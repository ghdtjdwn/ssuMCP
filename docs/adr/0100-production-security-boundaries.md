# ADR 0100 — 운영 인증·MCP·관측 경계의 fail-closed 기본값

- 상태: 채택 (2026-07-27)
- 관련: 0058(prod fail-fast), 0061(rate limit), 0080(shared rate limit)

## 배경

운영 감사에서 네 가지 구성 불일치가 확인됐다. 로그아웃이 refresh JTI를 denylist에 기록하지만 refresh
endpoint가 이를 읽지 않았고, 익명 MCP POST와 GET SSE stream은 REST rate-limit filter 범위 밖이었다. Prometheus endpoint는
public catch-all ingress로 노출됐으며, `application-prod.yml`의 일부 connector 기본값은 mock이었다.
현재 Helm 값이 안전해도 다른 production 실행 방식이나 누락된 환경변수는 mock을 정상 서비스처럼 띄울 수
있다.

## 결정

- refresh JWT parse 직후 denylist를 확인한다. 정상 rotation은 이전 token을 deny하지 않아 cross-site
  cookie 재전송 호환성을 유지하지만, 명시적 logout으로 폐기된 JTI는 남은 TTL 동안 401이다.
- `/mcp` POST와 GET에 Redis 공유 per-IP window limit을 적용하고, pod마다 per-IP 및 global in-flight
  cap을 둔다. async GET SSE는 완료·오류·timeout까지 lease를 유지한다. 익명 공개 도구 계약은 유지하되
  비용과 Tomcat/connection 점유를 제한하며, DELETE는 예산 고갈 뒤에도 session 정리를 위해 허용한다.
- MCP WebMVC transport가 SSE를 `Duration.ZERO`로 시작하더라도 rate-limit filter가 async context timeout을
  5분으로 덮어쓴다. listening GET은 Streamable HTTP 계약에 따라 다시 연결할 수 있고, 일반 MCP 요청의
  응답 제한은 이보다 짧다. 동기 `initialize` JSON 응답은 async context를 만들지 않으므로 이 상한의 영향을
  받지 않으며, 정상 종료·오류·명시 DELETE는 기존처럼 먼저 lease를 반환한다.
- production management server를 8081로 분리한다. readiness/liveness와 ServiceMonitor는 내부 service port를
  사용하고 public ingress는 8080 application port만 전달한다.
- prod의 모든 connector는 real/rusaint/llm이어야 한다. blank 또는 mock이면 기동을 거부한다. 우회
  설정은 두지 않으며 fixture가 필요한 데모는 non-production profile을 사용한다.
- 외부 Kubernetes Secret 회전은 `secretRef.revision`을 같은 Git 변경에서 증가시켜 rollout을 일으킨다.

## 대안

- ingress에서만 rate limit: 내부·직접 Service 호출과 replica 간 예산을 다루지 못해 앱 공유 limiter를 함께
  선택했다.
- SDK keep-alive만 활성화: 0.18.4의 scheduler는 실패한 ping 뒤 session map에서 항목을 제거하지 않는다.
  끊긴 session마다 경고와 주기 작업이 계속 남고 hard cleanup 상한도 제공하지 않아 선택하지 않았다.
- 전역 Spring MVC async timeout: MCP SDK가 SSE마다 명시적으로 zero timeout을 설정하며, 전역값은 다른 SSE
  API까지 함께 바꾼다. MCP concurrency lease를 소유한 요청에만 timeout을 다시 설정하는 편이 경계가 작다.
- Prometheus에 bearer token 추가: scraper secret 운영보다 management network boundary가 단순하고 누출
  면적이 작다.
- mock 기본값을 유지하고 Helm만 테스트: Helm 밖 prod 실행과 env 이름 drift를 막지 못한다.
- refresh rotation마다 이전 JTI 폐기: 실제 cross-site cookie 교체 실패가 정상 사용자를 로그아웃시킨 이력이
  있어 명시적 logout revocation만 적용한다.

## 검증과 운영 한계

controller·filter·validator 단위 테스트와 Helm render를 release gate에 포함한다. Redis 장애 시 request-rate
counter는 기존 per-pod limiter로 degrade하고 refresh denylist는 가용성을 위해 fail-open한다. 따라서 Redis
장애 동안 revocation의 짧은 공백은 access/refresh 자체 TTL로 제한한다. Secret 실제 교체와 rollout은
production 변경이므로 승인된 runbook에서만 실행한다.

MCP async lease 테스트는 SDK의 infinite timeout이 설정된 뒤 filter 값으로 교체되는지, timeout/complete가
동일 IP 슬롯을 다시 여는지 검증한다. 실제 Streamable HTTP 통합 테스트는 `initialize`가 HTTP 200 JSON으로
종료하고 `2025-03-26`을 협상한 뒤 DELETE로 session을 정리하는 계약을 고정한다. 5분 상한은 SSE lease만
회수하며 SDK session map의 만료 정책을 대신하지 않는다. 클라이언트는 종료 시 DELETE를 계속 보내야 하고,
SDK가 안전한 idle-session eviction을 제공하면 이 우회 경계를 재검토한다.
