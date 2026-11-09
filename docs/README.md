# Server Documentation Map

`ssuMCP/docs/` is the source of truth for the Spring Boot REST/MCP server.

## Active Documents

| Document | Purpose |
| --- | --- |
| [architecture.md](architecture.md) | Current runtime boundaries and package responsibilities |
| [mcp-tools.md](mcp-tools.md) | Tool inventory, auth flow, client setup, and official-source academic policy tools |
| [security.md](security.md) | Data classification, secret handling, and action policy |
| [playmcp-runtime.md](playmcp-runtime.md) | Bounded external MCP tool profile, data boundary, and deployment settings |
| [../deploy/README.md](../deploy/README.md) | Production deployment runbook: single-VM Docker Compose, GHCR `sha-<commit>` images, 15-minute pull timer, public health signals |
| [adr/0101-single-vm-docker-compose-timer-pull.md](adr/0101-single-vm-docker-compose-timer-pull.md) | 2026-10-15 move from k3s/ArgoCD to one VM with Docker Compose and timer-based image pull; supersedes ADR 0008 |
| [runbooks/node-capacity.md](runbooks/node-capacity.md) | Historical (k3s node retired 2026-10-15): disk emergency cleanup and boot volume expansion runbook |
| [troubleshooting-highlights.md](troubleshooting-highlights.md) | Incident and design-correction log, including the 2026-06-06 academic policy RAG refresh decision |
| [troubleshooting/lms-material-metadata-regression.md](troubleshooting/lms-material-metadata-regression.md) | Fixture-reproduced LMS listing/export regression in optional Commons metadata parsing; production cause pending verification |
| [troubleshooting/lms-general-attachment-export.md](troubleshooting/lms-general-attachment-export.md) | Production A/B isolation and fail-soft archive handling for general LMS attachments |
| [troubleshooting/mcp-web-session-credential-copy.md](troubleshooting/mcp-web-session-credential-copy.md) | Production web-session 500 and false provider-link state caused by a missing persistent-copy transaction boundary |
| [audits/2026-07-14-live-tool-hardening.md](audits/2026-07-14-live-tool-hardening.md) | 52-tool live-audit remediation record: scope, fixed contracts, regression evidence, limits, and safe smoke checklist |
| [adr/0099-crash-reconciled-library-actions.md](adr/0099-crash-reconciled-library-actions.md) | Durable MCP action phases and read-after-write reconciliation after process loss |
| [adr/0100-production-security-boundaries.md](adr/0100-production-security-boundaries.md) | Refresh revocation, MCP throttling, internal management port, and mock-free prod startup |
| [security-consistency.md](security-consistency.md) | Session ownership, `prepare_* → confirm_action` write contract, and consistency boundaries |
| [security-followups.md](security-followups.md) | Decision log for post-audit security follow-ups (adopted, rejected, superseded) |
| [failure-scenarios.md](failure-scenarios.md) | Upstream latency, 429, and concurrent-reservation failure modes with the mitigations in place |
| [troubleshooting/cafeteria-connector.md](troubleshooting/cafeteria-connector.md) | Cafeteria connector parsing/availability troubleshooting record |
| [troubleshooting/mcp-sse-concurrency-saturation.md](troubleshooting/mcp-sse-concurrency-saturation.md) | MCP SSE concurrency saturation diagnosis and remediation |

### Performance

| Document | Purpose |
| --- | --- |
| [performance/library-agent-load-test.md](performance/library-agent-load-test.md) | Library agent load experiment: setup, observed numbers, and limits |
| [performance/toolcall-event-pipeline.md](performance/toolcall-event-pipeline.md) | MCP tool-call event pipeline throughput and drop analysis |

### Lab validation

| Document | Purpose |
| --- | --- |
| [cilium-fqdn-egress/RESULTS.md](cilium-fqdn-egress/RESULTS.md) | Cilium FQDN egress policy lab results (not applied to production; see security-followups R1) |

## Historical Records

`adr/` and troubleshooting notes
record decisions made before or during the repository split. They may mention
the former monorepo `backend/` and `frontend/` paths, or the superseded SSE
transport. Current commands use the `ssuMCP` repository root and current MCP
transport is Streamable HTTP `/mcp`.

ADR numbering has gaps on purpose: ADR 0006 and 0009 were written on the ssuAI side before the
monorepo split (0009 is the OpenAI-compatible REST chat path that ADR 0010/0011 cite), and 0082 was
never assigned. `TROUBLESHOOTING.md` at the repository root is intentionally untracked
(`.gitignore`): it is the raw debugging log that may contain personal identifiers; the public summary
is [troubleshooting-highlights.md](troubleshooting-highlights.md).

Product vision, frontend decisions, and completed task specs are maintained in
the separate [ssuAI documentation](https://github.com/ghdtjdwn/ssuAI/tree/main/docs).

## Verification ownership

The executable inventory is `McpToolContractInventoryTests`: it requires all 52 registered
tools to declare authentication, inputs, empty/error behavior, owner scope, side effects,
idempotency, and response-size behavior. `McpSelfDogfoodTests` exercises the private-tool
surface through MCP HTTP, including explicit-session isolation. Documentation describes the
contract; these tests are the release gate for drift.
