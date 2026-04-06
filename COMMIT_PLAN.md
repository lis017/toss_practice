# 커밋 순서 계획 (5시간 과제용)

> **코드 내 `[N번]` 주석 검색 꿀팁:** Ctrl+Shift+F → `[N번]` 입력
> 코드 주석 `[N번]` = 커밋 N번에서 작성된 코드

---

## 커밋 메시지 템플릿

### 1번
```
docs: README 초기 작성 - 요구사항 분석 및 설계 계획

- 패키지 구조 및 전체 아키텍처 정의 (domain/global/infrastructure)
- 핵심/비기능 요구사항 분석표 작성
- 결제 처리 흐름도 및 트랜잭션 상태 전이 정의
- 설계 결정 이유 선작성 (트랜잭션 분리, 분산 락, 서킷 브레이커, 멱등성)
```
**파일:** `README.md`

**의도:** 코딩 시작 전 요구사항을 정확히 이해하고 전체 구조를 확정한 뒤 구현 시작

---

### 2번
```
chore: 프로젝트 초기 설정

- Spring Boot 3.2 / Java 17 기반 단일 프로젝트 구성
- Docker Compose로 MySQL + Redis + 앱 단일 명령 실행 환경 구성
- 테스트용 H2 인메모리 DB 분리 (application-test.yml)
- @EnableScheduling: SettlementScheduler, IdempotencyCleanupScheduler 활성화
```
**파일:** `build.gradle`, `Dockerfile`, `docker-compose.yml`, `application.yml`, `application-test.yml`, `gradle.properties`, `CashbackSystemApplication`

**체크:** `./gradlew build` 에러 없음

---

### 3번
```
feat: 공통 예외 처리 구현

- ErrorCode enum으로 HTTP 상태 + 에러 코드 + 메시지 일괄 관리 (A/C/E/V/P/CB/S 접두사)
- GlobalExceptionHandler: CustomException, Validation 에러 응답 형식 통일
- Validation 실패 시 어떤 필드가 왜 실패했는지 fields 배열로 상세 반환
```
**파일:** `ErrorCode`, `CustomException`, `ErrorResponse`, `GlobalExceptionHandler`

---

### 4번
```
feat: account 도메인 구현

- Account: 출금/입금/포인트 적립 비즈니스 로직을 엔티티 내부에 배치 (도메인 모델 패턴)
- withdraw: 잔액 부족 시 즉시 예외 (Fail Fast)
- AccountRepository: 계좌번호로 계좌 조회 지원
```
**파일:** `Account`, `AccountRepository`

---

### 5번
```
feat: payment 도메인 엔티티 및 레포지토리 구현

- PaymentTransaction: PENDING → PENDING_SETTLEMENT → SUCCESS 상태 흐름
  - virtualAccountId: 어느 가상계좌에 자금이 보관 중인지 추적
  - markPostProcessFailed(): 외부 승인 후 내부 처리 실패 상태 기록 (운영 복구용)
- PaymentIdempotency: PROCESSING/COMPLETED 상태 + 24시간 TTL (expiresAt)
- PaymentStatus, IdempotencyStatus enum
```
**파일:** `PaymentTransaction`, `PaymentStatus`, `PaymentIdempotency`, `IdempotencyStatus`, `PaymentTransactionRepository`, `PaymentIdempotencyRepository`

---

### 6번
```
feat: settlement 도메인 엔티티 및 레포지토리 구현

- MerchantSettlementPolicy: 가맹점별 수수료율/VAT/정산주기 정책
  - createDefault(): 미등록 가맹점 기본 정책 (3.5% / VAT포함 / D+1)
- SettlementRecord: PENDING → SETTLED 상태 관리
  - 정책 스냅샷(policyFeeRate, policyVatIncluded): 당시 조건 역추적 가능
  - 정산 실패 시 PENDING 유지 + failureReason 기록 → 다음 주기 자동 재시도
- SettlementAmountResult: 정산 계산 결과 불변 Value Object
```
**파일:** `MerchantSettlementPolicy`, `SettlementRecord`, `SettlementStatus`, `SettlementAmountResult`, `MerchantSettlementPolicyRepository`, `SettlementRepository`

---

