# PlayMCP runtime profile

## Service boundary

The `playmcp` Spring profile exposes a bounded set of private campus tools for the SSU Campus
external MCP endpoint. It is not a general campus-search surface: authenticated tools read only
the current user's university data, and a real reservation always requires an explicit final
confirmation.

## Tool surface

The profile exposes these nine tools:

| Tool | Behavior | Side effect |
| --- | --- | --- |
| `start_auth` | Starts a user-authorized u-SAINT, LMS, or library login | Creates or binds a session |
| `logout_all` | Revokes all linked providers for the current session | Deletes credentials and session state |
| `get_my_schedule` | Retrieves the user's u-SAINT timetable | None |
| `get_my_grades` | Retrieves the user's grade and GPA history | None |
| `get_my_assignments` | Retrieves outstanding LMS assignments and quizzes | None |
| `get_lms_dashboard` | Summarizes LMS deadlines, notices, and calendar items | None |
| `recommend_library_seats` | Ranks live available library seats by preference | None |
| `prepare_reserve_library_seat` | Validates and prepares one seat reservation | Creates a pending action only |
| `confirm_action` | Executes one prepared reservation after confirmation | Reserves a seat |

The reservation flow remains `recommend_library_seats` → `prepare_reserve_library_seat` →
`confirm_action`. Natural-language intent alone never authorizes the final action.

## Data and ownership

- u-SAINT, LMS, and library data is fetched only after the user completes the provider's login flow.
- The service does not request resident-registration numbers, payment-card details, bank-account
  details, passports, or credentials unrelated to the selected provider.
- Credentials and session identifiers are never returned in logs or tool results. `logout_all`
  revokes every provider linked to the current session.
- SSU Campus is an independent service and does not claim institutional affiliation.

## Deployment settings

Run the container on port `8080` and inject configuration through the deployment platform's
environment-variable and secret controls. Never bake secret values into the image or repository.

| Name | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `playmcp` |
| `SSUAI_MCP_API_BASE_URL` | Public HTTPS origin for this server |
| `SSUAI_API_BASE_URL` | Public HTTPS origin for this server |
| `SSUAI_CREDENTIAL_ENCRYPTION_KEY` | Newly generated high-entropy secret stored by the platform |

The external MCP endpoint is the public origin with the `/mcp` suffix. `application-playmcp.yml`
enables only the connectors required by the nine tools and disables the unused self-MCP client.
Use an external database for durable session and pending-action state; the in-memory fallback is
appropriate only for a local smoke test and loses active sessions on restart.

The server preserves the MCP transport session through `Mcp-Session-Id` and supports an opt-in
OAuth 2.1 JWT resource-server mode. Do not enable `SSUAI_OAUTH_RS_ENABLED=true` without a compatible
issuer, audience, and registered redirect URI.
