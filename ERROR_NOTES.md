# 에러 대응 메모 (개인 참고용, 제출 전 삭제)

---

## 테스트 실패

### CashbackConcurrencyTest 2건 실패 (RedisConnectionException)
- 원인: 로컬에 Redis가 없는 상태에서 테스트 실행
- 해결: `docker compose up` 으로 Redis 띄운 후 다시 실행
- 정상: Docker 없이 실행 시 이 2건만 실패하고 나머지 20건은 통과

### 한글 로그 깨짐
- 원인: 터미널 인코딩 문제
- 해결: `gradle.properties`에 `org.gradle.jvmargs=-Dfile.encoding=UTF-8` 확인
- 또는 `build.gradle` test 블록에 `jvmArgs('-Dfile.encoding=UTF-8')` 확인

---

## 서버 실행

### docker compose up 후 아무 로그 안 뜸
- 원인: MySQL → Redis 헬스체크 통과 대기 중 (최초 실행 시 약 40초 소요)
- 해결: 그냥 기다리면 됨. 터미널 클릭하거나 다른 창 누르면 프로세스 멈추는 것처럼 보이지만 계속 진행 중

### 포트 충돌 (Port already in use)
- 원인: 이전에 띄운 컨테이너가 남아있거나 로컬에 MySQL/Redis가 실행 중
- 해결: `docker compose down` 후 다시 `docker compose up --build`
- Docker Desktop에서 이전 컨테이너 수동 중지 후 실행

### Docker Desktop 실행 안 한 채로 compose up
- 원인: Docker Desktop이 꺼져 있으면 명령어 자체가 안 먹힘
- 해결: Docker Desktop 먼저 실행 후 진행

---

## 빌드

### Gradle 빌드 실패
- 의존성 문제면 `./gradlew clean build` 로 캐시 초기화 후 재시도
- Java 버전 확인: `java -version` → 17이어야 함

---

## DB

### DataInitializer 중복 실행 오류
- 원인: 서버 재시작 시 이미 초기 데이터가 있는 상태에서 다시 insert 시도
- 해결: `DataInitializer`에 `existsByAccountNumber` 체크 로직 있어서 중복 방지됨. 오류 아님

---

## 기타

### [N번] 커밋 주석 제출 전 삭제 대상
- 각 클래스 상단 `// ======= [N번] =======` 주석 전부 삭제
- COMMIT_PLAN.md 삭제
- ERROR_NOTES.md (이 파일) 삭제
