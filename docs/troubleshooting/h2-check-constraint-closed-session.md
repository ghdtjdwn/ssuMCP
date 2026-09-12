# H2 CHECK 제약이 닫힌 생성 세션을 재사용한 CI 실패

## 맥락과 기대 동작

정책 검토 Copilot의 V18 마이그레이션은 `policy_review_cases.status`를
`CHECK (status IN (...))`로 제한한다. 보존정책 통합 테스트는 Flyway가 만든 실제 H2 스키마에서
오래된 active case와 terminal case의 서로 다른 삭제 기간을 검증해야 한다.

## 실제 동작과 영향

2026-07-28 PR #235의 첫 GitHub Actions 실행 `30296741590`에서 전체 1,515개 테스트 중
`DataRetentionJobIntegrationTests.appliesSeparateActivePrivacyAndTerminalPolicyReviewWindows` 한 건이
첫 `APPROVED` fixture INSERT 중 실패했다. 기능 코드나 운영 데이터에는 영향이 없었지만 백엔드 CI와
후속 이미지 작업이 차단됐다.

표면 오류는 `CHK_POLICY_REVIEW_STATUS` 위반이었으나, 최하위 예외는 다음과 같았다.

```text
Check constraint invalid: "CHK_POLICY_REVIEW_STATUS"
Caused by: The database has been closed [90098-240]
at ConditionInConstantSet.getValue(...)
```

## 재현과 타임라인

1. 기능 브랜치의 JDK 21 로컬 전체 게이트는 통과했다.
2. GitHub Actions의 Docker 포함 전체 순서에서 H2 2.4.240 오류가 한 번 발생했다.
3. 실패 리포트에서 INSERT SQL, constraint 이름, 중첩 예외와
   `ConditionInConstantSet → SessionLocal.compare` 경로를 확인했다.
4. H2 2.4.240 jar에서 connection A가 `CHECK ... IN (...)`을 만든 뒤 A를 닫고, 같은
   `DB_CLOSE_DELAY=-1` DB의 connection B가 유효한 값을 INSERT하면 CI와 같은 예외가 재현됐다.
5. 보존정책 통합 테스트만 다시 실행하면 통과해, 입력 fixture의 결정적 상태 오류가 아니라
   테스트 컨텍스트 순서에 의존하는 수명주기 문제로 범위를 좁혔다.

## 검토한 가설

- 잘못된 enum 값 또는 상태별 필드 조합: V18의 상태 제약과 entity 전이를 대조했고, 실제 최하위 원인이
  데이터 위반이 아니라 닫힌 H2 세션이라 기각했다.
- JVM 종료 또는 `DB_CLOSE_DELAY` 누락: 실패 뒤에 JVM shutdown hook 로그가 나왔고 기본 URL에는 이미
  `DB_CLOSE_DELAY=-1`이 있었다. 이 옵션은 마지막 연결 뒤 DB 보존만 제어하므로 원인과 맞지 않았다.
- H2 구현 결함: upstream 이슈와 수정 커밋의 재현 스택·코드 경로가 CI 스택과 일치해 채택했다.

## 근본 원인

H2 2.4.240의 알려진 결함이다. `CHECK ... IN (...)`을 최적화한
`ConditionInConstantSet`이 제약을 만든 `SessionLocal` 기반 comparator를 보관한다. Spring 테스트 컨텍스트가
같은 이름의 영속 인메모리 DB를 재사용하는 동안 원래 connection pool이 닫히면, 이후 connection의
INSERT가 닫힌 생성 세션을 통해 상수 집합을 비교해 실패할 수 있다.

H2 이슈 [#4063](https://github.com/h2database/h2database/issues/4063)과
[#4291](https://github.com/h2database/h2database/issues/4291)이 같은 증상을 추적한다. Upstream
커밋 [`35f70285`](https://github.com/h2database/h2database/commit/35f70285bdc6107639edb66982dc2ee64ad111e2)은
session comparator를 database comparator로 바꾸지만, 사용 중인 정식 릴리스 2.4.240에는 포함되지 않았다.

## 대안과 선택

- H2를 이전 버전으로 고정: 결함을 피할 수 있지만 Spring Boot dependency management를 벗어난
  다운그레이드이며 다른 수정·호환성을 잃을 수 있어 기각했다.
- 미출시 commit 또는 snapshot 사용: 재현성·공급망 안정성이 떨어져 기각했다.
- `DB_CLOSE_DELAY` 조정: stale session 참조를 고치지 못해 기각했다.
- 테스트 컨텍스트별 DB 격리: 제품 의존성을 바꾸지 않고 닫힌 이전 컨텍스트가 만든 스키마 재사용을
  제거하므로 임시 대응으로 선택했다.

## 구현

`application-test.yml`의 H2 URL을
`jdbc:h2:mem:ssuai-test-${random.uuid};DB_CLOSE_DELAY=-1;MODE=PostgreSQL`로 바꿨다. 같은 Spring
컨텍스트는 같은 DB와 connection pool을 사용하고, 새 컨텍스트는 고유 DB에서 Flyway를 다시 적용한다.
PostgreSQL Testcontainers 테스트는 `@ServiceConnection`이 datasource를 대체하므로 영향을 받지 않는다.

## 검증

- JDK 21 집중 실행:
  `./gradlew test --tests com.ssuai.global.retention.DataRetentionJobIntegrationTests --rerun-tasks` 통과
- JDK 21 전체 게이트:
  `./gradlew cleanTest test jacocoTestReport jacocoTestCoverageVerification build` 통과
  (1,515 tests, failures 0, errors 0, skipped 18)
- GitHub Actions의 같은 JDK 21 전체 게이트를 merge 전 필수 검증으로 유지한다.

## 회귀 방지와 관측

- 모든 `test` profile Spring 컨텍스트가 고유 H2 DB를 사용해 컨텍스트 cache eviction과
  `@DirtiesContext` 순서에 따른 스키마 재사용을 제거한다.
- CI는 Docker가 있는 전체 순서에서 H2와 실제 PostgreSQL 테스트를 함께 실행한다.
- 다음 H2 정식 릴리스에 upstream fix가 포함되면 dependency insight와 전체 CI로 확인한 뒤 이 우회를
  유지할지 재평가한다.

## 남은 위험

고유 DB는 Spring 컨텍스트 사이의 stale session 재사용을 차단하지만 H2 2.4.240 자체 결함을 제거하지는
않는다. 한 컨텍스트 안에서 제약 생성 connection을 물리적으로 닫은 뒤 다른 connection이 같은 제약을
사용하는 별도 흐름이 생기면 재발할 수 있다. 또한 `DB_CLOSE_DELAY=-1`인 고유 DB는 테스트 JVM 종료까지
남으므로 컨텍스트 수에 비례한 메모리 비용이 있다. 현재 전체 게이트에서는 문제가 없었지만, 컨텍스트 수가
크게 늘면 close-delay 제거 또는 H2 수정 릴리스 전환을 함께 검토한다. 운영 DB는 PostgreSQL이라 이 H2 전용
결함의 영향을 받지 않는다.
