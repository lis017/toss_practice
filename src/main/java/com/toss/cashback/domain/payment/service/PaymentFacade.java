package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;
import com.toss.cashback.domain.settlement.service.SettlementService;
import com.toss.cashback.domain.webhook.service.WebhookService;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

// ======= [14번] 결제 Facade (C안 - PG 가상계좌 정산 구조) =======
/**
 * 결제 전체 흐름 조율. @Transactional 없이 각 단계별 트랜잭션을 분리합니다.
 *
 * C안 (PG 가상계좌 정산) 흐름:
 * 1단계(즉시): 외부 승인 → 구매자(A) 출금 → 가상계좌(C) 입금 → 정산 레코드 생성
 * 2단계(정산): SettlementScheduler가 매일 PENDING 정산 건을 일괄 처리 → 가맹점(B) 입금
 *
 * 실제 PG(토스페이먼츠) 구조:
 * - 구매자는 결제 즉시 완료 응답을 받음 (1단계 완료 = 구매자 입장에서는 결제 완료)
 * - 가맹점은 D+1, D+2 등 정산 주기에 따라 실제 입금받음
 * - 정산 실패 시 구매자 환불 없음. 가상계좌에 자금 보관 → 다음 정산 주기 재시도
 *
 * DB 커넥션 풀 고갈 방지:
 * - 외부 API 호출 구간에는 열린 트랜잭션 없음 → 커넥션 미점유
 * - 각 STEP이 독립 트랜잭션으로 커밋 후 즉시 반환
 *
 * 멱등성 처리 전략:
 * - 결제 시작 전 PROCESSING 상태로 선등록 (unique constraint로 동시 중복 요청 차단)
 * - 처리 완료 후 COMPLETED로 업데이트
 * - 외부 API 실패(STEP 1): 레코드 삭제 → 같은 키로 재시도 허용
 * - 외부 API 성공 후 후처리 실패(STEP 2~4): 레코드 PROCESSING 유지 → 자동 재시도 차단
 *   (이유: 은행은 이미 출금 처리했을 수 있어 동일 키로 재결제 시 이중 청구 위험)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final ExternalBankService externalBankService;
    private final SettlementService settlementService;
    private final WebhookService webhookService;
    private final PaymentIdempotencyRepository idempotencyRepository;
    private final AccountRepository accountRepository;

    @Value("${cashback.virtual-account.account-number:TOSS-VIRTUAL-001}")
    private String virtualAccountNumber;

    private Long virtualAccountId;  // 서버 시작 시 DB에서 조회해 캐싱 (매 요청마다 조회 방지)

    /**
     * 서버 시작 시 가상계좌 ID를 조회해 캐싱.
     *
     * @PostConstruct 대신 ApplicationReadyEvent를 사용하는 이유:
     * - @PostConstruct는 빈 초기화 단계에서 실행 → DataInitializer(ApplicationRunner)보다 먼저 실행됨
     * - ApplicationReadyEvent는 모든 ApplicationRunner 완료 후 발행 → DB에 가상계좌가 이미 존재함
     * - 덕분에 최초 실행(빈 DB) 시에도 정상 동작 + 테스트 환경(H2)에서도 안전
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadVirtualAccountId() {
        virtualAccountId = accountRepository.findByAccountNumber(virtualAccountNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "[초기화 실패] 가상계좌를 찾을 수 없습니다. account-number=" + virtualAccountNumber))
                .getId();
        log.info("[초기화] 가상계좌 ID 캐싱 완료 - virtualAccountId={}", virtualAccountId);
    }

    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("[결제 시작] from={}, to={}, amount={}, key={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount(), request.getIdempotencyKey());

        // 멱등성 키 선등록: PROCESSING 상태로 먼저 저장 → 동시 중복 요청을 DB 제약으로 차단
        PaymentIdempotency idempotencyRecord = getOrCreateIdempotencyRecord(request);

        // COMPLETED 상태: 이미 완료된 동일 요청 → 저장된 결과 즉시 반환 (재처리 없음)
        if (idempotencyRecord.isCompleted()) {
            log.info("[멱등성] 완료된 중복 요청 반환 - key={}, txId={}",
                    request.getIdempotencyKey(), idempotencyRecord.getTransactionId());
            return PaymentResponse.success(
                    idempotencyRecord.getTransactionId(),
                    idempotencyRecord.getAmount()
            );
        }

        // STEP 1: 외부 은행 승인 (구매자 계좌 출금 승인 - DB 커넥션 미점유 상태)
        // 승인 전 DB 미변경 → 실패 시 원복할 것 없음
        try {
            externalBankService.approve(request.getFromAccountId(), request.getAmount());
            log.info("[STEP 1 완료] 구매자 출금 승인 - from={}", request.getFromAccountId());
        } catch (CustomException e) {
            idempotencyRepository.delete(idempotencyRecord);
            log.error("[STEP 1 실패] 구매자 출금 승인 실패 - {}", e.getMessage());
            throw e;
        }

        // STEP 2: 구매자(A) → 가상계좌(C) 이체 (TX1 커밋 → DB 커넥션 즉시 반환)
        // ★ 외부 승인이 이미 완료됐으므로 실패해도 멱등성 레코드를 삭제하지 않음 (이중 청구 방지)
        Long transactionId;
        try {
            transactionId = paymentService.executeTransfer(
                    request.getFromAccountId(),
                    request.getToAccountId(),     // 가맹점 B (정산 수신처, 기록용)
                    virtualAccountId,              // 가상계좌 C (실제 자금 이동 대상)
                    request.getAmount());
            log.info("[STEP 2 완료] 가상계좌 보관 - txId={}", transactionId);
        } catch (Exception e) {
            // [긴급] 외부 은행 승인은 완료됐으나 DB 이체 실패 → 자금 불일치 가능성
            // 멱등성 레코드 PROCESSING 유지 → 동일 키로 재결제 차단 (이중 청구 방지)
            log.error("[긴급-STEP2실패] 외부 승인 완료 후 이체 실패 - from={}, amount={}, key={}, error={}",
                    request.getFromAccountId(), request.getAmount(), request.getIdempotencyKey(), e.getMessage());
            log.error("[복구 가이드] 1) 외부 은행에 해당 승인 건 실제 처리 여부 확인" +
                    " 2) 출금 확인됐다면 수동 이체 처리 또는 외부 은행 승인 취소 요청");
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        // STEP 3~4: 후처리 (이체 완료가 전제 - 실패 시 POST_PROCESS_FAILED 기록)
        try {
            // STEP 3: 정산 레코드 생성 (PENDING 상태로 등록 → 스케줄러가 처리)
            settlementService.createSettlementRecord(
                    transactionId, virtualAccountId, request.getToAccountId(), request.getAmount());
            log.info("[STEP 3 완료] 정산 레코드 등록 - txId={}, merchant={}", transactionId, request.getToAccountId());

            // STEP 4: 트랜잭션 PENDING_SETTLEMENT 상태 업데이트
            paymentService.markTransactionPendingSettlement(transactionId);
            log.info("[STEP 4 완료] 정산 대기 상태 - txId={}", transactionId);

        } catch (Exception postProcessEx) {
            // [긴급] 이체 완료 후 후처리(정산 등록 or 상태 업데이트) 실패
            // → PaymentTransaction POST_PROCESS_FAILED 기록, 멱등성 PROCESSING 유지
            log.error("[긴급-후처리실패] 이체 완료 후 후처리 실패 - txId={}, error={}",
                    transactionId, postProcessEx.getMessage());
            log.error("[복구 가이드] 1) payment_transactions에서 txId={} 건 확인" +
                    " 2) settlement_records에 해당 건 수동 삽입 여부 확인" +
                    " 3) 정상화 후 status를 PENDING_SETTLEMENT로 수동 업데이트", transactionId);
            paymentService.markTransactionPostProcessFailed(transactionId, postProcessEx.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        // 멱등성 레코드 COMPLETED로 업데이트 (다음 동일 요청은 이 결과를 바로 반환)
        idempotencyRecord.complete(transactionId, request.getAmount());
        idempotencyRepository.save(idempotencyRecord);

        // 웹훅 발송 (실패해도 결제 응답에는 영향 없음)
        try {
            webhookService.sendPaymentCompleted(transactionId, request.getToAccountId(), request.getAmount());
        } catch (Exception e) {
            log.warn("[웹훅] 서비스 예외 발생 (결제 응답에 영향 없음) - txId={}", transactionId);
        }

        // 구매자 입장에서는 결제 완료 응답 반환 (가맹점 정산은 스케줄러가 처리)
        return PaymentResponse.success(transactionId, request.getAmount());
    }

    /**
     * 멱등성 레코드 조회 또는 PROCESSING 상태로 신규 생성.
     *
     * 응답 정책:
     * - 기존 COMPLETED + 동일 요청: 캐시된 결과 그대로 반환 (재처리 없음)
     * - 기존 COMPLETED + 다른 요청: 409 IDEMPOTENCY_REQUEST_MISMATCH (위조 방지)
     * - 기존 PROCESSING + 동일 요청: 409 DUPLICATE_PAYMENT_REQUEST (처리 중 안내)
     * - 기존 PROCESSING + 다른 요청: 409 IDEMPOTENCY_REQUEST_MISMATCH (위조 방지)
     * - 신규: PROCESSING 상태로 saveAndFlush (unique constraint로 동시 중복 요청 차단)
     */
    private PaymentIdempotency getOrCreateIdempotencyRecord(PaymentRequest request) {
        PaymentIdempotency existing = idempotencyRepository
                .findByIdempotencyKeyAndExpiresAtAfter(request.getIdempotencyKey(), LocalDateTime.now())
                .orElse(null);

        if (existing != null) {
            // [공통] 요청 내용 불일치 → 즉시 거부 (COMPLETED/PROCESSING 모두 동일)
            if (!existing.matchesRequest(request.getFromAccountId(),
                    request.getToAccountId(), request.getAmount())) {
                log.warn("[멱등성] 요청 불일치 - key={}, 기존amount={}, 요청amount={}",
                        request.getIdempotencyKey(), existing.getRequestAmount(), request.getAmount());
                throw new CustomException(ErrorCode.IDEMPOTENCY_REQUEST_MISMATCH);
            }

            if (existing.isCompleted()) {
                return existing;  // 캐시된 완료 결과 반환
            }

            // PROCESSING + 동일 요청: 처리 중 중복 안내
            log.warn("[멱등성] 처리 중인 중복 요청 - key={}", request.getIdempotencyKey());
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }

        try {
            return idempotencyRepository.saveAndFlush(
                    PaymentIdempotency.createProcessing(
                            request.getIdempotencyKey(),
                            request.getFromAccountId(),
                            request.getToAccountId(),
                            request.getAmount()
                    ));
        } catch (DataIntegrityViolationException e) {
            log.warn("[멱등성] 동시 중복 요청 차단 - key={}", request.getIdempotencyKey());
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }
    }
}
