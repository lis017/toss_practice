# PG 결제 정산 시스템

Spring Boot 3.2 / Java 17 / MySQL / Redis(Redisson)

---

## 목차

1. [패키지 구조](#패키지-구조)
2. [요구사항 분석](#요구사항-분석)
3. [설계하면서 고민했던 것들](#설계하면서-고민했던-것들)
4. [정산 계산 규칙](#정산-계산-규칙)
5. [웹훅 구조](#웹훅-구조)
6. [실행](#실행)
7. [테스트 실행](#테스트-실행)
8. [API](#api)
9. [장애 시나리오 테스트](#장애-시나리오-테스트)
10. [헬스체크](#헬스체크)

---

## 패키지 구조

```
com.toss.cashback
├── CashbackSystemApplication.java
├── domain/
│   ├── account/                         # 계좌 도메인
│   │   ├── entity/Account.java
│   │   └── repository/AccountRepository.java
│   ├── payment/                         # 결제 도메인
│   │   ├── controller/PaymentController.java
│   │   ├── dto/
│   │   │   ├── request/PaymentRequest.java
│   │   │   └── response/PaymentResponse.java
│   │   ├── entity/
│   │   │   ├── PaymentTransaction.java  # 결제 이력 (PENDING → PENDING_SETTLEMENT → SUCCESS)
│   │   │   ├── PaymentStatus.java
│   │   │   ├── PaymentIdempotency.java  # 멱등성 레코드 (PROCESSING → COMPLETED)
│   │   │   └── IdempotencyStatus.java
│   │   ├── repository/
│   │   │   ├── PaymentTransactionRepository.java
│   │   │   └── PaymentIdempotencyRepository.java
│   │   ├── scheduler/
│   │   │   └── IdempotencyCleanupScheduler.java  # 매일 03시 만료 레코드 삭제
│   │   └── service/
│   │       ├── PaymentFacade.java       # 결제 흐름 조율 (멱등성 + 단계별 트랜잭션)
│   │       └── PaymentService.java      # 계좌 이체 (MultiLock + TransactionTemplate)
│   ├── settlement/                      # 정산 도메인
│   │   ├── entity/
│   │   │   ├── MerchantSettlementPolicy.java  # 가맹점별 수수료/VAT/정산주기 정책
│   │   │   ├── SettlementRecord.java          # 정산 레코드 (PENDING → SETTLED)
│   │   │   ├── SettlementStatus.java
│   │   │   └── SettlementAmountResult.java    # 계산 결과 Value Object
│   │   ├── repository/
│   │   │   ├── MerchantSettlementPolicyRepository.java
│   │   │   └── SettlementRepository.java
│   │   ├── scheduler/
│   │   │   └── SettlementScheduler.java  # 매일 02시 PENDING 정산 건 일괄 처리
│   │   └── service/
│   │       ├── SettlementCalculator.java  # 수수료/VAT/실지급액 계산 (HALF_EVEN)
│   │       └── SettlementService.java     # 정산 레코드 생성 + 일괄 정산 처리
│   └── webhook/                         # 웹훅 도메인
│       ├── entity/
│       │   ├── WebhookDelivery.java      # 발송 이력 (PENDING → SUCCESS/FAILED)
│       │   ├── WebhookDeliveryStatus.java
│       │   └── WebhookEventType.java
│       ├── repository/
│       │   └── WebhookDeliveryRepository.java
│       └── service/
│           └── WebhookService.java       # 발송 + 1회 재시도
├── global/
│   ├── config/
│   │   ├── DataInitializer.java     # 서버 시작 시 테스트 계좌 + 정산 정책 초기화
│   │   └── RedissonConfig.java
│   └── error/
│       ├── CustomException.java
│       ├── ErrorCode.java
│       ├── ErrorResponse.java
│       └── GlobalExceptionHandler.java
└── infrastructure/
    ├── api/
    │   ├── ExternalBankService.java      # 외부 은행 API 인터페이스
    │   └── MockExternalBankService.java  # Mock + Circuit Breaker (Resilience4j)
    ├── redis/
    │   └── RedissonLockService.java      # 분산 락 (단일 키 / MultiLock)
    └── webhook/
        ├── WebhookClient.java            # 웹훅 발송 인터페이스
        └── MockWebhookClient.java        # Mock 발송 구현체
```

---

## 요구사항 분석

### 핵심 기능 요구사항

| # | 요구사항 | 구현 위치 |
|---|---|---|
| F1 | 계좌 간 금액 이체 | `PaymentService.executeTransfer()` |
| F2 | 외부 은행 API 승인 | `MockExternalBankService.approve()` |
| F3 | 중복 결제 방지 (멱등성 키) | `PaymentFacade.getOrCreateIdempotencyRecord()` |
| F4 | 결제 이력 저장 | `PaymentTransaction` 엔티티 |
| F5 | 가맹점별 정산 수수료/VAT/주기 관리 | `MerchantSettlementPolicy`, `SettlementCalculator` |
| F6 | 결제 완료 웹훅 발송 | `WebhookService`, `MockWebhookClient` |

### 비기능 요구사항 (안정성)

| # | 요구사항 | 구현 방법 |
|---|---|---|
| N1 | 다중 서버에서 계좌 잔액 정합성 | Redisson MultiLock (두 계좌 동시 보호) |
| N2 | 외부 API 실패 시 DB 미변경 보장 | 승인 선행 → 실패 시 원복할 것 없음 |
| N3 | 외부 API 반복 실패 시 스레드 고갈 방지 | Circuit Breaker (Resilience4j) |
| N4 | DB 커넥션 풀 고갈 방지 | Facade 패턴으로 트랜잭션 분리 (외부 API 호출 중 커넥션 미점유) |

### 결제 처리 흐름 (C안 - PG 가상계좌 정산 구조)

```
━━━━━━━━━━━━━━━━━━ 1단계: 즉시 처리 (구매자 출금) ━━━━━━━━━━━━━━━━━━
클라이언트 요청
  ↓
[멱등성 체크] PROCESSING 선등록 (unique constraint로 동시 중복 차단)
  ↓
[STEP 1] 외부 은행 API 승인 (구매자 출금 승인 - Circuit Breaker 보호)
  ├─ 실패 → DB 미변경 → 멱등성 레코드 삭제 → 오류 반환 (재시도 가능)
  └─ 성공 ↓
[STEP 2 / TX1] 구매자(A) → 가상계좌(C) 이체 + 커밋 (DB 커넥션 반환)
  ↓
[STEP 3] 정산 레코드 생성 (PENDING 상태로 DB 등록)
  ↓
[STEP 4] 트랜잭션 PENDING_SETTLEMENT 상태 업데이트
  ↓
[멱등성 레코드] COMPLETED로 업데이트
  ↓
[웹훅] 가맹점에 PAYMENT_COMPLETED 이벤트 발송 (실패해도 결제 응답에 영향 없음)
  ↓
구매자에게 성공 응답 반환 ← 구매자 입장에서는 여기서 결제 완료

━━━━━━━━━━━━━━━━━━ 2단계: 정산 (매일 새벽 2시) ━━━━━━━━━━━━━━━━━━
[SettlementScheduler] PENDING 정산 건 일괄 조회
  ↓
[STEP A] 외부 은행 API 승인 (가맹점 입금 승인 - Circuit Breaker 보호)
  ├─ 실패 → PENDING 유지 (실패 사유 기록) → 다음 날 자동 재시도
  └─ 성공 ↓
[STEP B / TX] 가상계좌(C) → 가맹점(B) 이체 + SettlementRecord SETTLED
  ↓
PaymentTransaction SUCCESS 상태 업데이트
```

### 트랜잭션 상태 전이

```
PaymentTransaction:
  PENDING
    ↓ (구매자 출금 완료 + 가상계좌 보관 중)
  PENDING_SETTLEMENT
    ↓ (가맹점 정산 완료)
  SUCCESS

  PENDING → FAILED              (비즈니스 예외 - 잔액 부족 등)
  PENDING → POST_PROCESS_FAILED (외부 승인 성공 후 내부 후처리 실패 → 운영팀 수동 복구 필요)

SettlementRecord:
  PENDING → SETTLED  (정산 완료)
  PENDING → PENDING  (외부 API 실패 → 다음 정산 주기 재시도)
```

---

## 정산 계산 규칙

### 가맹점별 정산 정책 (MerchantSettlementPolicy)

가맹점마다 협의된 수수료율·VAT 여부·정산 주기가 다를 수 있습니다.

| 항목 | 설명 | 예시 |
|------|------|------|
| `feeRate` | 수수료율 | `0.0350` = 3.5% |
| `vatIncluded` | 수수료에 부가세(10%) 별도 부과 여부 | `true` = 수수료의 10% 추가 |
| `settlementCycleDays` | 정산 주기 | `1` = D+1, `2` = D+2 |

정책 미등록 가맹점은 기본 정책(수수료 3.5% / VAT 포함 / D+1)이 자동 적용됩니다.

### 금액 계산 공식 (SettlementCalculator)

```
feeAmount  = grossAmount × feeRate          (HALF_EVEN 반올림)
vatAmount  = feeAmount × 0.10              (vatIncluded=true 일 때만)
netAmount  = grossAmount - feeAmount - vatAmount
```

**HALF_EVEN(은행가 반올림) 선택 이유:**
- 소수점 정확히 0.5인 경계에서 "가까운 짝수" 방향으로 반올림
- 대량 정산 건 처리 시 반올림 오차가 통계적으로 상쇄됨 (금융권 표준)
- HALF_UP은 항상 올림 → PG 또는 가맹점이 지속적으로 손해

### 정산 정책 스냅샷 (SettlementRecord)

`SettlementRecord`에 계산 결과(금액)뿐 아니라 **당시 적용된 정책 값(`policyFeeRate`, `policyVatIncluded`)도 함께 저장**합니다.

이후 가맹점 수수료율이 변경되더라도, 해당 정산 건이 어떤 조건으로 계산됐는지 DB에서 직접 역추적할 수 있습니다. ([토스페이먼츠 정산 개편기 - 설정 정보 스냅샷 패턴](https://toss.tech/article/payments-legacy-6) 참고)

### 계산 예시 (100,000원 결제, 기본 정책)

```
grossAmount  =  100,000원  (구매자가 낸 금액)
feeAmount    =    3,500원  (100,000 × 3.5%)
vatAmount    =      350원  (3,500 × 10%)
netAmount    =   96,150원  (가맹점 실수령액)
PG 수익      =    3,850원  (feeAmount + vatAmount, 가상계좌 잔류)
```

### 정산 흐름에서의 금액 이동

```
[결제 시점] 구매자 출금 → grossAmount(100,000원) 전액 가상계좌에 보관

[정산 시점] 가상계좌 → netAmount(96,150원)만 가맹점에 입금
            나머지 3,850원(fee+vat)은 가상계좌 잔류 = PG 수익
```

---

## 설계하면서 고민했던 것들

### 외부 API 호출 중 DB 커넥션을 잡고 있으면 안 된다

처음에 `PaymentService` 하나에 `@Transactional`을 걸고 내부에서 외부 은행 API를 호출하는 구조를 떠올렸는데, 이렇게 하면 외부 API가 3초 응답할 동안 DB 커넥션을 계속 점유합니다. 커넥션 풀이 30개라고 하면 동시 요청 30건만 들어와도 나머지는 전부 대기합니다.

그래서 `PaymentFacade`를 별도 빈으로 분리해서 트랜잭션 없이 흐름만 조율하도록 했습니다. 외부 API 승인 → 이체(TX1) 커밋 → 커넥션 반환 순서로 각 단계가 독립적으로 동작합니다. 외부 승인을 먼저 받기 때문에 승인 실패 시 DB를 건드린 게 없어 원복이 필요 없습니다.

### PG 가상계좌 정산 구조 (토스페이먼츠 도메인 반영)

일반적인 P2P 송금은 A→B가 즉시 이뤄지지만, PG(Payment Gateway)는 다릅니다. 구매자 출금과 가맹점 입금 사이에 PG가 자금을 중간에 보관하고, 정산 주기(D+1, D+2 등)에 따라 가맹점에 입금합니다.

이 구조의 장점:
- 가맹점 은행 API가 일시 장애여도 구매자 결제는 정상 처리됨 (구매자 경험 보호)
- 정산 실패 시 가상계좌에 자금이 보관되어 있어 재시도가 안전함 (구매자 환불 불필요)
- PG의 실제 비즈니스 모델(정산 수수료, 정산 주기 제어)에 맞는 아키텍처

`SettlementScheduler`가 매일 새벽 2시에 PENDING 정산 건을 일괄 처리합니다. 실패 건은 다음 날 자동 재시도됩니다.

### 계좌 이체 동시성은 Redisson MultiLock으로

DB 비관락, 낙관락 다 고려해봤는데 Redisson MultiLock이 더 적합하다고 판단했습니다.
낙관락은 충돌 시 재시도 로직을 직접 구현해야 하고, 비관락은 DB 커넥션을 락 대기 시간만큼 점유해서 고트래픽 상황에 불리합니다. Redisson은 Redis 레벨에서 대기하므로 DB 부하와 분리됩니다.

계좌 이체와 정산 이체 모두 두 계좌를 동시에 락해야 하므로 MultiLock을 사용했습니다. 데드락 방지는 항상 낮은 ID 순서(`Math.min/max`)로 락을 획득해서 해결했습니다.

락 코드가 서비스에 섞이면 지저분해서 `RedissonLockService`로 분리해 인프라 레이어에 뒀습니다.

### 네트워크 재시도로 인한 중복 결제 방지

클라이언트가 응답을 못 받고 재시도하면 같은 결제가 두 번 나갈 수 있습니다. `idempotencyKey`를 요청에 포함시켜서, 동일 키로 들어온 요청은 DB에 저장된 첫 번째 응답을 그대로 돌려줍니다. Redis가 아닌 DB에 저장한 이유는 Redis 장애 시에도 중복 결제 방지가 보장되어야 하기 때문입니다.

멱등성 키는 **처리 시작 전** `PROCESSING` 상태로 먼저 저장합니다. 결제 완료 후 `COMPLETED`로 업데이트합니다. 이 덕분에 응답 대기 중 동일 키로 동시 요청이 들어와도 DB의 unique constraint가 두 번째 요청을 막습니다.

### 외부 API가 계속 실패하면 스레드와 커넥션이 고갈된다

외부 은행 서버가 다운된 상태에서 요청이 계속 들어오면 매번 타임아웃이 반복됩니다. 이걸 막으려고 Resilience4j Circuit Breaker를 붙였습니다. 최근 5건 중 3건(60%) 이상 실패하면 30초간 요청 자체를 차단하고, 이후 자동으로 복구를 시도합니다.

---

## 웹훅 구조

결제 완료(`PENDING_SETTLEMENT`) 시 가맹점에게 `PAYMENT_COMPLETED` 이벤트를 발송합니다.

### 발송 흐름

```
PaymentFacade.processPayment()
  └── WebhookService.sendPaymentCompleted()
        ├── MerchantSettlementPolicy에서 가맹점 webhookUrl 조회
        ├── WebhookDelivery 레코드 생성 (PENDING)
        ├── WebhookClient.send() → 성공 시 SUCCESS 저장
        └── 실패 시 1회 재시도 → 성공 SUCCESS / 실패 FAILED 저장
```

### 페이로드 예시

```json
{ "eventType": "PAYMENT_COMPLETED", "transactionId": 1, "amount": 100000 }
```

### 발송 실패 대처

- `webhook_deliveries` 테이블에서 `status = FAILED` 건 조회 후 수동 재발송
- `application.yml`의 `webhook.simulate-error: true`로 실패 시나리오 재현 가능

---

## 실행

Docker Desktop이 실행 중인 상태에서:

```bash
docker compose up --build
```

MySQL → Redis 헬스체크 통과 후 앱이 자동으로 뜹니다 (최초 실행 시 약 40초 소요).

코드 변경 없이 재시작만 할 때는 `--build` 생략 가능합니다.

앱 뜨면 로그에 아래가 찍힙니다:

```
[초기화] 테스트 계좌 생성 완료
[테스트] POST http://localhost:8080/api/v1/payments
```

---

## 테스트 실행

서버 없이 독립 실행 가능합니다 (H2 인메모리 DB 사용):

```bash
./gradlew test
```

| 테스트 | 내용 |
|---|---|
| `PaymentServiceTest` | 이체 성공/실패 단위 테스트 (Mockito, Spring Context 없이 실행) |
| `SettlementCalculatorTest` | 정산 금액 계산 단위 테스트 (HALF_EVEN 반올림 포함) |
| `PaymentFacadeIntegrationTest` | 멱등성/원자성/이중청구방지 통합 테스트 (H2 + Mock) |

---

## API

### 결제 요청

```
POST http://localhost:8080/api/v1/payments
```

```json
{
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 10000
}
```

성공 응답:

```json
{
  "transactionId": 1,
  "status": "SUCCESS",
  "amount": 10000,
  "message": "결제 성공! 정산은 D+1 ~ D+2 내에 가맹점으로 입금됩니다."
}
```

에러 응답:

```json
{
  "timestamp": "2026-04-06T10:00:00",
  "code": "A002",
  "message": "잔액이 부족합니다",
  "path": "/api/v1/payments"
}
```

Validation 실패 시 필드별 상세 오류 반환:

```json
{
  "code": "V001",
  "message": "입력값이 유효하지 않습니다",
  "fields": [
    { "field": "idempotencyKey", "message": "멱등성 키는 UUID 형식이어야 합니다" },
    { "field": "amount", "message": "결제 금액은 0보다 커야 합니다" }
  ]
}
```

주요 에러 코드:

| 코드 | 상황 |
|---|---|
| V001 | 입력값 유효성 실패 |
| A002 | 잔액 부족 |
| A003 | 동일 계좌 이체 |
| C001 | 분산 락 획득 실패 (요청 과부하 시) |
| CB001 | 서킷 브레이커 오픈 (외부 은행 반복 실패로 차단) |
| E001 | 외부 은행 타임아웃 |
| E002 | 외부 은행 API 오류 |
| P001 | 동일 idempotencyKey 처리 중 (중복 요청 차단) |

---

## 장애 시나리오 테스트

`application.yml`에서 플래그 하나만 바꿔서 각 상황을 재현할 수 있습니다.

### 외부 API 타임아웃 → 즉시 실패 반환 (계좌 미변경)

```yaml
cashback:
  external-bank:
    simulate-delay: true
```

결제 요청을 보내면 5초 지연 → 3초 타임아웃 초과 → 승인 실패 → DB 미변경 상태로 오류 반환됩니다.
계좌를 건드리기 전에 승인이 실패하므로 원복 로직이 필요 없습니다.

로그에서 확인:

```
[STEP 1 실패] 외부 은행 승인 실패 - ...
```

DB에서 확인:

```sql
SELECT balance FROM accounts WHERE id=1;  -- 차감 없이 원래 잔액 유지
```

### 서킷 브레이커 동작

`simulate-delay: true` 상태에서 결제를 5번 연속 실패시키면 서킷이 오픈됩니다.

이후 요청부터는 타임아웃 대기 없이 즉시 CB001 에러가 반환됩니다:

```
[서킷 브레이커] OPEN 상태 - 즉시 차단, accountId=...
```

### 웹훅 실패 시뮬레이션

```yaml
webhook:
  simulate-error: true
```

결제 완료 후 웹훅 발송 1차 실패 → 1회 재시도 → 최종 FAILED 기록됩니다.
결제 자체는 정상 응답이 반환됩니다. (`webhook_deliveries` 테이블에서 FAILED 건 확인 가능)

---

## 헬스체크

```
GET http://localhost:8080/api/v1/health
GET http://localhost:8080/actuator/health
```
