package com.toss.cashback.global.error;

import lombok.Getter;

// ======= [3번] 커스텀 예외 클래스 =======
/**
 * =====================================================================
 * [설계 의도] 도메인 전용 런타임 예외
 * =====================================================================
 * RuntimeException 상속 이유:
 * - Spring @Transactional 기본 롤백 대상 = RuntimeException (Unchecked)
 * - Checked Exception은 throws 선언이 모든 계층에 전파 → 코드 복잡도 증가
 * =====================================================================
 */
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;      // 에러 코드 (HTTP 상태 + 코드 + 메시지 포함)

    /** 기본 생성자: ErrorCode의 기본 메시지 사용 */
    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());      // 로그에서 getMessage()로 확인 가능
        this.errorCode = errorCode;
    }

    /** 상세 메시지 생성자: 상황별 커스텀 메시지 사용 */
    public CustomException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }
}
