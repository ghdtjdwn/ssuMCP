# ADR 0095 — u-SAINT SSO 콜백을 1회용 authorization code + POST /api/auth/exchange로 전환 (Fix B)

- 상태: 채택 (2026-07-12)
- 관련: 0014(SSO redirect-callback 설계), 0074(MCP OAuth 체인 스코핑 — 같은 로그인 장애의 다른 원인), 0092(Traefik 세션 어피니티 쿠키)

## 배경

u-SAINT SmartID SSO 로그인 성공 후 `GET /api/auth/saint/sso-callback`이 내려주는 refresh 쿠키(`ssuai_refresh`)가 브라우저에 안정적으로 도달하지 못하는 장애가 실사용자 로그인에서 반복됐다.

첫 번째 가설은 Vercel의 rewrite 프록시가 302/캐시 가능한 응답에서 `Set-Cookie`를 벗겨낸다는 것이었다 — 이는 ADR 0074가 고친 별개의 장애(MCP OAuth 리소스 서버 체인이 웹 JWT 경로까지 덮어 401을 낸 문제)와 섞여서 같은 "로그인이 안 된다" 증상으로 보고됐다. Fix A(현재 코드: 200 + JS `location.replace` + 콜백 응답 자체에 `Set-Cookie`를 얹는 방식, `htmlRedirect(location, setCookie)`)는 "Vercel이 302에서만 쿠키를 버린다"는 가설 위에서 200 응답으로 우회했지만, 필드에서 여전히 실패했다.

실제 원인 체인은 다르다: 프론트엔드가 콜백 응답을 가로채는 자체 프록시 레이어를 두고 있었는데, 이 프록시의 쿠키 릴레이가 **여러 개의 `Set-Cookie` 헤더를 잘못 파싱**했다 — Traefik이 모든 응답에 세션 어피니티 쿠키(`mcp_lb_affinity`, ADR 0092)를 추가로 붙이기 때문에, 백엔드 응답에는 항상 최소 2개의 `Set-Cookie`가 실려 있었고 이 프록시는 그 join된 형태를 오파싱했다. 게다가 이 프록시의 redirect 분기는 애초에 쿠키를 전혀 복사하지 않았다. 즉 Fix A가 고치려던 지점(200 vs 302)은 실제 원인이 아니었다 — 원인은 백엔드-프론트엔드 사이에 낀 프록시 레이어의 쿠키 처리 버그였다.

## 고려한 대안

1. **프론트엔드 프록시의 파싱을 고친다** ❌ — 두 번이나 실패를 낸 취약한 쿠키 릴레이 레이어를 그대로 유지하는 선택이다. 프론트엔드가 백엔드 쿠키 내부 동작(Set-Cookie 개수, Traefik이 뭘 더 붙이는지)에 계속 결합돼 있어야 하고, Traefik이나 다른 인프라 레이어가 쿠키를 하나 더 추가하면 또 깨진다.
2. **콜백에서 쿠키를 유지하되 rewrite passthrough를 신뢰한다** ❌ — "이 프록시 분기는 문서화되지 않은 채 쿠키를 통과시킨다"는 동작에 계속 기대는 것이다. 검증되지 않은 프록시 동작에 로그인 성공 여부를 거는 구조는 반복 재발 위험이 그대로 남는다.
3. **Postgres 기반 1회용 state 저장소** (`McpAuthStateStore`와 동일한 패턴 — 상태를 테이블에 저장하고 원자적 delete-if-active로 소비) ❌ — 동작은 하지만 이 유스케이스에는 과하다. Flyway 마이그레이션이 필요하고, 코드의 수명(2분)에 비해 영구 테이블/컬럼을 두는 건 목적을 넘어선다. Redis(Redisson)는 이미 `RefreshTokenDenylist`, MCP 세션 등에서 라이브로 쓰이고 있어 별도 마이그레이션 없이 바로 재사용 가능하다.
4. **Redis 기반 1회용 authorization-code exchange** ✅ (채택) — 콜백은 쿠키를 전혀 내려주지 않고, URL에 1회용 코드만 실어 보낸다. 프론트엔드는 그 코드를 같은 오리진으로 `POST /api/auth/exchange`에 보내고, 그 응답(리다이렉트가 아닌 순수 200)이 실제 `Set-Cookie`를 전달한다 — 이 경로는 도서관 로그인 쿠키 발급에서 이미 검증된, 신뢰 가능한 전달 경로다.

## 결정

### 코드 형식 / 엔트로피
`AuthExchangeCodeStore.issue`는 `SecureRandom`으로 256비트를 생성해 base64url(패딩 없음)로 인코딩한다. UUID(122비트 실질 엔트로피)보다 여유 있게 잡은 이유는 이 값이 URL 쿼리 파라미터로 노출되고(브라우저 히스토리, 서버 액세스 로그, Referer 등) 값 하나가 곧 로그인 자격증명이기 때문 — 추측/전수조사 공격 여지를 사실상 0으로 만드는 쪽을 택했다.

### TTL 120초
`ssuai.auth.exchange-code-ttl` (기본 120초). 콜백 리다이렉트 → 프론트엔드 페이지 로드 → `/api/auth/exchange` fetch까지의 왕복을 느린 모바일 네트워크까지 감안해 커버하면서, 브라우저 히스토리에 남는 코드가 사실상 무의미해질 만큼 짧게 잡았다. 만료된 코드는 Redis TTL로 스스로 사라지므로 별도 정리(clean-up) 잡이 필요 없다 — `McpAuthStateStore`의 `@Scheduled` 정리 잡과 대비되는 지점.

