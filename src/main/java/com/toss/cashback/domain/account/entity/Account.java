package com.toss.cashback.domain.account.entity;

import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// ======= [4번] 계좌 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] 계좌 도메인 엔티티 - 도메인 모델 패턴 적용
 * =====================================================================
 *
 * 도메인 모델 패턴:
 * - 비즈니스 로직(withdraw, deposit)을 Entity 내부에 배치
 * - Service 계층이 단순 "절차 호출자"로 유지 → 비즈니스 규칙의 Entity 응집
 *
 * 동시성 전략:
 * - 계좌 이체: Redisson MultiLock + TransactionTemplate으로 처리
 * - 데드락 방지: 항상 낮은 락 키 순서로 획득
 *
 * 추후 리팩터:
 * - balance 변경 이력 TransactionHistory 테이블 분리 → 감사 로그 강화
 * - 계좌 상태(ACTIVE/SUSPENDED) Enum 컬럼 추가 → 계좌 정지 기능 확장
 * =====================================================================
 */
@Entity
@Table(
    name = "accounts",
    indexes = {
        @Index(name = "idx_account_number", columnList = "accountNumber", unique = true)
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 기본 생성자 (외부 직접 생성 방지)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                    // 계좌 고유 ID (PK)

    @Column(nullable = false, unique = true, length = 50)
    private String accountNumber;                       // 계좌 번호 (예: "110-1234-5678")

    @Column(nullable = false, length = 50)
    private String ownerName;                           // 계좌 소유자 이름

    @Column(nullable = false)
    private Long balance;                               // 현재 잔액 (원 단위)

    @Builder
    public Account(String accountNumber, String ownerName, Long balance) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.balance = balance;
    }

    // =====================================================================
    // [비즈니스 로직] 출금 - 잔액 부족 시 즉시 예외 (Fail Fast 원칙)
    // @Transactional 내에서 호출 → 예외 발생 시 자동 롤백 보장
    // =====================================================================
    public void withdraw(Long amount) {
        validatePositiveAmount(amount);                         // 양수 검증
        if (this.balance < amount) {
            throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance -= amount;                                 // Dirty Checking → 자동 UPDATE
    }

    // =====================================================================
    // [비즈니스 로직] 입금
    // =====================================================================
    public void deposit(Long amount) {
        validatePositiveAmount(amount);
        this.balance += amount;                                 // Dirty Checking → 자동 UPDATE
    }

    private void validatePositiveAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new CustomException(ErrorCode.INVALID_AMOUNT);
        }
    }
}
