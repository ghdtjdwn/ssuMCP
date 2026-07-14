# 2026-07-14 Live-tool audit remediation record

## Scope and evidence boundary

This record tracks remediation of a live MCP audit across all 52 registered tools. The audit
exercised representative read paths and LMS export preview/confirmation. It did **not** perform
real library reserve, wait, swap, or cancellation against a real account. Those write paths are
covered only with mocked connectors and the isolated test database.

No session identifiers, cookies, credentials, student data, export tokens, or raw download URLs
are recorded here or in fixtures. This document is a current implementation record, not a
substitute for a production change approval or real-account test.

## Release-blocking finding: explicit session isolation

### Root cause

Session lookup was not authoritative. Some paths correctly handled explicit session IDs, while
ordinary private-tool failure handling could resolve again in an authentication-rebinding context.
That permitted an explicit invalid or mismatched ID to be replaced by a transport-bound session.
The bug affected both personal reads and owner-scoped state such as LMS export confirmation.

### Fixed contract

`McpSessionResolver` is now the sole resolver for ordinary MCP/REST operations:

1. A nonblank explicit ID is looked up exactly. Missing, expired, or invalidated IDs return
   `INVALID_SESSION`; no transport or OAuth fallback is allowed.
2. An explicit valid ID different from the current transport binding returns `SESSION_MISMATCH`.
3. An omitted ID may use only a valid binding for the current MCP transport; otherwise it returns
   `NO_SESSION`.
4. Only `start_auth` and trusted authentication callbacks may establish/rebind a transport
   binding. `AUTH_REQUIRED` construction is not an authorisation escape hatch.
5. Denial responses do not return a resolved session ID, login URL, or private data.

The complete rationale, alternatives, and operational limit are in
[ADR 0098](../adr/0098-authoritative-mcp-session-resolution.md).

## Owner-scoped state and credential safety

| State | Enforced owner boundary |
| --- | --- |
| Provider credentials/cookies | `(mcpSessionId, provider)` with versioned durable state |
| `ActionAudit` and library prepare/confirm | exact MCP session and provider-session key under row lock |
| Library wait intent and SSE/status/cancel | exact MCP session; foreign and unknown IDs have the same non-disclosing result |
| LMS preview, export job, download capability | exact MCP session; preview is consumed/versioned and capability is expiry-bound |
| Logout/unlink vs destructive write | credential-version fence prevents an in-flight write from inheriting revoked credentials |

No-op action states are explicit: `NO_CURRENT_SEAT`, `NO_PENDING_ACTION`, and
`ACTION_CONFLICT` replace fabricated IDs or `OK`-only prose. Private responses expose `status`,
`code`, `provider`, `userMessage`, `developerMessage`, `retryable`, and correlation ID while
retaining compatibility fields during the transition.

## Provider-session reliability

SAINT and LMS use one canonical provider session per MCP owner/provider, with durable cookie
state, response `Set-Cookie` merge/deletion handling, origin-scoped LMS cookies, and monotonic
credential versions. Atomic/CAS persistence prevents an older response from overwriting a newer
cookie jar. Connector failures distinguish authentication expiry from unavailable upstream,
protocol/parser changes, and network errors. Provider status retains `linked` and adds
`health`, validation/success/failure timestamps, `failureCode`, and `credentialVersion`.

The remaining upstream uncertainty is limited to external write acknowledgement: if an upstream
write succeeds but the following local terminal-state persistence fails, an upstream API without
an idempotency key cannot provide absolute certainty. Action audit, worker recovery, and manual
status verification are the mitigation.

## Behavioural corrections included in this audit

- LMS: deterministic regular-term-first selection, selected-term metadata, null-deadline handling,
  owner-scoped idempotent export previews/jobs/capabilities.
- SAINT: deduplicated schedule meetings, nullable unmapped period, explicit graduation gate state,
  GPA input validation, shared provider state for chapel/scholarship paths.
- Library: physical/active/inactive counts reconciled, corrected B1/Maru catalog mapping, honest
  preference coverage/warnings, compact/paginated seat responses, mocked write-flow coverage.
- Public data: ISO date compatibility fields, dorm notice-vs-meal classification, notice
  attachment/completeness metadata, stricter validation, facility freshness, and book validation.
- Policy/RAG: unambiguous cache/live metadata, relevance boosts, structured scholarship tiers,
  bilingual intent support, and answer-oriented policy briefs.

## Automated regression evidence

| Area | Evidence |
| --- | --- |
| 52-tool contract inventory | `McpToolContractInventoryTests` verifies callback count, schema parameters, auth/provider/read-write classification, validation/empty state, isolation, ownership, idempotency, and response-size metadata |
| P0 MCP boundary | `McpSelfDogfoodTests` sends no binding, random explicit, invalidated explicit, and valid-but-different explicit IDs through 28 private tools; denial responses are checked for no real session disclosure |
| Action/export ownership | tool/service tests cover foreign action/export confirmation, wait status/cancel isolation, duplicate/superseded actions, and idempotent retry behavior |
| Provider concurrency | durable SAINT/LMS cookie-store tests cover CAS/version ordering, non-conflicting cookie merges, and stale overwrite prevention |
| Parser/domain/RAG | fixture and service tests cover schedule, graduation, GPA, library reconciliation, meal/notice parsing, term selection, retrieval, and scholarship boundaries |

Latest local verification after this remediation:

```text
./gradlew cleanTest test --no-daemon
./gradlew build --no-daemon
```

Both completed successfully; the packaging gate includes JaCoCo verification. CI must still run
the Docker-enabled integration subset before a deployment decision.

## Safe manual smoke checklist

1. Start the server with deterministic/mock connectors or explicit live-smoke configuration.
2. Call `get_auth_status` without an ID and confirm `NO_SESSION` when no binding exists.
3. Call `start_auth` and complete only the intended provider login.
4. Verify `get_auth_status` reports the selected provider linkage and health fields.
5. Exercise private **read-only** SAINT, LMS, and library tools with the returned ID.
6. Verify a random or invalidated explicit ID returns `INVALID_SESSION`, and a different valid ID
   with a bound transport returns `SESSION_MISMATCH`; neither response may reveal another ID.
7. Optionally create an LMS export preview and inspect its metadata. Do not confirm it unless the
   environment is explicitly authorised for that side effect.
8. Do not call any real library prepare/confirm, wait registration, swap, or cancellation in
   production. Use the mocked integration suite for those paths.

## Documentation relationships

- Current MCP contract: [mcp-tools.md](../mcp-tools.md)
- Security controls and follow-up register: [security.md](../security.md),
  [security-followups.md](../security-followups.md)
- Runtime ownership boundaries: [architecture.md](../architecture.md)
- Curated incident narrative: [troubleshooting-highlights.md](../troubleshooting-highlights.md)
- Private diagnostic record: repository-local `TROUBLESHOOTING.md` (intentionally untracked)
