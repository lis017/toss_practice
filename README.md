# 한정판 캐시백 이벤트 결제 시스템

Spring Boot 3.2 / Java 17 / MySQL / Redis(Redisson)

---

## 목차

1. [요구사항 분석](#요구사항-분석)
2. [설계하면서 고민했던 것들](#설계하면서-고민했던-것들)
3. [실행](#실행)
4. [테스트 실행](#테스트-실행)
5. [API](#api)
6. [장애 시나리오 테스트](#장애-시나리오-테스트)
7. [API 실행 테스트 .http 파일](#api-실행-테스트-http-파일)
8. [헬스체크](#헬스체크)

---

## 요구사항 분석

### 핵심 기능 요구사항

| # | 요구사항 | 구현 위치 |
|---|---|---|
| F1 | 계좌 간 금액 이체 | `PaymentService.executeTransfer()` |
| F2 | 외부 은행 API 승인 | `MockExternalBankService.approve()` |
| F3 | 캐시백 10% 지급 (한정 예산) | `CashbackService.grantCashback()` |
| F4 | 중복 결제 방지 (멱등성 키) | `PaymentFacade.getOrCreateIdempotencyRecord()` |
| F5 | 결제 이력 저장 | `PaymentTransaction` 엔티티 |

### 비기능 요구사항 (안정성)

| # | 요구사항 | 구현 방법 |
|---|---|---|
| N1 | 동시 요청 시 캐시백 예산 초과 방지 | Redisson 분산 락 (`lock:cashback:budget`) |
| N2 | 다중 서버에서 계좌 잔액 정합성 | Redisson MultiLock (두 계좌 동시 보호) |
| N3 | 외부 API 실패 시 DB 미변경 보장 | 승인 선행 → 실패 시 원복할 것 없음 |
| N4 | 외부 API 반복 실패 시 스레드 고갈 방지 | Circuit Breaker (Resilience4j) |
| N5 | DB 커넥션 풀 고갈 방지 | Facade 패턴으로 트랜잭션 분리 |

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
[STEP 4 / TX3] 캐시백 지급 (Redisson 락으로 예산 동시성 보호)
  ↓
[STEP 5] 트랜잭션 PENDING_SETTLEMENT 상태 업데이트
  ↓
[멱등성 레코드] COMPLETED로 업데이트
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
PENDING
  ↓ (구매자 출금 완료 + 가상계좌 보관 중)
PENDING_SETTLEMENT
  ↓ (가맹점 정산 완료)
SUCCESS

PENDING → FAILED  (비즈니스 예외 - 잔액 부족 등)
```

---

## 설계하면서 고민했던 것들

### 외부 API 호출 중 DB 커넥션을 잡고 있으면 안 된다

처음에 `PaymentService` 하나에 `@Transactional`을 걸고 내부에서 외부 은행 API를 호출하는 구조를 떠올렸는데, 이렇게 하면 외부 API가 3초 응답할 동안 DB 커넥션을 계속 점유합니다. 커넥션 풀이 30개라고 하면 동시 요청 30건만 들어와도 나머지는 전부 대기합니다.

그래서 `PaymentFacade`를 별도 빈으로 분리해서 트랜잭션 없이 흐름만 조율하도록 했습니다. 외부 API 승인 → 이체(TX1) 커밋 → 커넥션 반환 → 캐시백(TX3) 순서로 각 단계가 독립적으로 동작합니다. 외부 승인을 먼저 받기 때문에 승인 실패 시 DB를 건드린 게 없어 원복이 필요 없습니다.

### PG 가상계좌 정산 구조 (토스페이먼츠 도메인 반영)

일반적인 P2P 송금은 A→B가 즉시 이뤄지지만, PG(Payment Gateway)는 다릅니다. 구매자 출금과 가맹점 입금 사이에 PG가 자금을 중간에 보관하고, 정산 주기(D+1, D+2 등)에 따라 가맹점에 입금합니다.

이 구조의 장점:
- 가맹점 은행 API가 일시 장애여도 구매자 결제는 정상 처리됨 (구매자 경험 보호)
- 정산 실패 시 가상계좌에 자금이 보관되어 있어 재시도가 안전함 (구매자 환불 불필요)
- PG의 실제 비즈니스 모델(정산 수수료, 정산 주기 제어)에 맞는 아키텍처

`SettlementScheduler`가 매일 새벽 2시에 PENDING 정산 건을 일괄 처리합니다. 실패 건은 다음 날 자동 재시도됩니다.

### 캐시백 예산 동시성은 Redisson 분산 락 하나로

DB 비관락, 낙관락 다 고려해봤는데 과제 범위에서는 Redisson 분산 락 하나면 충분하다고 판단했습니다.
낙관락은 충돌 시 재시도 로직을 직접 구현해야 하고, 비관락은 DB 커넥션을 락 대기 시간만큼 점유해서 고트래픽 상황에 불리합니다. Redisson은 Redis 레벨에서 대기하므로 DB 부하와 분리됩니다.
 분산 환경에서도 하나의 락 키(`lock:cashback:budget`)로 직렬화되기 때문에 예산 초과 지급이 원천 차단됩니다.

락 관련 코드가 서비스에 섞이면 지저분해서 `RedissonLockService`로 분리해 인프라 레이어에 뒀습니다.

### 계좌 이체도 다중 서버 환경을 고려했습니다

계좌 이체도 여러 서버 인스턴스가 동시에 같은 계좌를 수정하면 잔액이 꼬일 수 있습니다. `executeTransfer`(구매자→가상계좌)와 `settleOne`(가상계좌→가맹점) 모두 Redisson MultiLock으로 두 계좌를 동시에 보호합니다.

데드락 방지: 항상 낮은 락 키 순서(`Math.min/max`)로 획득하기 때문에 교착 상태가 발생하지 않습니다.

### 외부 API가 계속 실패하면 스레드와 커넥션이 고갈된다

외부 은행 서버가 다운된 상태에서 요청이 계속 들어오면 매번 타임아웃이 반복됩니다. 이걸 막으려고 Resilience4j Circuit Breaker를 붙였습니다. 5번 중 3번 이상 실패하면 30초간 요청 자체를 차단하고, 이후 자동으로 복구를 시도합니다.

### 네트워크 재시도로 인한 중복 결제 방지

클라이언트가 응답을 못 받고 재시도하면 같은 결제가 두 번 나갈 수 있습니다. `idempotencyKey`를 요청에 포함시켜서, 동일 키로 들어온 요청은 DB에 저장된 첫 번째 응답을 그대로 돌려줍니다. Redis가 아닌 DB에 저장한 이유는 Redis 장애 시에도 중복 결제 방지가 보장되어야 하기 때문입니다.
대신 DB 조회가 한 번 더 발생하지만, 결제 정합성이 캐시 가용성보다 우선이라 판단했습니다.

멱등성 키는 **처리 시작 전** `PROCESSING` 상태로 먼저 저장합니다. 결제 완료 후 `COMPLETED`로 업데이트합니다. 이 덕분에 응답 대기 중 동일 키로 동시 요청이 들어와도 DB의 unique constraint가 두 번째 요청을 막습니다.

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
[초기화] 캐시백 예산 생성 - 총 예산: 100000000원
[테스트] POST http://localhost:8080/api/v1/payments
```

---

## 테스트 실행

서버 없이 독립 실행 가능합니다 (H2 인메모리 DB 사용):

```bash
./gradlew test
```


| 테스트                       | 내용                             |
| ------------------------- | ------------------------------ |
| `PaymentServiceTest`      | 이체 성공/실패 단위 테스트 |
| `CashbackConcurrencyTest` | 100명 동시 요청 시 예산 초과 없음 검증       |
| `PaymentControllerTest`   | API 요청/응답 형식, 멱등성, 에러 코드 검증    |


> **참고:** `CashbackConcurrencyTest`는 실제 Redis가 필요합니다. `docker compose up` 실행 후 테스트하면 전체 통과합니다. Docker 없이 실행 시 해당 테스트 2건만 실패하며 나머지 20건은 정상입니다.

---

## API

### 결제 요청

```
POST http://localhost:8080/api/v1/payments
```

```json
{
  "idempotencyKey": "클라이언트가-생성한-UUID",
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
  "cashbackAmount": 1000,
  "message": "결제 성공! 1,000포인트 캐시백이 적립되었습니다."
}
```

캐시백 예산 소진 시에도 결제 자체는 성공하고 `cashbackAmount: 0`으로 반환합니다.

에러 응답:

```json
{
  "code": "A002",
  "message": "잔액이 부족합니다",
  "path": "/api/v1/payments"
}
```

Validation 실패 시 어떤 필드가 왜 실패했는지 상세하게 반환합니다:

```json
{
  "code": "V001",
  "message": "입력값이 유효하지 않습니다",
  "fields": [
    { "field": "idempotencyKey", "message": "멱등성 키는 필수입니다" }
  ]
}
```

주요 에러 코드:


| 코드    | 상황                                |
| ----- | --------------------------------- |
| V001  | 입력값 유효성 실패                        |
| A002  | 잔액 부족                             |
| A003  | 동일 계좌 이체                          |
| C001  | 캐시백 예산 소진 (결제는 성공)                |
| CB001 | 서킷 브레이커 오픈 (외부 은행 반복 실패로 차단)      |
| E001  | 외부 은행 타임아웃                        |
| P001  | 동일 idempotencyKey 처리 중 (중복 요청 차단) |


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

### 캐시백 예산 소진 테스트

```yaml
cashback:
  budget:
    total: 100
```

예산 100원으로 줄이면 10원짜리 캐시백 10번 이후 소진됩니다.

---

## API 실행 테스트 (.http 파일)

`src/test/http/payment-api-test.http` 파일을 IntelliJ에서 열면 각 요청 왼쪽에 **▶ 버튼**이 생깁니다.
curl 없이 클릭 한 번으로 실행 가능하며, Windows/Linux/VDI 환경 모두 동일하게 동작합니다.


| 번호  | 케이스                                      |
| --- | ---------------------------------------- |
| [1] | 헬스체크                                     |
| [2] | 정상 결제 (캐시백 10% 적립 확인)                    |
| [3] | 멱등성 — 같은 `idempotencyKey` 재요청 시 동일 응답 반환 |
| [4] | 독립된 두 번째 거래                              |
| [5] | 잔액 부족 오류                                 |
| [6] | 음수 금액 입력값 검증 오류                          |
| [7] | 필수 필드 누락 오류                              |


---

## 헬스체크

```
GET http://localhost:8080/actuator/health
```

---

