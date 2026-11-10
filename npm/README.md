# ssumcp

숭실대학교 캠퍼스 데이터를 MCP(Model Context Protocol) 도구로 제공하는 비공식 서버
[ssuMCP](https://github.com/ghdtjdwn/ssuMCP)의 로컬 실행 런처다. 학교 공식 서비스가 아니다.

`npx ssumcp`는 GitHub Release에서 같은 버전의 JAR을 받아 `~/.ssumcp/`에 캐시하고
`http://localhost:8080/mcp`로 띄운다.

[![CI](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/ci.yml)

## 먼저 알아둘 것

- 이 문서는 런처 **0.2.0** 기준이다. `npx ssumcp`는 설치된 런처와 같은 버전의 JAR을 받으므로
  `npm view ssumcp version`이 0.2.0인지 확인한다. 0.1.1 런처는 구버전 서버(도구 23개, Redis 플래그 없음)를 띄운다.
- **Java 21 이상**이 필요하다 ([Temurin](https://adoptium.net)).
- 기본 설정은 `localhost:6379`의 **Redis**를 기대한다. 없으면 `SSUAI_LIBRARY_REDIS_ENABLED=false`로
  실행한다(좌석 SSE fan-out과 분산 lock이 in-process/noop으로 동작).
- 로컬 실행의 connector는 전부 **결정적인 mock**이라 학식·좌석·공지 응답이 실제 데이터가 아니다.
  실데이터가 필요하면 원격 endpoint `https://ssumcp.duckdns.org/mcp`를 등록한다.

## 빠른 시작

```bash
# Redis가 있을 때
npx ssumcp

# Redis 없이
SSUAI_LIBRARY_REDIS_ENABLED=false npx ssumcp

# 포트 변경
PORT=18080 npx ssumcp
```

기동 뒤 `http://localhost:8080/actuator/health`가 `{"status":"UP"}`이면 준비된 것이다.

## MCP 클라이언트 연결

원격 서버(설치 없이, 실데이터):

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "https://ssumcp.duckdns.org/mcp"
    }
  }
}
```

로컬 서버(`npx ssumcp` 실행 중):

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Claude Desktop 설정 파일 위치:

- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`

## 도구

52개 도구(공개 21 · 인증 관리 4 · 개인 27)의 전체 목록과 인자, 인증 흐름은 저장소의
[docs/mcp-tools.md](https://github.com/ghdtjdwn/ssuMCP/blob/main/docs/mcp-tools.md)와 서버가 직접
제공하는 [`/.well-known/mcp/server-card.json`](https://ssumcp.duckdns.org/.well-known/mcp/server-card.json)에
있다.

- 공개 도구(인증 불필요): 학식·기숙사 식단, 캠퍼스 시설, 도서 검색, 좌석 카탈로그와 층별 좌석 집계
  (`get_library_seat_status`), 학사일정, 학칙·졸업·장학 근거 검색, 공지사항.
- 인증 관리: `start_auth`, `get_auth_status`, `logout_provider`, `logout_all`.
- 개인 도구(u-SAINT · LMS · 도서관 로그인): 시간표·성적·졸업요건·장학금, LMS 과제·자료 내보내기,
  좌석 실시간 조회·추천·예약·대기·반납. 로그인 전에는 `AUTH_REQUIRED`와 로그인 URL을 돌려주고,
  예약·반납·내보내기는 `prepare_* → confirm_action` 두 단계로만 실행된다.

인증 흐름:

1. `start_auth(provider="SAINT")` 호출 → `loginUrl`과 `mcpSessionId` 수신
2. `loginUrl`을 브라우저에서 열어 숭실대 계정으로 로그인
3. 이후 개인 도구 호출 시 `mcp_session_id` 파라미터 전달

## 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PORT` | `8080` | 서버 포트 |
| `SSUAI_LIBRARY_REDIS_ENABLED` | `true` | `false`면 Redis 없이 기동 |

나머지 설정은 저장소의 [`.env.example`](https://github.com/ghdtjdwn/ssuMCP/blob/main/.env.example)을 참고한다.

## 관련 링크

- [GitHub 저장소](https://github.com/ghdtjdwn/ssuMCP)
- [ssuAI 웹 클라이언트](https://github.com/ghdtjdwn/ssuAI)

## 라이선스

MIT
