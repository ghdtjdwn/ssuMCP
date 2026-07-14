# PlayMCP Agentic Player 10 submission profile

## Product

**Display name:** SSU Campus

**Short description:**

> SSU Campus(숭실대학교 캠퍼스)는 학생 본인이 u-SAINT, LMS, 중앙도서관 계정을 연동한 뒤 시간표·성적·미제출 과제·실시간 도서관 좌석을 조회하고, 명시적 최종 확인 후 좌석 예약까지 처리하는 MCP입니다.

The product is intentionally not a general campus-search server. Its value is access to the
student's own authenticated university data and the safety boundary around a real reservation.

## Contest tool surface

Activate the `playmcp` profile. It exposes exactly nine tools, within PlayMCP's recommended
3–10 tool range.

| Tool | Value | Side effect |
| --- | --- | --- |
| `start_auth` | Starts a user-authorized u-SAINT, LMS, or library login | Creates/binds a session |
| `logout_all` | Revokes all linked providers for the current session | Deletes credentials/session |
| `get_my_schedule` | Retrieves the student's u-SAINT timetable | None |
| `get_my_grades` | Retrieves the student's own grade and GPA history | None |
| `get_my_assignments` | Retrieves outstanding LMS assignments and quizzes | None |
| `get_lms_dashboard` | Summarizes LMS deadlines, notices, and calendar items | None |
| `recommend_library_seats` | Ranks live available library seats by preference | None |
| `prepare_reserve_library_seat` | Validates and prepares a selected seat reservation | Creates a pending action only |
| `confirm_action` | Executes one prepared reservation after the user confirms | Reserves a seat |

The reservation flow must remain `recommend_library_seats` →
`prepare_reserve_library_seat` → `confirm_action`. The final action is never inferred from
natural-language intent alone.

## Data, privacy, and ownership statement

- u-SAINT, LMS, and library data is fetched only after the individual user completes the
  provider's own login flow.
- The service must not request resident-registration numbers, payment-card details, bank
  account details, passports, or credentials unrelated to the chosen provider.
- Credentials and session identifiers are never returned in logs or tool results. The user can
  revoke every linked provider with `logout_all`.
- The registered description must say that SSU Campus is an independent service; do not claim
  affiliation with Soongsil University or Kakao without written authorization.
- For review questions, describe the data source as the user's own authenticated account and
  the relevant university system. Do not represent it as licensed institutional data.

## PlayMCP in KC deployment settings

Set the container port to `8080` and inject these values through KC's environment-variable and
secret UI. Do not bake secret values into the Docker image or Git repository.

| Name | KC value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `playmcp` |
| `SSUAI_MCP_API_BASE_URL` | `https://<mcp-name>.playmcp-endpoint.kakaocloud.io` |
| `SSUAI_API_BASE_URL` | `https://<mcp-name>.playmcp-endpoint.kakaocloud.io` |
| `SSUAI_CREDENTIAL_ENCRYPTION_KEY` | A newly generated high-entropy secret, stored only in KC Secrets |

Use the KC-issued endpoint with the `/mcp` suffix when registering in PlayMCP:

```text
https://<mcp-name>.playmcp-endpoint.kakaocloud.io/mcp
```

`application-playmcp.yml` enables only the real connectors required by the nine tools and
disables the server's unused self-MCP client. An external database is strongly recommended for
durable session and pending-action state; the in-memory fallback is suitable only for a local
smoke test and loses active sessions on a container restart.

## Pre-submission gate

1. Build and run the full test/coverage gate.
2. Deploy the `playmcp` profile to KC.
3. Verify the KC endpoint with MCP Inspector: initialize, list exactly nine tools, call every
   read tool with a test account, and test the reservation flow through **prepare only** unless
   the account owner explicitly authorizes a real reservation.
4. In PlayMCP Developer Console, use **temporary registration**, fetch tool information, and
   test in the provided AI chat before requesting review.
5. Request review only after every tool has a bounded response and the production endpoint is
   stable. After approval, change visibility to **public**, copy the MCP detail-page URL, and
   submit that URL once through the competition form.

## OAuth/custom-header review requirement

The server already preserves the MCP transport session through the `Mcp-Session-Id` header and
has an opt-in standard OAuth 2.1 JWT resource-server mode. Before final submission, verify in
the PlayMCP console whether its private-tool flow accepts the existing transport session or
requires a configured OAuth issuer. Do not enable `SSUAI_OAUTH_RS_ENABLED=true` without a
compatible JWT issuer, audience, and registered redirect URI.
