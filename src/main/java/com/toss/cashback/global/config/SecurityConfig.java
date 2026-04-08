package com.toss.cashback.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * =====================================================================
 * [보안] Spring Security 최소 설정
 * =====================================================================
 *
 * 설계 원칙:
 * - 과제 특성상 /api/v1/** 는 인증 없이 허용 (운영에서는 JWT/OAuth2 추가)
 * - /actuator/prometheus, /actuator/metrics 는 외부 접근 차단
 *   → 실운영: Prometheus 서버 IP 화이트리스트 또는 내부망 전용
 * - /actuator/health 는 로드 밸런서/k8s liveness probe용으로 허용
 * - /api/v1/admin/** 는 운영에서 별도 ADMIN 역할 제한 필요
 *
 * 비밀값 외부화 (운영 필수):
 * - DB_USERNAME, DB_PASSWORD → 환경변수 또는 AWS Secrets Manager / Vault
 * - 현재 application.yml의 ${DB_PASSWORD:cashback_pass} 형태는 로컬/과제용
 * - CI/CD 파이프라인에서 환경변수 주입 또는 ConfigMap(k8s) 사용 권장
 *
 * 웹훅 보안 (추후 확장):
 * - 현재: 가맹점 URL로 단방향 발송
 * - 실운영: HMAC-SHA256 서명 헤더 추가 (X-Webhook-Signature: hmac_sha256(secret, payload))
 *   → 가맹점이 서명 검증으로 토스 서버 발송 확인 (위변조 방지)
 * =====================================================================
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // REST API → CSRF 토큰 불필요 (세션 없는 stateless)
            .csrf(AbstractHttpConfigurer::disable)

            // REST API → 세션 사용 안 함 (JWT 사용 시 필수)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                // [외부 차단] 메트릭/프로메테우스는 내부 모니터링 전용
                // 실운영: IP 화이트리스트 또는 내부망 전용 포트(9090)로 분리 권장
                .requestMatchers(
                    "/actuator/prometheus",
                    "/actuator/metrics",
                    "/actuator/metrics/**"
                ).denyAll()

                // [허용] 헬스체크 (로드 밸런서, k8s probe)
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                // [허용] 결제 API (과제 특성상 인증 없이 공개)
                // 운영: 가맹점 API 키 검증 헤더(Authorization: Bearer {apiKey}) 추가 필요
                .requestMatchers("/api/v1/**").permitAll()

                // 그 외 미정의 경로는 차단
                .anyRequest().denyAll()
            );

        return http.build();
    }
}
