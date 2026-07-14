# ADR 0098 — 명시 세션 우선의 MCP 인증 경계와 자격증명 쓰기 펜스

- 상태: 채택 (2026-07-14)
- 관련: 0086(prepare/confirm), 0092(MCP transport affinity), 0095(SSO code exchange), 0096(영속 library session)

## 배경

라이브 도구 감사에서 private tool 일부가 잘못된 `mcp_session_id`를 받은 뒤 transport-bound
세션으로 조용히 fallback했다. 그 결과 다른 세션의 개인 데이터와 대기 export/action이 노출·확정될 수
있었다. 인증 도구와 일반 도구가 서로 다른 해석 규칙을 사용한 것이 근본 원인이다.

## 결정

`McpSessionResolver`를 일반 MCP 작업의 단일 해석 경계로 둔다.

1. 비어 있지 않은 명시 ID는 정확히 그 세션만 조회한다. 없거나 만료·무효면 `INVALID_SESSION`이며
   transport/OAuth 세션으로 fallback하지 않는다.
2. 유효한 명시 ID가 현재 transport binding과 다르면 `SESSION_MISMATCH`다. 응답은 어느 세션 ID도
   echo하지 않는다.
3. 명시 ID가 없을 때만 유효한 현재 transport binding을 사용할 수 있고, 없으면 `NO_SESSION`이다.
4. `start_auth`만 명시적으로 허가된 rebind 컨텍스트를 쓴다. private tool의 `AUTH_REQUIRED` 생성
   경로도 rebind 해석을 다시 시도하지 않는다.
5. provider credential, action, wait intent, LMS preview/job/capability는 모두 정확한 resolved MCP
   session owner에 귀속한다. logout과 provider unlink는 같은 세션 row 잠금 아래 credential-version
   fence를 획득하므로 진행 중인 destructive upstream write와 경쟁하지 않는다.

## 대안

- transport를 항상 우선: 명시 ID를 준 호출의 의도를 무시해 감사에서 확인된 data leak을 재현한다.
- unlinked 명시 세션만 private tool에서 자동 rebind: `AUTH_REQUIRED` 생성 중 재해석되어 mismatch
  세션을 응답에 노출하는 우회를 만들었다.
- 명시 ID 지원 제거: ChatGPT/Claude Desktop 등 기존 explicit workflow 호환성을 불필요하게 깨뜨린다.

## 검증과 한계

MCP HTTP self-dogfood test가 28개 모든 private tool에 대해 no-binding, random explicit,
invalidated explicit, transport mismatch를 검증한다. 각 denial은 세션 ID와 login URL을 비워 둔다.
LMS cookie jar는 origin-scoped versioned persistence를 사용한다. upstream write 성공 후 DB terminal
state 저장이 실패하는 불확실 결과는 외부 API의 idempotency 보장이 없는 한 완전히 제거할 수 없으며,
action audit와 재시도 관찰로 운영한다.
