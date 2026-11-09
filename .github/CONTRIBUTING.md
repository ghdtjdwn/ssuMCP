# 기여 안내

## 로컬 실행

- Java 21. 기본 profile은 mock connector라 학교 계정이 필요 없다.
- 기본 설정은 `localhost:6379` Redis를 기대한다. 없으면 `SSUAI_LIBRARY_REDIS_ENABLED=false ./gradlew bootRun`.
- 자세한 절차는 [README](../README.md#로컬-실행과-검증).

## 테스트

```bash
./gradlew test jacocoTestReport jacocoTestCoverageVerification
```

- `*IT` 통합 테스트는 Testcontainers(PostgreSQL·Redis)를 쓰며 Docker가 없으면 self-skip한다. 로컬 skip은
  통과가 아니다. Docker가 있는 GitHub Actions CI가 권위 게이트이며, LMS download-claim IT가 실제로 실행됐는지도
  CI가 별도로 확인한다.
- JaCoCo LINE 커버리지가 `build.gradle`의 floor 아래로 내려가면 빌드가 실패한다.

## MCP 도구를 추가·변경할 때

다음 세 곳을 함께 갱신한다. `McpToolContractInventoryTests`가 불일치를 잡는다.

1. `@Tool` 구현과 `ToolContract` 인벤토리
2. `src/main/resources/static/.well-known/mcp/server-card.json`
3. `docs/mcp-tools.md`

쓰기 도구는 `prepare_* → confirm_action` 계약을 따른다([docs/security-consistency.md](../docs/security-consistency.md)).

## 설계 변경

동작·경계·운영 방식이 바뀌는 결정은 `docs/adr/`에 다음 번호(`NNNN-slug.md`)로 기록하고 `docs/README.md`
지도를 갱신한다.

## 커밋과 PR

- 커밋 제목은 기존 이력처럼 `feat|fix|docs|ops|test|build(scope): 요약` 형식을 쓴다.
- PR 본문에는 무엇을·왜 바꿨는지와 실행한 검증 명령·결과를 적는다. 실제로 관찰하지 않은 테스트·배포
  결과는 적지 않는다.
- 자격증명·개인 데이터·내부 주소는 커밋하지 않는다. 보안 문제는 [SECURITY.md](SECURITY.md)를 따른다.
