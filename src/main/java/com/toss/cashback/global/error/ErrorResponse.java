package com.toss.cashback.global.error;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

// ======= [3번] API 에러 응답 =======
/**
 * =====================================================================
 * [설계 의도] API 에러 응답 공통 포맷
 * =====================================================================
 *
 * 비즈니스 에러 응답 예시:
 * {
 *   "timestamp": "2024-01-01T12:00:00",
 *   "code": "A002",
 *   "message": "잔액이 부족합니다",
 *   "path": "/api/v1/payments",
 *   "fields": null
 * }
 *
 * Validation 에러 응답 예시 (fields 포함):
 * {
 *   "timestamp": "2024-01-01T12:00:00",
 *   "code": "V001",
 *   "message": "입력값이 유효하지 않습니다",
 *   "path": "/api/v1/payments",
 *   "fields": [
 *     { "field": "fromAccountId", "message": "송금 계좌 ID는 필수입니다" },
 *     { "field": "amount",        "message": "결제 금액은 0보다 커야 합니다" }
 *   ]
 * }
 *
 * 추후 리팩터:
 * - fields를 Map<String, String>으로 변환하면 클라이언트 파싱 더 간편
 * - i18n 지원 시 message를 MessageSource로 분리
 * =====================================================================
 */
@Getter
@Builder
public class ErrorResponse {

    private final LocalDateTime timestamp;          // 에러 발생 시각
    private final String code;                      // 내부 에러 코드 (클라이언트 분기 처리용)
    private final String message;                   // 에러 메시지
    private final String path;                      // 에러가 발생한 요청 경로
    private final List<FieldErrorDetail> fields;    // 유효성 검증 실패 시 필드별 오류 목록 (null이면 비표시)

    /** 비즈니스/시스템 에러용 (fields 없음) */
    public static ErrorResponse of(ErrorCode errorCode, String path) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .path(path)
                .build();
    }

    /** 비즈니스 에러 + 커스텀 상세 메시지용 */
    public static ErrorResponse of(ErrorCode errorCode, String path, String detailMessage) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(errorCode.getCode())
                .message(detailMessage)
                .path(path)
                .build();
    }

    /** Validation 실패 전용 (fields 포함) */
    public static ErrorResponse ofValidation(List<FieldErrorDetail> fields, String path) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(ErrorCode.VALIDATION_FAILED.getCode())    // V001
                .message(ErrorCode.VALIDATION_FAILED.getMessage())
                .path(path)
                .fields(fields)
                .build();
    }

    // =====================================================================
    // [설계 의도] 필드별 오류 상세 정보 - 중첩 클래스로 응집도 유지
    // 응답 예시: { "field": "fromAccountId", "message": "송금 계좌 ID는 필수입니다" }
    // =====================================================================
    @Getter
    @AllArgsConstructor
    public static class FieldErrorDetail {
        private final String field;     // 오류 발생 필드명
        private final String message;   // 해당 필드의 오류 메시지 (@NotNull message 값)
    }
}