### 7번
```
feat: webhook 도메인 엔티티 및 레포지토리 구현

- WebhookDelivery: PENDING → SUCCESS/FAILED 상태 관리
  - retryCount: 재시도 횟수 기록
  - lastFailureReason: 운영팀 디버깅용 실패 사유 보관
- WebhookDeliveryStatus, WebhookEventType enum
```
**파일:** `WebhookDelivery`, `WebhookDeliveryStatus`, `WebhookEventType`, `WebhookDeliveryRepository`

---

### 8번
```
feat: infrastructure 구현 (분산 락 + 외부 은행 API + 웹훅 클라이언트)

- RedissonConfig: 단일 노드 설정 (운영 전환 시 Sentinel/Cluster로 교체)
- RedissonLockService: 단일 키 락 / MultiLock (데드락 방지: 키 정렬 후 획득)
- ExternalBankService 인터페이스 + MockExternalBankService:
  - simulate-delay/error 플래그로 장애 시나리오 재현
  - Resilience4j Circuit Breaker: 5건 중 3건(60%) 실패 시 30초 차단
- WebhookClient 인터페이스 + MockWebhookClient:
  - simulate-error 플래그로 발송 실패 시나리오 재현
```
**파일:** `RedissonConfig`, `RedissonLockService`, `ExternalBankService`, `MockExternalBankService`, `WebhookClient`, `MockWebhookClient`, `application.yml` resilience4j 설정

---

### 9번
```
feat: PaymentService 구현 (계좌 이체 - 다중 서버 안전)

- executeTransfer: 구매자(A) → 가상계좌(C) 이체
  - Redisson MultiLock으로 두 계좌 동시 보호
  - 데드락 방지: Math.min/max로 낮은 ID 순서 락 획득
  - 락 내에서 TransactionTemplate으로 커밋 보장 → 락 해제 전 DB 반영 완료
- markTransactionPendingSettlement(): 1단계 완료 상태 변경
- markTransactionPostProcessFailed(): 후처리 실패 상태 기록
```
**파일:** `PaymentService`

---

### 10번
```
test: PaymentService 단위 테스트

- 정상 이체: 구매자 잔액 차감 + 가상계좌 잔액 증가 검증
- 잔액 부족: INSUFFICIENT_BALANCE 예외 + 잔액 미변경 검증
- 동일 계좌: SAME_ACCOUNT_TRANSFER 예외 + 락/DB 조회 없음 검증
- 음수 금액: INVALID_AMOUNT 예외 검증
- Spring Context 없이 Mockito 단위 테스트 (빠른 실행)
```
**파일:** `PaymentServiceTest`

**체크:** `./gradlew test --tests PaymentServiceTest` 초록불

---

### 11번
```
feat: SettlementCalculator + SettlementService + SettlementScheduler 구현

- SettlementCalculator: 수수료/VAT/실지급액 계산 (HALF_EVEN 반올림)
- SettlementService:
  - createSettlementRecord(): 결제 완료 시 PENDING 정산 레코드 생성 (정책 스냅샷 포함)
  - processAllPendingSettlements(): PENDING 건 일괄 처리 (단건 독립 처리 - 한 건 실패 → 나머지 계속)
  - settleOne(): 외부 승인 → 가상계좌→가맹점 이체 → SETTLED
- SettlementScheduler: @Scheduled(cron="0 0 2 * * *") 매일 새벽 2시 실행
```
**파일:** `SettlementCalculator`, `SettlementService`, `SettlementScheduler`

---

### 12번
```
test: SettlementCalculator 단위 테스트

- 기본 케이스: 수수료 3.5% + VAT 포함 + D+1 → 금액 정확히 계산
- 수수료 면제: feeRate=0% → netAmount = grossAmount 전액
- HALF_EVEN 반올림: 소수 발생 케이스 검증 (10,001 × 3.5% = 350.035 → 350)
- netAmount 항등식: grossAmount = feeAmount + vatAmount + netAmount 검증
```
**파일:** `SettlementCalculatorTest`

**체크:** `./gradlew test --tests SettlementCalculatorTest` 초록불

---

### 13번
```
feat: WebhookService 구현 (발송 + 1회 재시도)

- MerchantSettlementPolicy에서 webhookUrl 조회 (미등록이면 스킵)
- WebhookDelivery 레코드 PENDING 생성 → 발송 → SUCCESS/FAILED 저장
- 1차 실패 시 1회 재시도 (재시도 성공이면 SUCCESS, 또 실패면 FAILED)
- 예외를 외부로 전파하지 않음 → 웹훅 실패가 결제 응답에 영향 없음
```
**파일:** `WebhookService`

