package com.toss.cashback.domain.payment.repository;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 결제 트랜잭션 이력 리포지토리
 *
 * 추후 리팩터:
 * - 대용량 이력 시 Pageable 파라미터 추가로 페이징 처리
 * - QueryDSL 또는 Specification으로 복합 조건 동적 쿼리 지원
 * - COMPENSATED 건만 조회하는 배치로 수동 검토 프로세스 구축 가능
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** 특정 계좌의 결제 이력 최신순 조회 (마이페이지, 관리자 페이지용) */
    List<PaymentTransaction> findByFromAccountIdOrderByCreatedAtDesc(Long fromAccountId);

    /** 특정 상태의 결제 이력 조회 (배치 처리, 운영 모니터링용) */
    List<PaymentTransaction> findByStatus(PaymentStatus status);
}
