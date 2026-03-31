package com.toss.cashback.global.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

// ======= [3번] 전역 예외 처리기 =======
/**
 * 전역 예외 처리기. 모든 API 에러 응답 형식을 ErrorResponse로 통일합니다.
 *
 * Validation 실패 시 어떤 필드가 왜 실패했는지 fields 배열로 상세히 반환해서
 * 클라이언트가 특정 필드에 에러 메시지를 표시할 수 있습니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 비즈니스 예외 (잔액 부족, 예산 소진, 외부 API 실패 등) */
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(
            CustomException ex, HttpServletRequest request) {

        ErrorCode errorCode = ex.getErrorCode();
        log.error("[비즈니스 예외] code={}, message={}, path={}",
                errorCode.getCode(), ex.getMessage(), request.getRequestURI());

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode, request.getRequestURI()));
    }

    /** Bean Validation 실패. 어떤 필드가 왜 실패했는지 fields 배열로 반환합니다 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ErrorResponse.FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorResponse.FieldErrorDetail(
                        fieldError.getField(),
                        fieldError.getDefaultMessage()))
                .collect(Collectors.toList());

        log.warn("[유효성 검증 실패] 오류 {}건, path={}, fields={}",
                fieldErrors.size(), request.getRequestURI(), fieldErrors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(fieldErrors, request.getRequestURI()));
    }

    /** 예상치 못한 예외 - 최후 방어선 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex, HttpServletRequest request) {

        log.error("[예상치 못한 예외] path={}, exceptionClass={}, message={}",
                request.getRequestURI(), ex.getClass().getName(), ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR, request.getRequestURI()));
    }
}
