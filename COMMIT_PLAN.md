# 커밋 순서 계획 (5시간 과제용)

> **코드 내 `[N번]` 주석 검색 꿀팁:** Ctrl+Shift+F → `[N번]` 입력
> README 커밋이 1번으로 추가되면서 **코드 주석 [N번] = 커밋 (N+1)번**입니다.
> 예) 코드에 `[2번]` → 커밋 3번, 코드에 `[10번]` → 커밋 11번

---

## 커밋 메시지 템플릿

### 1번
```
docs: README 초기 작성 - 요구사항 분석 및 설계 계획

- 목차 및 전체 구조 정의
- 핵심/비기능 요구사항 분석표 작성
- 결제 처리 흐름도 및 트랜잭션 상태 전이 정의
- 설계 결정 이유 선작성 (트랜잭션 분리, 분산 락, 서킷 브레이커, 멱등성, 운영 알림)
```
**파일:** `README.md`

**의도:** 코딩 시작 전 요구사항을 정확히 이해하고 전체 구조를 확정한 뒤 구현 시작

---

### 2번
```
chore: 프로젝트 초기 설정

- Spring Boot 3.2 / Java 17 기반 멀티 모듈 없는 단일 프로젝트 구성
- Docker Compose로 MySQL + Redis + 앱 단일 명령 실행 환경 구성
- 테스트용 H2 인메모리 DB 분리 (application-test.yml)
```
**파일:** `build.gradle`, `Dockerfile`, `docker-compose.yml`, `application.yml`, `application-test.yml`, `gradle.properties`

**체크:** `./gradlew build` 에러 없음

---

### 3번
```
feat: 도메인 엔티티 및 레포지토리 구현

- Account: 출금/입금/포인트 적립 비즈니스 로직을 엔티티 내부에 배치 (도메인 모델 패턴)
- CashbackBudget: 예산 초과 여부 검증 로직 포함 (use/canGrant)
- PaymentTransaction: PENDING → PENDING_SETTLEMENT(가상계좌 보관) → SUCCESS(정산 완료) 상태 흐름
  - virtualAccountId 필드 추가: 어느 가상계좌에 자금이 보관 중인지 추적
- SettlementRecord: PG 정산 레코드. PENDING → SETTLED 상태 관리
  - 정산 실패 시 PENDING 유지 + failureReason 기록 → 다음 정산 주기 자동 재시도
- SettlementStatus enum: PENDING(정산 대기) / SETTLED(정산 완료)
- PaymentIdempotency: PROCESSING/COMPLETED 상태 관리 + 멱등성 선등록 패턴
- IdempotencyStatus enum: PROCESSING(처리 중) / COMPLETED(완료) 상태 구분
```
**파일:** `Account`, `CashbackBudget`, `PaymentTransaction`, `SettlementRecord`, `SettlementStatus`, `PaymentIdempotency`, `IdempotencyStatus`, `PaymentStatus` + Repository 5개

---

### 4번
```
feat: 공통 예외 처리 구현

- ErrorCode enum으로 HTTP 상태 + 에러 코드 + 메시지 일괄 관리
- GlobalExceptionHandler: CustomException, Validation 에러 응답 형식 통일
- Validation 실패 시 어떤 필드가 왜 실패했는지 fields 배열로 상세 반환
- P001: 동일 idempotencyKey 처리 중 차단 (멱등성 선등록으로 동시 중복 요청 차단)
```
**파일:** `ErrorCode`, `CustomException`, `ErrorResponse`, `GlobalExceptionHandler`

---

### 5번
```
feat: 계좌 이체 서비스 구현 (다중 서버 안전)

- executeTransfer: 구매자(A) → 가상계좌(C) 이체. PaymentTransaction에는 최종 수신처(B) 기록
  - 파라미터: fromAccountId, merchantAccountId, virtualAccountId, amount (C안 구조 반영)
- markTransactionPendingSettlement(): 1단계 완료 시 PENDING_SETTLEMENT 상태 + cashbackAmount 기록
- markTransactionSuccess(): 정산 완료 후 SUCCESS 상태 변경 (cashbackAmount는 1단계에서 이미 기록)
- Redisson MultiLock으로 구매자+가상계좌 동시 보호 (데드락 방지: Math.min/max 정렬)
```
**파일:** `PaymentService`

---

### 6번
```
test: 계좌 이체 서비스 단위 테스트

- 이체 성공 / 잔액 부족 / 동일 계좌 이체 거부 케이스
- Spring Context 없이 Mockito 단위 테스트 (빠른 실행)
```
**파일:** `PaymentServiceTest`

**체크:** `./gradlew test --tests PaymentServiceTest` 초록불

---

### 7번
```
feat: Redisson 분산 락 인프라 구현

- executeWithLock: 단일 키 락 (캐시백 예산)
- executeWithMultiLock: 다중 키 락 + 사전순 정렬 (계좌 이체 데드락 방지)
- 락 획득 실패/서버 다운 시 자동 해제(leaseTime)로 락 영구 점유 방지
```
**파일:** `RedissonConfig`, `RedissonLockService`

---

### 8번
```
feat: 외부 은행 API 및 서킷 브레이커 구현

- ExternalBankService 인터페이스로 실제 구현체와 Mock 교체 가능하도록 설계
- MockExternalBankService: simulate-delay/error 플래그로 장애 시나리오 재현
- Resilience4j Circuit Breaker: 5회 중 3회 이상 실패 시 30초 차단 → 반복 타임아웃 방지
```
**파일:** `ExternalBankService`, `MockExternalBankService`, `application.yml` resilience4j 설정

