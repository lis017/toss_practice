package com.toss.cashback.global.util;

import java.net.URI;

/**
 * =====================================================================
 * [보안] 로그 마스킹 유틸리티
 * =====================================================================
 *
 * 운영 보안 원칙:
 * - 로그에 개인정보/민감 정보 전체 출력은 금지 (GDPR, 개인정보보호법 준수)
 * - idempotencyKey: 결제 추적용 앞 6자리만 출력 → 디버깅 가능 + 전체 노출 방지
 * - 계좌번호: 뒷 4자리 마스킹 → 출력 패턴으로 소유자 식별 불가
 * - webhookUrl: 도메인만 출력 → 내부 API 경로 노출 방지
 * - payload: 전체 출력 금지 → 거래 금액/계좌 정보 로그 유출 차단
 *
 * 적용 범위:
 * - PaymentFacade: idempotencyKey 로그
 * - WebhookService: webhookUrl, payload 로그
 * =====================================================================
 */
public final class LogMaskUtil {

    private LogMaskUtil() { /* 인스턴스 생성 금지 */ }

    /**
     * 멱등성 키 마스킹: 앞 6자리 + "****"
     * 예) "550e84xx-xxxx-..." → "550e84****"
     */
    public static String maskKey(String key) {
        if (key == null || key.length() < 6) return "****";
        return key.substring(0, 6) + "****";
    }

    /**
     * 계좌번호 마스킹: 앞 N-4 자리만 + "****"
     * 예) "110-1234-5678" → "110-1234-****"
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) return "****";
        return accountNumber.substring(0, accountNumber.length() - 4) + "****";
    }

    /**
     * 웹훅 URL 마스킹: 도메인만 출력 + "/****"
     * 예) "https://merchant.com/api/webhook/v2" → "https://merchant.com/****"
     *
     * 목적: 가맹점 내부 API 경로/버전 정보 로그 노출 방지
     */
    public static String maskUrl(String url) {
        if (url == null || url.isBlank()) return "(없음)";
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/****";
        } catch (Exception e) {
            return "****";  // 파싱 실패 시 전체 마스킹
        }
    }

    /**
     * payload 마스킹: 로그 출력 금지 (거래 금액 등 민감 정보 포함)
     * 실제 payload는 webhook_deliveries 테이블에서 직접 조회
     */
    public static String maskPayload(String payload) {
        if (payload == null) return "(없음)";
        return "[payload " + payload.length() + "bytes - 보안 마스킹]";
    }
}
