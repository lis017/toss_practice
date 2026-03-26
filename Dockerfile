# ============================================================
# [위치] 프로젝트 최상단 (설정파일 경로 규칙 준수)
#
# 빌드 방법:
#   docker build -t cashback-system .
#
# 실행 방법 (docker-compose 권장):
#   docker-compose up -d
#
# 수동 실행:
#   docker run -p 8080:8080 cashback-system
#
# [변경] 포트 변경 시 EXPOSE와 docker run의 -p 옵션 둘 다 수정
# [변경] JVM 옵션 변경 시 ENTRYPOINT의 java 옵션 수정
# ============================================================

# 빌드 스테이지 (Gradle 빌드)
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Gradle wrapper + 설정 파일 복사 (의존성 캐시 레이어 분리)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# 의존성 다운로드 (소스 변경 없으면 이 레이어 캐시 재사용)
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon

# 소스 코드 복사 후 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon

# ──────────────────────────────────────────────────────────

# 실행 스테이지 (JRE만 포함 - 이미지 크기 최소화)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 빌드 결과물 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# 타임존 설정 (created_at 등 시각 정합성)
ENV TZ=Asia/Seoul

# [변경] 운영 환경 JVM 옵션 조정 가능
# -Xms256m: 최소 힙 메모리 / -Xmx512m: 최대 힙 메모리
# [변경] 애플리케이션 포트 변경 시 아래 8080 수정
EXPOSE 8080

ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
