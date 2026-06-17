# ADR 0036 — MCP 인증 견고화: opt-in 2모드 (transport 바인딩 + OAuth 2.1 RS)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-17 |
| 상태 | Accepted |
| 연관 ADR | [ADR 0014](0014-saint-sso-redirect-callback.md) (SAINT SSO), [ADR 0019](0019-mcp-stateless-spec-2026-07-28.md) (MCP stateless), [ADR 0031](0031-mcp-web-session.md) (웹 세션 브릿지) |
| PR | #73 |

---

## 배경 — 무엇이 문제인가

### 진짜 원인: opaque id는 LLM이 들고 다닌다

ssuMCP의 사적 도구(도서관 좌석, 시간표, 성적 등)는 `mcp_session_id`(opaque UUID)를 LLM 인자로 넘겨 받아 세션을 식별한다. 이 방식은 **LLM이 id를 계속 기억하고 있을 때만** 동작한다.

**ChatGPT의 실제 거동**: 턴 경계(대화 새 메시지)에서 이전 도구 호출 결과를 도구 인자로 재전달하지 않는 경우가 있다. 결과:
1. 도구 A 호출 → `mcp_session_id = "session-X"` 발급, 로그인 완료.
2. 다음 턴에서 도구 B 호출 → `mcp_session_id = null` (dropped).
3. 서버는 새 세션 "session-Y"를 발급 → SAINT/LIBRARY 미연결 → AUTH_REQUIRED.
4. 사용자는 또 로그인하지만 다음 턴에서 또 드랍 → **무한 루프**.

이는 서버 버그가 아니라 클라이언트 거동 차이다. 같은 API를 Claude Desktop은 세션을 잘 유지한다.

### 선행 탐색 (스파이크, 2026-06-16)

두 가지 우회를 스파이크 브랜치에서 탐색했다:

| 접근 | 결과 | 판단 |
|---|---|---|
| `spike/elicitation-diagnostics`: ChatGPT elicitation API로 대화 간 `session_id_prefix` 유지 확인 | ChatGPT elicitation 지원 여부 미검증 — 실측 필요(G1 게이트) | 보조 수단으로 보존, 주 해결책으로 불충분 |
| `spike/oauth-round-trip`: Auth0 OAuth 2.1 RS로 전면 인증 강제 | 공개 도구까지 인증 필요화 → ADR 0004 "공개 도구 무인증" 원칙 위반 + stale audience 미검증 | 기각; 구조 재설계 필요 |

---

## 결정 — opt-in 2모드

스파이크 결과와 웹 검색(Spring Security OAuth 2.1, MCP spec 2026, RFC 9728) 기반으로 두 레이어를 결합한 설계를 채택한다.

### 모드 A: 기본 모드 (transport 바인딩, rs-enabled=false)

**원리**: MCP 서버가 HTTP 연결마다 `Mcp-Session-Id` 헤더를 발급한다. LLM이 opaque id를 떨어뜨려도 HTTP 계층의 transport id는 서버가 관리하므로 안정적이다.

```
start_auth 호출
  └─> 세션 생성/재사용 + transport_session_id 바인딩 (DB 저장)

사적 도구 호출 (mcp_session_id=null — LLM이 드랍)
  └─> 3-tier 해석:
      1. OAuth sub (null — 기본 모드)
      2. transport id (Mcp-Session-Id 헤더) → 세션 발견 ✓
      3. opaque mcp_session_id (스킵)
```

**보안**: transport id는 서버가 연결에 직접 귀속 → 다른 사용자의 연결로 하이재킹 불가. (opaque id 병합이 위험했던 것과 다름 — 그건 LLM 인자를 그냥 믿는 방식이었음.)

**G1 의존성**: ChatGPT가 실제로 `Mcp-Session-Id`를 턴 간 유지하는지 실측이 필요하다 (동일 연결 재사용 vs. 매 턴 재연결). 코드는 완성됐으나 prod 활성은 G1 스모크 후 확정.

