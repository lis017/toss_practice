# 커밋 순서 계획 (5시간 과제용)

> 코드 내 `// ======= [N번]` 주석을 Ctrl+Shift+F 로 검색하면 해당 번호 코드 블록을 빠르게 찾을 수 있습니다.

---

## 커밋 메시지 템플릿

### 1번
```
chore: 프로젝트 초기 설정

- Spring Boot 3.2 / Java 17 기반 멀티 모듈 없는 단일 프로젝트 구성
- Docker Compose로 MySQL + Redis + 앱 단일 명령 실행 환경 구성
- 테스트용 H2 인메모리 DB 분리 (application-test.yml)
```
**파일:** `build.gradle`, `Dockerfile`, `docker-compose.yml`, `application.yml`, `application-test.yml`, `gradle.properties`

**체크:** `./gradlew build` 에러 없음

---

### 2번
```
feat: 도메인 엔티티 및 레포지토리 구현

- Account: 출금/입금/포인트 적립 비즈니스 로직을 엔티티 내부에 배치 (도메인 모델 패턴)
- CashbackBudget: 예산 초과 여부 검증 로직 포함 (use/canGrant)
- PaymentTransaction: PENDING → SUCCESS/COMPENSATED 상태 흐름 관리
- PaymentIdempotency: 멱등성 키 + 만료 시각(expiresAt) 24시간 TTL
```
**파일:** `Account`, `CashbackBudget`, `PaymentTransaction`, `PaymentIdempotency` + Repository 4개

**체크:** `./gradlew build` 컴파일 통과

---

### 3번
```
feat: 공통 예외 처리 구현

- ErrorCode enum으로 HTTP 상태 + 에러 코드 + 메시지 일괄 관리
- GlobalExceptionHandler: CustomException, Validation 에러 응답 형식 통일
- Validation 실패 시 어떤 필드가 왜 실패했는지 fields 배열로 상세 반환
```
**파일:** `ErrorCode`, `CustomException`, `ErrorResponse`, `GlobalExceptionHandler`

**체크:** `./gradlew build` 컴파일 통과

---

### 4번
```
feat: 계좌 이체 서비스 구현 (다중 서버 안전)

- TX1(이체), TX2(보상), TX4(상태 업데이트) 3개 독립 트랜잭션으로 분리
- Redisson MultiLock으로 송금/수신 계좌 동시 보호 (다중 서버 환경 대응)
- 데드락 방지: 항상 낮은 락 키 순서로 획득 (lock:account:{낮은ID} → lock:account:{높은ID})
- 락 내부 TransactionTemplate으로 커밋 완료 후 락 해제 보장
```
**파일:** `PaymentService`

**체크:** `./gradlew build` 통과

---

### 5번
```
test: 계좌 이체 서비스 단위 테스트

- 이체 성공 / 잔액 부족 / 동일 계좌 이체 거부 케이스
- 외부 API 실패 시 보상 트랜잭션으로 계좌 잔액 원복 검증
- Spring Context 없이 Mockito 단위 테스트 (빠른 실행)
```
**파일:** `PaymentServiceTest`

**체크:** `./gradlew test --tests PaymentServiceTest` 초록불

---

### 6번
```
feat: Redisson 분산 락 인프라 구현

- executeWithLock: 단일 키 락 (캐시백 예산)
- executeWithMultiLock: 다중 키 락 + 사전순 정렬 (계좌 이체 데드락 방지)
- 락 획득 실패/서버 다운 시 자동 해제(leaseTime)로 락 영구 점유 방지
```
**파일:** `RedissonConfig`, `RedissonLockService`

**체크:** `./gradlew build` 통과

---

### 7번
```
feat: 외부 은행 API 및 서킷 브레이커 구현

- ExternalBankService 인터페이스로 실제 구현체와 Mock 교체 가능하도록 설계
- MockExternalBankService: simulate-delay/error 플래그로 장애 시나리오 재현
- Resilience4j Circuit Breaker: 5회 중 3회 이상 실패 시 30초 차단 → 보상 트랜잭션 반복 방지
```
**파일:** `ExternalBankService`, `MockExternalBankService`, `application.yml` resilience4j 설정

