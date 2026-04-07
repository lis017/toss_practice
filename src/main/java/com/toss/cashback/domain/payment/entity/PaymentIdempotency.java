package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

// ======= [5번] 멱등성 레코드 엔티티 =======
// 24시간 이후 동일 idempotencyKey로 재요청하면 새 결제로 처리됨에 주의

/**
 * =====================================================================
 * [설계 의도] 멱등성(Idempotency) 기록 엔티티
 * =====================================================================
 *
 * 문제:
 * - 클라이언트가 네트워크 오류로 결제 요청을 재시도하면 중복 결제 발생
 * - 예: 모바일 앱이 응답 수신 실패 → 같은 요청 재전송 → 계좌에서 두 번 출금
 *
 * 해결 - 멱등성 키 선등록:
 * - 결제 처리 시작 전 PROCESSING 상태로 먼저 저장 (unique constraint)
 * - 동시 중복 요청이 들어와도 DB 제약으로 하나만 통과
 * - 처리 완료 후 COMPLETED로 업데이트 → 이후 재요청은 캐시된 결과 반환
 * - 이체 실패/보상 완료 시 레코드 삭제 → 같은 키로 재시도 허용
 *
 * DB vs Redis:
 * - DB 선택 이유: Redis 장애 시에도 멱등성 보장 필요 (결제 중복은 치명적)
 * - 추후 Redis 캐시 레이어 추가로 성능 개선 가능 (DB는 백업 역할)
 *
 * TTL 전략:
 * - expiresAt 컬럼으로 만료 시각 관리 (기본 24시간)
 * - IdempotencyCleanupScheduler가 매일 새벽 3시 만료 레코드 일괄 삭제
 * - 조회 시 expiresAt 조건 포함 → 만료된 레코드는 새 요청으로 처리
 *
 * 추후 리팩터:
 * - Redis + DB 이중 저장으로 조회 성능 개선
 * - expiresAt을 이벤트별 정책으로 유연하게 설정 가능
 * =====================================================================
 */
@Entity
@Table(
    name = "payment_idempotency",
    indexes = {
        @Index(name = "idx_idempotency_key", columnList = "idempotencyKey", unique = true)
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;          // 클라이언트가 보낸 고유 키 (UUID 권장)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;       // PROCESSING(진행 중) / COMPLETED(완료)

    @Column(nullable = true)
    private Long transactionId;             // 처리된 결제 트랜잭션 ID (COMPLETED 시 설정)

    @Column(nullable = true)
    private Long amount;                    // 결제 금액 (COMPLETED 시 설정, 응답 재구성용)

    // =====================================================================
    // [요청 스냅샷] 최초 처리된 요청 내용 저장 → 동일 키 재요청 시 불일치 검증
    // 같은 키 + 다른 요청 바디 → 409 IDEMPOTENCY_REQUEST_MISMATCH
    // =====================================================================
    @Column(nullable = true)
    private Long requestFromAccountId;      // 최초 요청의 송금 계좌 ID

    @Column(nullable = true)
    private Long requestToAccountId;        // 최초 요청의 수신 계좌 ID

    @Column(nullable = true)
    private Long requestAmount;             // 최초 요청의 결제 금액

    @Column(nullable = false)
    private LocalDateTime createdAt;        // 최초 처리 시각

    @Column(nullable = false)
    private LocalDateTime expiresAt;        // 만료 시각 (createdAt + 24시간), 이후 동일 키 재사용 허용

    /**
     * 결제 처리 시작 시 PROCESSING 상태로 먼저 저장.
     * 요청 스냅샷(from/to/amount)을 함께 저장 → 재요청 시 불일치 검증에 사용.
     * unique constraint 덕분에 동시 중복 요청 중 하나만 저장 성공.
     */
    public static PaymentIdempotency createProcessing(String idempotencyKey,
                                                       Long fromAccountId,
                                                       Long toAccountId,
                                                       Long amount) {
        PaymentIdempotency record = new PaymentIdempotency();
        record.idempotencyKey = idempotencyKey;
        record.status = IdempotencyStatus.PROCESSING;
        record.requestFromAccountId = fromAccountId;
        record.requestToAccountId = toAccountId;
        record.requestAmount = amount;
        record.createdAt = LocalDateTime.now();
        record.expiresAt = record.createdAt.plusHours(24);
        return record;
    }

    /**
     * 동일 키 재요청 시 요청 내용 일치 여부 검증.
     * 모두 일치하면 true (동일 요청 재시도) → 캐시된 응답 또는 진행 중 안내 반환.
     * 하나라도 다르면 false (다른 요청 위조 시도) → 409 Conflict 반환.
     */
    public boolean matchesRequest(Long fromAccountId, Long toAccountId, Long amount) {
        return Objects.equals(this.requestFromAccountId, fromAccountId)
                && Objects.equals(this.requestToAccountId, toAccountId)
                && Objects.equals(this.requestAmount, amount);
    }

    /**
     * 결제 완료 후 COMPLETED로 업데이트
     * 이후 동일 키 재요청 시 여기 저장된 결과를 바로 반환
     */
    public void complete(Long transactionId, Long amount) {
        this.status = IdempotencyStatus.COMPLETED;
        this.transactionId = transactionId;
        this.amount = amount;
    }

    public boolean isCompleted() {
        return this.status == IdempotencyStatus.COMPLETED;
    }
}
