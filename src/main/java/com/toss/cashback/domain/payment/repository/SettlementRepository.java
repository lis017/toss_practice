package com.toss.cashback.domain.payment.repository;

import com.toss.cashback.domain.payment.entity.SettlementRecord;
import com.toss.cashback.domain.payment.entity.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// ======= [2번] 정산 레코드 레포지토리 =======
/**
 * 정산 레코드 리포지토리
 *
 * 추후 리팩터:
 * - findPendingForSettlement()에 날짜 조건 추가 → 특정 날 이전 생성 건만 정산
 * - Pageable 파라미터로 대용량 정산 건 청크 처리 (배치 안정성)
 */
public interface SettlementRepository extends JpaRepository<SettlementRecord, Long> {

    /** PENDING 상태 정산 건 전체 조회 (정산 스케줄러 실행용) */
    List<SettlementRecord> findByStatus(SettlementStatus status);

    /** 특정 결제 트랜잭션의 정산 레코드 조회 (결제-정산 연결 확인용) */
    List<SettlementRecord> findByPaymentTransactionId(Long paymentTransactionId);
}