---

### 14번
```
feat: PaymentFacade 구현 (결제 전체 흐름 조율)

- 멱등성 선등록: PROCESSING → 외부 승인 실패 시 삭제 / 완료 후 COMPLETED
- @Transactional 없이 단계별 독립 트랜잭션 (DB 커넥션 풀 고갈 방지)
- STEP1 외부 승인 → STEP2 구매자→가상계좌 이체 → STEP3 정산 레코드 생성 → STEP4 상태 업데이트
- 후처리 실패 시 POST_PROCESS_FAILED 기록 + 멱등성 PROCESSING 유지 (이중 청구 방지)
- ApplicationReadyEvent로 가상계좌 ID 캐싱 (DataInitializer 완료 후 실행 보장)
```
**파일:** `PaymentFacade`

---

### 15번
```
feat: 결제 API 엔드포인트 및 초기 데이터 구현

- PaymentController: POST /api/v1/payments, GET /api/v1/health (Thin Controller)
- PaymentRequest: @NotBlank + @Size + @Pattern(UUID) + @NotNull + @Positive 검증
- DataInitializer: 서버 시작 시 테스트 계좌 3개(구매자/가맹점/가상계좌) + 정산 정책 자동 생성
- IdempotencyCleanupScheduler: 매일 새벽 3시 만료(24h) 멱등성 레코드 일괄 삭제
```
**파일:** `PaymentController`, `PaymentRequest`, `PaymentResponse`, `DataInitializer`, `IdempotencyCleanupScheduler`

---

### 16번
```
test: PaymentFacade 통합 테스트 - 3가지 핵심 시나리오 검증

- [멱등성] 동일 key 동시 2요청 → PaymentTransaction 1건, 잔액 1회 차감
- [원자성] 외부 승인 실패 → 계좌 미변경 + 멱등성 레코드 삭제 (재시도 허용)
- [이중청구 방지] 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지 (재시도 차단)
- @SpringBootTest + H2 인메모리 DB (MySQL 불필요)
- MockBean: RedissonClient, RedissonLockService(ReentrantLock 대체), ExternalBankService
```
**파일:** `PaymentFacadeIntegrationTest`

**체크:** `./gradlew test --tests PaymentFacadeIntegrationTest` 초록불

---

### 17번
```
docs/refactor: README 최종 보완 + 코드 정리

- 패키지 구조 명시 (domain/global/infrastructure)
- 실행/테스트/API/장애시나리오 최종 정리
- 불필요한 코드 및 주석 제거
- 전체 테스트 최종 통과 확인
```
**파일:** `README.md`, 전체

---

## 제출 전 최종 체크리스트

1. `./gradlew test` → 전체 테스트 통과 (3개 테스트 클래스)
2. `docker compose down` → `docker compose up --build`
3. 아래 로그 확인:
```
[초기화] 테스트 계좌 생성 완료
[테스트] POST http://localhost:8080/api/v1/payments
```
4. `http://localhost:8080/api/v1/health` → `OK` 확인
5. `src/test/http/payment-api-test.http` 파일로 실제 결제 API 호출 테스트

---

## 파일 빠르게 찾는 꿀팁

| 단축키 | 용도 |
|---|---|
| `Ctrl + Shift + F` | `[N번]` 으로 전체 검색 → 해당 커밋 코드 블록 일괄 검색 |
| `Ctrl + Shift + N` | 파일명으로 바로 이동 |
| `Ctrl + E` | 최근 열었던 파일 목록 |

---

## 시간 배분 참고

| 구간 | 예상 시간 |
|---|---|
| 1번 (README 요구사항 분석) | 15분 |
| 2~3번 (프로젝트 설정 + 예외처리) | 20분 |
| 4~7번 (도메인 엔티티 4개) | 50분 |
| 8번 (인프라) | 25분 |
| 9~10번 (PaymentService + 테스트) | 30분 |
| 11~13번 (Settlement + Calculator테스트 + Webhook) | 45분 |
| 14~16번 (Facade + API + 통합테스트) | 45분 |
| 17번 (README 보완 + 정리) | 15분 |
| **총합** | **약 3.7시간** |

1.3시간 여유 → 서버 실행 확인, 예상치 못한 오류 대응 가능.
