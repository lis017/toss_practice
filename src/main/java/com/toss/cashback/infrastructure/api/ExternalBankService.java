package com.toss.cashback.infrastructure.api;

// ======= [7번] 외부 은행 API 인터페이스 =======
/**
 * =====================================================================
 * [설계 의도] 외부 은행 API 인터페이스 - infrastructure/api 레이어 배치
 * =====================================================================
 *
 * 인터페이스로 분리한 이유:
 * - 실제 구현체(RealExternalBankService)와 Mock(MockExternalBankService) 교체 가능
 * - OCP(개방-폐쇄 원칙): 실제 API 연동 시 이 인터페이스만 새로 구현하면 됨
 * - 테스트에서 @MockBean으로 외부 의존성 완전 제거 가능
 *
 * 추후 리팩터:
 * - 실제 외부 은행 API 연동: @FeignClient 또는 WebClient로 HTTP 통신 구현
 * - Resilience4j @CircuitBreaker + @TimeLimiter 어노테이션으로 선언적 처리
 * =====================================================================
 */
public interface ExternalBankService {

    /**
     * 외부 은행에 결제 승인 요청
     *
     * @param accountId 계좌 ID
     * @param amount    결제 금액
     * @throws com.toss.cashback.global.error.CustomException
     *   EXTERNAL_BANK_TIMEOUT: 3초 초과 시
     *   EXTERNAL_BANK_ERROR: 외부 API 오류 시
     */
    void approve(Long accountId, Long amount);
}
