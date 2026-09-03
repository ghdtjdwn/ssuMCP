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

## 2026-09-03 후속 운영 관찰

### 기대와 실제 동작

최종 공개 점검에서 ssuAgent의 shallow `/health`는 HTTP 200이었지만, MCP 도구 탐색까지 확인하는
`/healthz/deep`은 세 번 모두 HTTP 503과 `status=DEGRADED`, `mcp=DOWN`을 반환했다. 기대값은 HTTP 200과
두 필드의 `UP`이다. ssuMCP의 `/api/meals/today`는 같은 시각 HTTP 200이었으므로 backend ingress와 해당 REST
경로의 도달성은 확인됐지만, Streamable HTTP `/mcp`의 initialize와 `tools/list` 성공까지 증명하지는 않는다.

ssuAgent의 liveness는 shallow health, readiness는 PostgreSQL과 checkpointer만 사용하므로 이 결과가 Pod를
자동 재시작하거나 traffic endpoint에서 제거하지는 않는다. 프로세스는 응답하지만 MCP 도구가 필요한 대화가
영향을 받을 수 있는 degraded 상태로 해석한다. 실제 사용자 요청 실패율은 관측 자료가 없어 추정하지 않는다.

### 수집한 근거와 원인 경계

- ssuAgent deep health는 configured `SSUMCP_URL`에 Streamable HTTP client를 만들고, 2초 제한 안에
  `get_tools()`를 한 번 수행한다. DNS, TLS, ingress, non-2xx 응답, MCP protocol, tools/list 또는 전체 2초
  timeout 중 어느 단계가 실패해도 외부에는 같은 503 응답을 낸다.
- 당시 GitOps desired state는 ssuAgent image `sha-e7caddd59dd83f7f332f30027a82768ce7004059`, ssuMCP image
  `sha-b9c30e85f7f514df750daf4d04a9b281484a8f02`였다. 두 image의 GitHub build gate는 성공했다.
- 접근 가능한 환경에서 Argo CD hostname을 해석할 수 없어 Application의 Synced/Healthy 상태, 실제 Pod image,
  Ready 수와 restart 횟수는 확인하지 못했다. 따라서 desired image가 실제 실행 중이라고 전제하지 않는다.
- 직전 공개 문서 정리는 ssuAgent deep-health 코드와 설정을 바꾸지 않았다. ssuMCP 정리 변경도 full profile의
  MCP 계약을 바꾸지 않았으므로 그 변경이 직접 원인이라는 근거는 없다.

최초 인과 실패는 ssuAgent와 ssuMCP 사이의 도구 탐색 경계까지 좁혔지만, public response가 의도적으로 예외를
평탄화하고 cluster 로그를 보지 못했으므로 root cause는 확정하지 않았다. 이전 공개 initialize의 429 관찰은
concurrency 가설을 지지하지만 ssuAgent Pod의 egress와 다른 source IP에서 얻은 결과라 단독 증거로 쓰지 않는다.

### 안전한 진단 순서

1. Argo CD Application source revision과 sync/health를 확인하고, ssuAgent와 ssuMCP의 실제 image SHA, Ready 수,
   restart 횟수를 desired state와 대조한다.
2. ssuAgent Pod 안에서 configured `SSUMCP_URL`로
   `initialize → notifications/initialized → tools/list`를 한 session에서 수행한다. 모든 요청에 같은 affinity
   cookie와 `Mcp-Session-Id`를 유지하고, 성공 시 같은 값으로 DELETE한다. 429이면 자동 재시도하지 않는다.
3. 같은 시각의 `deep health MCP check failed` 로그를 내부에서 확인하되 credential, token, session identifier와
   exception message 원문은 외부 기록에 복사하지 않는다. 현재 로그만으로 exception class와 실패 계층을 확정할
   수 없으면 Pod 내부 protocol probe 결과와 연결해 다음 확인 대상을 정한다.
4. 전체 2초 timeout, non-2xx, connection failure는 각각 가능한 원인 범위를 좁히는 단서일 뿐이다. DNS, TLS,
   ingress, service, protocol 또는 rate-limit을 추가 증거 없이 확정하지 않고, 근거 없이 timeout이나 concurrency
   cap을 올리거나 Pod를 재시작하지 않는다.

운영 변경 전 확인할 질문은 세 가지다. deep health를 liveness와 분리한 이유가 유지되는가, rate window와
concurrency lease 중 어느 제한이 거부했는지 metric으로 구분할 수 있는가, 재시작 없이 원인을 재현하고 회귀
테스트로 고정할 수 있는가. 현재는 이 질문에 필요한 cluster evidence가 없으므로 설정 변경과 재시작을 보류한다.

### 문서 전달

[PR #251](https://github.com/ghdtjdwn/ssuMCP/pull/251)의 정확한 head
`22f288ab28acb7d9f1de3bf3aaf93a2b8a78da6e`를 `main`에 fast-forward했다. PR의
[Backend gate](https://github.com/ghdtjdwn/ssuMCP/actions/runs/33706036381),
[CodeQL](https://github.com/ghdtjdwn/ssuMCP/actions/runs/33706036382)과
[Security](https://github.com/ghdtjdwn/ssuMCP/actions/runs/33706036384)가 통과했다. 문서 전용 변경이라
MCP server image job은 의도대로 skip됐고 runtime image와 GitOps desired tag는 바뀌지 않았다. 병합 후
[CodeQL](https://github.com/ghdtjdwn/ssuMCP/actions/runs/33706406509)과
[Security](https://github.com/ghdtjdwn/ssuMCP/actions/runs/33706406717)도 통과했다.