### Redis 에러에서 fail-closed (RefreshTokenDenylist의 fail-open과 반대)
`RefreshTokenDenylist`는 Redis 에러를 삼키고 fail-open한다 — 실패해도 "이미 만료 예정인 토큰을 조금 더 허용한다"는 작은/한정된 blast radius이기 때문이다. `AuthExchangeCodeStore`는 다르다 — 이건 로그인을 완성시키는 유일한 자격증명 그 자체다. 여기서 Redis 에러를 삼키면 (a) `issue`에서 쓰기가 실제로는 실패했는데도 브라우저에 "쓸 수 없는" 코드를 내려주거나, (b) `consume`에서 진짜 읽기 실패를 "코드 없음"으로 오판해, 코드가 다른 정상 replica에는 여전히 유효하게 남아 있는데도 정상 로그인 시도를 에러 페이지로 튕겨내는 결과를 낳는다. 둘 다 1회용 자격증명 저장소로서 받아들일 수 없는 실패 모드이므로, 두 메서드 모두 `RuntimeException`을 그대로 전파한다 — 호출자(콜백 / exchange 엔드포인트)가 이를 로그인 실패로 드러내는 편이, 조용히 1회성 보장을 깨뜨리는 것보다 낫다.

### 모든 콜백 응답을 예외 없이 200+JS로 통일
Fix A는 성공 응답에서만 200+JS+쿠키를 썼고 에러 분기는 여전히 302 `redirect(...)`였다. 이번 인시던트의 교훈은 "그 사이 어딘가의 프록시/CDN이 redirect를 다르게 취급할 수 있다"는 것 그 자체였으므로, 이번 설계는 성공/에러를 가리지 않고 콜백의 모든 응답을 같은 200+JS `location.replace` 모양으로 통일한다(`htmlRedirect(URI)`, 인자에서 `setCookie`를 아예 제거). 이제 이 컨트롤러는 어떤 응답에도 `Set-Cookie`를 싣지 않으므로, 앞단의 어떤 프록시가 redirect/캐시를 어떻게 재해석하든 더 이상 로그인에 영향을 줄 수 없는 구조가 됐다.

## 동작 원리

```
GET sso-callback (성공)
  → saintSsoService.authenticate + studentService.upsertOnLogin (변경 없음)
  → LMS best-effort side-auth (변경 없음, 실패해도 로그인은 계속됨)
  → authExchangeCodeStore.issue(studentId) → code
  → 200 html, location.replace(frontendOrigin/auth/return?code=...)   ※ Set-Cookie 없음

GET sso-callback (에러 각 분기)
  → 200 html, location.replace(frontendOrigin/auth/return?error=...)  ※ Set-Cookie 없음, 이전엔 302였음

POST /api/auth/exchange { code }
  → authExchangeCodeStore.consume(code) — Redis RBucket.getAndDelete(), 원자적 read-and-remove
  → 없거나 이미 소비됨 → 401 UnauthorizedException
  → studentService.findById(studentId) 없으면 → 401
  → jwtProvider.issueAccess / issueRefresh
  → Set-Cookie: ssuai_refresh=... (refresh() / logout()과 동일한 buildRefreshCookie 헬퍼 재사용)
  → 200 { accessToken, accessTtlSeconds }
```

`CsrfOriginGuardFilter`는 이미 `/api/auth/*` 아래 모든 상태 변경 메서드를 커버하므로 `/api/auth/exchange`는 별도 예외 처리 없이 자동으로 Origin 검증 대상이 된다 — 프론트엔드는 Vercel rewrite를 통한 same-origin 호출이라 허용 목록의 Origin이 그대로 통과한다(오늘의 `/api/auth/refresh`와 동일).

`exchange()`는 `refresh()`처럼 별도의 재사용 방지 denylist를 두지 않는다 — exchange code 자체가 이미 `consume`으로 엄격한 단일 사용이 보장되고, refresh 토큰 쪽의 denylist는 오히려 과거 로그인 장애의 원인이었기 때문에(코드 주석 참조) 의도적으로 추가하지 않았다.

## 결과와 한계

성공 케이스든 에러 케이스든 SSO 콜백 응답이 다시는 쿠키를 나르지 않으므로, 프론트엔드 프록시나 Traefik, Vercel rewrite 등 그 사이 어떤 레이어가 redirect/캐시를 어떻게 재해석해도 로그인 쿠키 전달에 영향을 줄 수 없다. 실제 쿠키 전달은 도서관 로그인에서 이미 검증된 "순수 POST 200 응답" 경로로 옮겨졌다.

한계: exchange code가 브라우저 히스토리/서버 로그에 짧게라도 노출되는 것은 여전하다 — 120초 TTL과 단일 사용으로 노출 창을 최소화했지만 제로는 아니다. 또한 Redis 장애 시 이 저장소는 fail-closed이므로, Redis가 완전히 죽으면 로그인 자체가 막힌다(다른 fail-open 컴포넌트와 다른 트레이드오프이며, 위 "결정" 절에서 의도적으로 선택한 것).
