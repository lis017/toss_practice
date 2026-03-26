package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// [변경] 멱등성 레코드 보관 기간 (현재 24시간, 필요 시 조정)
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
 * 해결 - 멱등성 키:
 * - 클라이언트가 요청 시 고유한 idempotencyKey(UUID) 포함
 * - 서버는 최초 처리 결과를 이 테이블에 저장
 * - 동일 idempotencyKey 재요청 시 저장된 결과를 즉시 반환 (실제 처리 스킵)
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

    @Column(nullable = false)
    private Long transactionId;             // 처리된 결제 트랜잭션 ID

    @Column(nullable = false)
    private Long amount;                    // 결제 금액 (응답 재구성용)

    @Column(nullable = false)
    private Long cashbackAmount;            // 지급된 캐시백 금액 (응답 재구성용)

    @Column(nullable = false)
    private LocalDateTime createdAt;        // 최초 처리 시각

    @Column(nullable = false)
    private LocalDateTime expiresAt;        // 만료 시각 (createdAt + 24시간), 이후 동일 키 재사용 허용

    @Builder
    public PaymentIdempotency(String idempotencyKey, Long transactionId, Long amount, Long cashbackAmount) {
        this.idempotencyKey = idempotencyKey;
        this.transactionId = transactionId;
        this.amount = amount;
        this.cashbackAmount = cashbackAmount;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plusHours(24); // [변경] 보관 기간 (현재 24시간)
    }
}
