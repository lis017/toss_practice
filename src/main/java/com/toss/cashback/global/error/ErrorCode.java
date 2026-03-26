package com.toss.cashback.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 목록. A(계좌), C(캐시백), E(외부API), V(유효성), CB(서킷브레이커), S(시스템) 접두사로 구분합니다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ======================== A: 계좌 관련 에러 ========================
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "계좌를 찾을 수 없습니다"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "A002", "잔액이 부족합니다"),
    SAME_ACCOUNT_TRANSFER(HttpStatus.BAD_REQUEST, "A003", "동일한 계좌로는 이체할 수 없습니다"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "A004", "결제 금액이 유효하지 않습니다 (0보다 커야 합니다)"),

    // ======================== C: 캐시백 관련 에러 ========================
    CASHBACK_BUDGET_EXCEEDED(HttpStatus.OK, "C001", "캐시백 이벤트 예산이 모두 소진되었습니다"),
    CASHBACK_BUDGET_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "캐시백 예산 정보를 찾을 수 없습니다"),
    LOCK_ACQUISITION_FAILED(HttpStatus.CONFLICT, "C003", "요청이 많아 처리 중입니다. 잠시 후 다시 시도해주세요"),

    // ======================== E: 외부 은행 API 관련 에러 ========================
    EXTERNAL_BANK_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "E001", "외부 은행 API 응답 시간이 초과되었습니다"),
    EXTERNAL_BANK_ERROR(HttpStatus.BAD_GATEWAY, "E002", "외부 은행 API 오류가 발생했습니다"),
    EXTERNAL_BANK_REJECTED(HttpStatus.BAD_REQUEST, "E003", "외부 은행에서 결제를 승인하지 않았습니다"),

    // ======================== V: 유효성 검증 에러 ========================
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "V001", "입력값이 유효하지 않습니다"),

    // ======================== P: 결제 요청 관련 에러 ========================
    DUPLICATE_PAYMENT_REQUEST(HttpStatus.OK, "P001", "이미 처리된 요청입니다 (멱등성 키 중복)"),

    // ======================== CB: 서킷 브레이커 에러 ========================
    // 외부 은행 API가 반복 실패 → 서킷 Open 상태 → 즉시 차단 (불필요한 재시도 방지)
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "CB001", "외부 은행 서비스가 일시적으로 차단되었습니다. 잠시 후 다시 시도해주세요"),

    // ======================== S: 시스템 에러 ========================
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "S001", "서버 내부 오류가 발생했습니다"),
    COMPENSATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S002", "보상 트랜잭션 처리 중 오류가 발생했습니다");

    private final HttpStatus httpStatus;    // HTTP 응답 상태 코드
    private final String code;              // 내부 에러 코드 (클라이언트 분기 처리용)
    private final String message;           // 사용자에게 보여줄 에러 메시지
}
