# MCP SSE 동시성 포화가 지속된 문제

## 배경과 영향

2026-09-03 배포 확인 중 공개 `/mcp`에 올바른 `initialize` POST를 보냈지만 수 분 동안 HTTP 429
`RATE_LIMITED`가 이어졌다. `Retry-After`는 계속 1초였고 분 경계를 지나도 바뀌지 않았다. 확인에 사용한
source IP에서는 새 MCP session을 시작할 수 없었지만, 전체 사용자 영향이나 어느 client가 기존 슬롯을
점유했는지는 관측 자료가 없어 단정하지 않는다.

기대 동작은 client가 SSE 연결을 닫으면 servlet async lifecycle이 끝나고, 해당 pod의 per-IP/global
concurrency lease가 반환되는 것이다.

## 조사

- 분당 Redis counter가 원인이라면 `Retry-After`는 fixed window 경계까지 남은 시간이 된다. 1초가 여러 분
  지속된 응답은 request-rate보다 concurrency 거부 경로와 일치했다.
- concurrency 거부는 lease를 얻기 전에 끝난다. 따라서 실패한 initialize 재시도 자체가 stale lease를 만들지는
  않는다. 다만 rate counter는 concurrency 검사보다 먼저 증가하므로 반복 재시도는 120회/분 예산을 소모한다.
- MCP SDK 0.18.4의 initialize handler는 HTTP 200 `application/json`을 동기로 반환한다. 이 요청은 async
  context를 만들지 않아 filter의 `finally`에서 lease를 즉시 반환한다.
- 같은 SDK의 GET listening stream과 일부 POST response stream은 Spring WebMVC
  `ServerResponse.sse(..., Duration.ZERO)`를 사용한다. application에는 MCP keep-alive도 없어서 idle stream에
  서버 write가 발생하지 않을 수 있었다.
- filter는 async complete/error/timeout에 lease를 반환하도록 구현돼 있었지만 timeout 자체가 무한이어서,
  servlet이 completion/error를 전달하지 못한 연결에는 application 수준의 회수 상한이 없었다.

## 확인된 설계 결함과 원인 가설

확인된 사실은 동시성 포화가 지속됐고, 동시성 슬롯을 소유하는 async SSE lifecycle에는 수명 상한이 없었다는
점이다. TCP 종료가 즉시 servlet error/completion으로 전달되지 않고 이후 write도 없으면 lease가 pod 종료까지
남을 수 있는 구조였으므로, 감지되지 않은 연결 종료가 유력한 설명이다. 다만 당시 active lease나 거부 원인을
구분하는 metric이 없어 실제 점유자가 끊긴 client였는지 정상 listening client였는지, per-IP와 global cap 중
어느 쪽이 찼는지는 사후에 확정할 수 없다. 정적인 `Retry-After: 1`은 재시도 힌트일 뿐 실제 1초 후 회수를
보장하지 않았다.

## 해결과 대안

`ssuai.ratelimit.mcp-async-lease-timeout`을 추가하고 기본값을 5분으로 정했다. MCP filter는 downstream SDK가
async context를 만든 뒤 이 timeout을 명시적으로 설정한다. complete/error/timeout callback은 release-once로
같은 lease를 한 번만 반환한다. 동기 initialize는 async 분기에 들어가지 않는다.

이 값은 idle timeout이 아니라 async request의 절대 수명이다. 따라서 정상적으로 연결된 listening GET도 5분마다
종료되며 Streamable HTTP client가 필요하면 다시 연결해야 한다. MCP request timeout 기본값은 이 상한보다 훨씬
짧아 일반 tool response를 먼저 끝내는 것이 기존 계약이다.

검토한 대안은 다음과 같다.

- SDK keep-alive: 끊김 감지를 앞당길 수 있지만 0.18.4는 ping 실패 session을 map에서 제거하지 않아 경고와
  주기 작업이 누적된다. hard bound도 아니므로 단독 해결책으로 쓰지 않았다.
- `spring.mvc.async.request-timeout`: SDK의 명시적인 zero timeout 뒤에 적용되지 않고, MCP 외 SSE까지 바꾼다.
- concurrency cap 상향: 증상을 늦출 뿐 stale lease 수명을 고치지 않는다.
- X-Forwarded-For 조작이나 sticky cookie 우회: 신뢰 경계를 약화하고 실제 결함을 숨기므로 사용하지 않는다.

## 검증과 회귀 방지

- `RateLimitFilterTests`: downstream이 시작한 async context의 timeout을 filter가 덮어쓰는지, timeout과 정상
  complete 뒤 동일 IP 슬롯이 다시 열리는지, 동기 MCP POST는 즉시 lease를 반환하는지 검증한다.
- `McpAsyncLeaseTimeoutIntegrationTests`: 실제 내장 서버에서 session GET SSE가 동시성 슬롯을 점유해 다음 요청을
  429로 막고, servlet timeout 뒤 연결이 종료되면 동일 IP가 다시 요청할 수 있는지 검증한다.
- `PlayMcpProtocolTests`: 실제 내장 서버에 initialize를 보내 HTTP 200 JSON, 협상 protocol
  `2025-03-26`, session header를 확인한 뒤 DELETE로 정리한다.
- 운영 smoke는 한 번의 initialize만 보내고, 성공 시 같은 affinity cookie와 session header로 DELETE한다.
  429이면 session이 생성되지 않았으므로 반복하지 않는다.

## 롤백과 남은 위험

5분 내 재연결을 지원하지 않는 client가 확인되면 Helm의 `env.mcpAsyncLeaseTimeout`을 늘려
`SSUAI_RATELIMIT_MCP_ASYNC_LEASE_TIMEOUT`을 조정할 수 있다. 설정 변경과 pod restart는 별도 production
승인 대상이다.

이 변경은 SDK 내부 session map을 만료시키지 않는다. 정상 client의 DELETE가 여전히 권위 있는 session 종료
방법이다. 통합 테스트는 servlet timeout과 슬롯 회수를 검증하지만 특정 외부 client의 자동 재연결, session 유지,
이벤트 유실 여부까지 증명하지는 않는다. 또한 현재 429 응답과 로그는 per-IP cap과 global cap을 구분하지 않아,
후속으로 active lease와 거부 원인을 low-cardinality metric으로 노출할 여지가 남아 있다.
