package com.toss.cashback.domain.settlement.repository;

import com.toss.cashback.domain.settlement.entity.SettlementRecord;
import com.toss.cashback.domain.settlement.entity.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

// ======= [6번] 정산 레코드 레포지토리 =======
/**
 * 정산 레코드 리포지토리
 *
 * 추후 리팩터:
 * - findByStatus()에 날짜 조건 추가 → 특정 날 이전 생성 건만 정산
 * - Pageable 파라미터로 대용량 정산 건 청크 처리 (배치 안정성)
 */
public interface SettlementRepository extends JpaRepository<SettlementRecord, Long> {

    /** PENDING 상태 정산 건 전체 조회 (테스트/직접 조회용) */
    List<SettlementRecord> findByStatus(SettlementStatus status);

    /**
     * 정산 예정일이 기준일 이하인 PENDING 건만 조회 (정산 스케줄러 실행용)
     * D+1: 어제 이전 생성 건, D+2: 그제 이전 생성 건
     * 오늘 생성된 D+2 건은 내일 배치까지 대기 → README 명세와 실제 동작 일치
     */
    List<SettlementRecord> findByStatusAndExpectedSettlementDateLessThanEqual(
            SettlementStatus status, LocalDate date);

    /** 특정 결제 트랜잭션의 정산 레코드 조회 (결제-정산 연결 확인용) */
    List<SettlementRecord> findByPaymentTransactionId(Long paymentTransactionId);
}