### 모드 B: opt-in OAuth 모드 (rs-enabled=true)

**원리**: Bearer JWT의 `sub`(OAuth subject)는 HTTP 계층 정보 → LLM이 절대 떨어뜨릴 수 없다. 한번 Google 로그인만 하면 이후 대화에서 sub로 동일인 인식 → 학교 재로그인 0(세션 TTL 내).

```
GET /mcp + Authorization: Bearer <JWT>
  └─> BearerTokenAuthenticationFilter: JWT 검증 → SecurityContext 채움
      (토큰 없으면 anonymous → 기존 모드 그대로)

사적 도구 호출 (mcp_session_id=null)
  └─> McpAuthHelper.resolveSession():
      1. SecurityContextHolder.getAuthentication() → JwtAuthenticationToken.getName() = sub
      2. findByOauthSubject(sub) → 세션 발견 ✓
```

**비번 금고 없음**: 학교 connector 토큰(SaintSessionStore 등) 만료 시 1회 재로그인. u-SAINT 비번은 영구 미저장. 이는 기존 원칙(ADR 0014) 유지.

**`permitAll()` 유지**: 공개 도구는 여전히 무인증. `BearerTokenAuthenticationFilter`는 authorization 규칙과 독립적으로 동작 — 토큰 있으면 검증·SecurityContext 채움, 없으면 anonymous 통과, 만료/무효면 401. 이것이 "opt-in 공존"의 핵심이다.

---

## 기각한 대안

### 대안 1: OAuth 전면 강제 (스파이크 방식)

```java
.requestMatchers("/mcp", "/mcp/**").authenticated() // ← 기각
```

- 공개 도구(학식, 도서 검색, 공지)가 모두 인증 필요화 → 처음 쓰는 사람이 OAuth 등록 전에 아무것도 못 씀.
- ADR 0004 "공개 도구 무인증" 원칙 위반.
- **기각**.

### 대안 2: 학교 비번 금고

서버에 암호화한 u-SAINT 비번 저장 → 세션 만료 시 자동 재로그인. 학교 인증 흐름 자동화.

- 학교 비번을 서버에 두는 순간 **유출 시 학교 계정 전체 탈취** 위험.
- 교내 SSO는 2FA 없음 → 비번만으로 성적/장학금 등 민감정보 접근 가능.
- 관련 규정(개인정보보호법) 위반 소지.
- **기각. 영구 원칙: u-SAINT/LMS 비번 서버 미저장.**

### 대안 3: 커뮤니티 `org.springaicommunity:mcp-server-security`

Spring AI 커뮤니티의 MCP 보안 모듈. `@PreAuthorize` 기반 403 강제.

- 사적 도구가 403 대신 `AUTH_REQUIRED + loginUrl`로 우아하게 처리하는 기존 UX 파괴.
- 아직 미승인(incubating), Spring AI 정식 로드맵 아님.
- plain `spring-boot-starter-oauth2-resource-server` 직접 설정이 더 투명하고 면접 설명 용이.
- **기각**.

### 대안 4: 신규 `OAuthSessionStore` 분리

OAuth 세션 전용 별도 엔티티/스토어.

- `McpAuthSession`의 수명주기·만료필터·cleanup이 이미 있으므로 중복 인프라.
- 두 세션 동기화 문제 추가.
- **기각**. 기존 `McpSessionEntity`에 `oauthSubject` 컬럼 추가로 재사용.

---

## 구현 핵심

### @ConditionalOnProperty 제거 (Spring Boot 안전성)

`spring-boot-starter-oauth2-resource-server`를 classpath에 추가하면 Spring Boot는 커스텀 `SecurityFilterChain` 빈이 없으면 기본 체인으로 모든 엔드포인트를 잠근다. 클래스 레벨 `@ConditionalOnProperty(rs-enabled=true)`를 걸면 `rs-enabled=false` 시 빈이 아예 안 뜨므로 기본 체인이 MCP 포함 전체를 잠근다.