**체크:** `./gradlew build` 통과

---

### 8번
```
feat: 캐시백 지급 서비스 구현 (동시성 제어)

- Redisson 분산 락 내에서 TransactionTemplate으로 커밋 보장
- 락 해제 전 DB 커밋 완료 → 다음 스레드가 stale data 읽는 race condition 방지
- 예산 초과 시 예외가 아닌 0 반환 → 결제 자체는 성공으로 처리
```
**파일:** `CashbackService`

**체크:** `./gradlew build` 통과

---

### 9번
```
test: 캐시백 동시성 통합 테스트

- 100명 동시 요청 시 예산 초과 지급 없음 검증 (CountDownLatch 활용)
- 예산 소진 시나리오: 요청 100건 중 일부만 지급되고 DB usedBudget과 합계 일치 검증
- RedissonLockService를 ReentrantLock Mock으로 대체 → Docker 없이 실행 가능
```
**파일:** `CashbackConcurrencyTest`

**체크:** `./gradlew test --tests CashbackConcurrencyTest` 초록불

---

### 10번
```
feat: 결제 Facade 구현 (트랜잭션 분리 + 멱등성)

- PaymentFacade에 @Transactional 없음 → 외부 API 호출 중 DB 커넥션 점유 방지
- 이체(TX1) 커밋 → 커넥션 반환 → 외부 API → 캐시백(TX3) 순서로 단계별 트랜잭션 분리
- 동일 idempotencyKey 재요청 시 DB에 저장된 첫 번째 응답 그대로 반환
- IdempotencyCleanupScheduler: 매일 새벽 3시 만료(24h) 레코드 일괄 삭제
```
**파일:** `PaymentFacade`, `IdempotencyCleanupScheduler`, `CashbackSystemApplication`

**체크:** `./gradlew build` 통과

---

### 11번
```
feat: 결제 API 엔드포인트 및 초기 데이터 구현

- POST /api/v1/payments: @Valid 입력 검증 후 PaymentFacade 위임 (Thin Controller)
- DataInitializer: 서버 시작 시 테스트 계좌 2개 + 캐시백 예산 자동 생성
- PaymentRequest에 idempotencyKey 필드 추가 (@NotBlank 검증)
```
**파일:** `PaymentController`, `PaymentRequest`, `PaymentResponse`, `DataInitializer`

**체크:** `./gradlew build` 통과 + `docker compose up` 후 로그에 `[초기화]` 확인

---

### 12번
```
test: 결제 API 컨트롤러 테스트

- 정상 결제 / 잔액 부족 / 유효성 검증 실패 응답 형식 검증
- 동일 idempotencyKey 재요청 시 같은 응답 반환 (중복 처리 방지) 검증
- @WebMvcTest로 컨트롤러 레이어만 테스트 (빠른 실행)
```
**파일:** `PaymentControllerTest`

**체크:** `./gradlew test --tests PaymentControllerTest` 초록불

---

### 13번
```
docs: README 작성

- 설계 결정 이유 (트랜잭션 분리, 분산 락, 서킷 브레이커, 멱등성) 기술
- 실행 방법, 테스트 실행, API 명세, 장애 시나리오 테스트 방법 포함
```
**파일:** `README.md`

**체크:** 눈으로 확인

---

### 14번
```
refactor: 코드 정리 및 최종 점검

- 주석 보완 및 불필요한 코드 제거
- 전체 테스트 최종 통과 확인
```
**파일:** 전체

**체크:** `./gradlew test` 22개 전부 초록불

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
| 1~3번 (기반 설정) | 40분 |
| 4~9번 (핵심 로직 + 테스트) | 90분 |
| 10~12번 (Facade + API + 테스트) | 50분 |
| 13~14번 (문서 + 정리) | 30분 |
| **총합** | **약 3.5시간** |

1.5시간 여유 → 서버 실행 확인, 예상치 못한 오류 대응 가능.