---

### 9번
```
feat: 캐시백 지급 서비스 구현 (동시성 제어)

- Redisson 분산 락 내에서 TransactionTemplate으로 커밋 보장
- 락 해제 전 DB 커밋 완료 → 다음 스레드가 stale data 읽는 race condition 방지
- 예산 초과 시 예외가 아닌 0 반환 → 결제 자체는 성공으로 처리
```
**파일:** `CashbackService`

---

### 10번
```
test: 캐시백 동시성 통합 테스트

- 100명 동시 요청 시 예산 초과 지급 없음 검증 (CountDownLatch 활용)
- 예산 소진 시나리오: 요청 100건 중 일부만 지급되고 DB usedBudget과 합계 일치 검증
- RedissonLockService를 ReentrantLock Mock으로 대체 → Docker 없이 실행 가능
```
**파일:** `CashbackConcurrencyTest`

**체크:** `./gradlew test --tests CashbackConcurrencyTest` 초록불

---

### 11번
```
feat: 결제 Facade 구현 (C안 - PG 가상계좌 정산 구조)

- 구매자 출금 승인 → 구매자(A)→가상계좌(C) 이체 → 정산 레코드 생성(PENDING) → 캐시백
- 구매자는 1단계 완료 즉시 성공 응답 수신 (가맹점 정산은 비동기 처리)
- @PostConstruct로 가상계좌 ID 캐싱 → 매 요청마다 DB 조회 방지
- 멱등성 선등록: PROCESSING 선등록 → COMPLETED 업데이트 (unique constraint로 동시 중복 차단)
- SettlementService.createSettlementRecord(): 정산 레코드 PENDING 등록
- SettlementService.processAllPendingSettlements(): 매일 새벽 2시 일괄 정산
  - 단건 독립 처리: 한 건 실패 → 해당 건만 PENDING 유지, 나머지 정상 처리
  - 정산 흐름: 외부 API(가맹점 입금 승인) → 가상계좌→가맹점 이체 → SETTLED
- SettlementScheduler: @Scheduled(cron="0 0 2 * * *") 일일 정산 실행
- IdempotencyCleanupScheduler: 매일 새벽 3시 만료(24h) 레코드 일괄 삭제
```
**파일:** `PaymentFacade`, `SettlementService`, `SettlementScheduler`, `IdempotencyCleanupScheduler`, `CashbackSystemApplication`

---

### 12번
```
feat: 결제 API 엔드포인트 및 초기 데이터 구현

- POST /api/v1/payments: @Valid 입력 검증 후 PaymentFacade 위임 (Thin Controller)
- DataInitializer: 서버 시작 시 테스트 계좌 3개(구매자/가맹점/가상계좌) + 캐시백 예산 자동 생성
  - 가상계좌(TOSS-VIRTUAL-001): PG 중간 보관용, 구매자→가상계좌→가맹점 정산 흐름 지원
- PaymentRequest에 idempotencyKey 필드 추가 (@NotBlank 검증)
```
**파일:** `PaymentController`, `PaymentRequest`, `PaymentResponse`, `DataInitializer`

---

### 13번
```
test: 결제 API 컨트롤러 테스트

- 정상 결제 / 잔액 부족 / 유효성 검증 실패 응답 형식 검증
- 동일 idempotencyKey 재요청 시 같은 응답 반환 (중복 처리 방지) 검증
- @WebMvcTest로 컨트롤러 레이어만 테스트 (빠른 실행)
```
**파일:** `PaymentControllerTest`

**체크:** `./gradlew test --tests PaymentControllerTest` 초록불

---

### 14번
```
docs: README 최종 보완

- 실행 방법, API 명세 (요청/응답/에러코드), 장애 시나리오 테스트 방법 최종 정리
- 에러 코드표 완성 (P001 중복 요청 차단 포함)
- .http 파일 사용법, 헬스체크 안내 추가
```
**파일:** `README.md`

---

### 15번
```
refactor: 코드 정리 및 최종 점검

- 주석 보완 및 불필요한 코드 제거
- 전체 테스트 최종 통과 확인
```
**파일:** 전체

---

### (16번) 제출 전 최종 체크리스트
1. `./gradlew test` → 전체 테스트 통과
2. `docker compose down` → `docker compose up --build`
3. 아래 로그 확인:
```
[초기화] 캐시백 예산 생성 - 총 예산: 100000000원
[테스트] POST http://localhost:8080/api/v1/payments
```
4. 브라우저에서 `http://localhost:8080/api/v1/health` → `OK` 확인

---

## 파일 빠르게 찾는 꿀팁

| 단축키 | 용도 |
|---|---|
| `Ctrl + Shift + F` | `[N번]` 으로 전체 검색 → 해당 커밋 코드 블록 일괄 검색 (코드 [N번] = 커밋 N+1번) |
| `Ctrl + Shift + N` | 파일명으로 바로 이동 |
| `Ctrl + E` | 최근 열었던 파일 목록 |

---

## 시간 배분 참고

| 구간 | 예상 시간 |
|---|---|
| 1번 (README 요구사항 분석) | 15분 |
| 2~4번 (기반 설정 + 엔티티 + 예외처리) | 40분 |
| 5~10번 (핵심 로직 + 테스트) | 90분 |
| 11~13번 (Facade + API + 테스트) | 50분 |
| 14~15번 (README 보완 + 정리) | 25분 |
| **총합** | **약 3.5시간** |

1.5시간 여유 → 서버 실행 확인, 예상치 못한 오류 대응 가능.