**해결**: `@ConditionalOnProperty` 제거 → SecurityFilterChain **항상 로드**. JWT RS 설정만 내부에서 `if (rsEnabled && !issuerUri.isBlank())` 조건부 적용.

### 3-tier 우선순위

```java
// McpAuthHelper.resolveSession()
// Tier 1: OAuth sub (JwtAuthenticationToken.getName())
// Tier 2: Mcp-Session-Id 헤더 → findByTransportId()
// Tier 3: LLM 인자 mcp_session_id → find()
```

각 tier 성공 시 하위 tier에 대한 opportunistic 바인딩:
- Tier 2 성공 + oauthSub 있으면 → `bindOauthSubject()`
- Tier 3 성공 + oauthSub/transportId 있으면 → 각각 bind

### V12 마이그레이션 (PostgreSQL / H2 방언 분기)

실제 배포 SQL은 `mcp_sessions` 테이블에 컬럼 2개와 인덱스 2개를 추가한다. PostgreSQL은 partial index를 지원하지만 H2(테스트)는 미지원이므로 Flyway 디렉토리를 분기한다.

```sql
-- postgresql/V12__add_mcp_session_transport_oauth.sql
ALTER TABLE mcp_sessions ADD COLUMN transport_session_id VARCHAR(128);
ALTER TABLE mcp_sessions ADD COLUMN oauth_subject       VARCHAR(255);

CREATE INDEX idx_mcp_sessions_transport
    ON mcp_sessions(transport_session_id)
    WHERE transport_session_id IS NOT NULL;

CREATE INDEX idx_mcp_sessions_oauth_subject
    ON mcp_sessions(oauth_subject)
    WHERE oauth_subject IS NOT NULL;

-- h2/V12__add_mcp_session_transport_oauth.sql
-- H2는 partial index(WHERE 절) 미지원 → 표준 인덱스로 대체
CREATE INDEX idx_mcp_sessions_transport     ON mcp_sessions(transport_session_id);
CREATE INDEX idx_mcp_sessions_oauth_subject ON mcp_sessions(oauth_subject);
```

기존 행은 두 컬럼 모두 NULL → 3-tier 중 Tier 3(opaque mcp_session_id) 경로만 동작 → **하위 호환 보장**.

---

## audience 검증 강제

스파이크 버전은 audience 미검증 → 다른 RS용 토큰도 수락 가능한 보안 취약점. 이번 구현에서:

```java
OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
    "aud", aud -> aud != null && aud.contains(audience)
);
```

issuer validator와 결합. audience 불일치 → 401.

### OAuth 2.1 / MCP RFC 스택

| RFC | 역할 |
|---|---|
| RFC 9728 | Protected Resource Metadata — `/.well-known/oauth-protected-resource` 노출, OAuth 클라이언트의 AS 자동 발견 |
| RFC 8414 | Authorization Server Metadata — Auth0 메타데이터 자동 발견 |
| RFC 7591 | Dynamic Client Registration — ChatGPT가 Auth0에 자동 등록 |
| RFC 8707 | Resource Indicators — audience 바인딩 |

---

## 운영 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `SSUAI_OAUTH_RS_ENABLED` | `false` | true 시 OAuth 모드 활성 |
| `SSUAI_OAUTH_ISSUER_URI` | `` | Auth0 테넌트 issuer URI |
| `SSUAI_OAUTH_AUDIENCE` | `` | API audience (e.g. `https://ssumcp.duckdns.org`) |
| `SSUAI_OAUTH_RESOURCE_BASE_URL` | `https://ssumcp.duckdns.org` | PRM URL 생성 기준 |

**기본값(false)에서 기존 동작 100% 보존**. OAuth 활성화는 G2(Auth0 테넌트 생성) + G3(prod env confirm) 이후.

---

## 검증 계획

| 게이트 | 방법 | 담당 |
|---|---|---|
| G1 | prod에서 ChatGPT `check_elicitation_support` 2턴 호출 → `session_id_prefix` 동일 확인 | 사용자 |
| G2 | Auth0 테넌트 + Google social connection + DCR 생성 | 사용자 |
| G3 | prod env-var 추가 confirm | 사용자 |
| G4 | ChatGPT OAuth 연결 → `sub` 안정성 + 새 대화 재로그인 없음 확인 | 사용자 |

CI 단위 테스트: transport/oauth 바인딩 통합 테스트 6개, 3-tier resolution 유닛 테스트 2개 포함. BUILD SUCCESSFUL 확인.

---

## 포트폴리오 포인트

- **OAuth 2.1 Resource Server** — 2026년 취업 키워드. MCP spec과 RFC 9728 PRM을 실제 구현.
- **3-tier graceful degradation** — 클라이언트 거동 차이에 투명하게 대응, 사용자에게 보이지 않음.
- **opt-in 공존 패턴** — `permitAll()` + BearerTokenAuthenticationFilter 조합으로 하위호환성 유지. Spring Security 내부 동작 이해 없이 못 하는 설계.
- **partial index / H2 호환** — 테스트(H2)와 prod(PostgreSQL)의 SQL 방언 차이를 Flyway 디렉토리 분기로 처리.
- **보안 원칙 일관성** — 비번 금고 기각. "신원 고정만, 비밀번호 없음" 설계를 시종일관 유지.

---

## 예상 면접 질문

1. **"JWT를 검증하지만 `permitAll()`이면 인증 안 된 거 아닌가요?"**
   Spring Security의 `BearerTokenAuthenticationFilter`는 authorization 규칙(`.authenticated()` vs `permitAll()`)과 독립적으로 동작합니다. 토큰이 있으면 검증해서 SecurityContext를 채우고, 없으면 그냥 지나갑니다. `permitAll()`은 "토큰 없어도 접근 허용"이 아니라 "이 경로에 401/403 강제 안 함"을 의미합니다. 따라서 토큰 있으면 sub를 읽을 수 있고, 없으면 anonymous로 처리됩니다.

2. **"transport session id가 ChatGPT 턴 간 안정적이라는 보장이 있나요?"**
   아키텍처상 HTTP 연결 수명과 `Mcp-Session-Id` 발급 방식에 따라 안정 여부가 달라집니다. ChatGPT가 매 턴 MCP 연결을 재-initialize하면 매번 새 id가 발급되어 불안정합니다. 이를 G1 스모크 테스트(실제 ChatGPT에서 2턴 호출해 `session_id_prefix` 비교)로 검증한 후 기능을 활성화합니다. 실패 시 기본 모드 ChatGPT 수정은 포기하고 OAuth 모드로 유도합니다.

3. **"학교 비번을 저장하면 UX가 훨씬 좋아지는데 왜 안 하나요?"**
   세 가지 이유입니다. ① u-SAINT 비번을 비공식 DuckDNS 서버에서 입력받는 것은 피싱과 구조가 동일합니다. ② 유출 시 접속 학생 전원의 학적 정보가 위험합니다. ③ 실제로는 connector 토큰 TTL(7일) 안에서는 재로그인이 없으므로 "매번 로그인"이 아닙니다. 비번 저장 없이도 OAuth `sub`로 신원을 고정하면 새 대화에서도 세션이 살아있는 한 학교 재로그인이 없습니다.

4. **"왜 Auth0인가요? 직접 Google OAuth를 쓰면 안 되나요?"**
   Google은 자체 Dynamic Client Registration(DCR, RFC 7591)을 지원하지 않습니다. MCP 스펙 OAuth 2.1은 클라이언트(ChatGPT 등)가 AS에 DCR로 자동 등록하는 것을 전제합니다. Auth0는 Google을 social connection으로 upstream에 두면서 DCR과 OIDC Discovery를 모두 제공하는 관리형 AS입니다. Auth0 무료 티어로 충분히 운영 가능합니다.
