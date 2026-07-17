# ssuMCP

[![CI](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/ci.yml)
[![Security](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/security.yml/badge.svg)](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/security.yml)

[한국어](README.md) · **English**

A Spring Boot backend that exposes public and authenticated Soongsil University data through
52 MCP tools and REST APIs. It owns the boundaries around university-system integrations,
per-user authentication state, action-specific consent, failure isolation, and production telemetry.

[Live web app](https://ssuai.vercel.app) · [Server health](https://ssumcp.duckdns.org/actuator/health) ·
[Platform case study](https://seongju.vercel.app/en/projects/ssu-platform/) · [Documentation map](docs/README.md)

## Role in the platform

| Service | Responsibility | Repository |
| --- | --- | --- |
| ssuAI | User interface, same-origin BFF, and SSE chat UX | [ghdtjdwn/ssuAI](https://github.com/ghdtjdwn/ssuAI) |
| ssuAgent | LangGraph routing, conversation state, and HITL orchestration | [ghdtjdwn/ssuAgent](https://github.com/ghdtjdwn/ssuAgent) |
| **ssuMCP** | **Campus domain logic, MCP/REST contracts, authentication, and state changes** | This repository |
| ssu-ai-service | Isolated embedding-request gateway | [ghdtjdwn/ssu-ai-service](https://github.com/ghdtjdwn/ssu-ai-service) |

`ssuMCP` owns atomic domain tools and direct university-system integration. `ssuAgent` interprets
natural-language intent and composes tools, while `ssuAI` owns the UI and browser-session boundary.

## Architecture

![ssuMCP service and production architecture showing the shared service layer, state stores, university connectors, GitOps, and observability](docs/assets/architecture.svg)

REST controllers and MCP `@Tool` adapters call the same service layer. Every external university
system sits behind a `*Connector` interface, with mock implementations enabled by default so the
project builds and tests without network access. See the [architecture document](docs/architecture.md)
for the full runtime, data, and deployment boundaries.

### Core request path

```text
ssuAI REST/BFF or MCP client
  → REST Controller / MCP Tool adapter
  → shared Service layer
  → PostgreSQL · Redis/Redisson · Kafka
  → u-SAINT · LMS · library · university website connectors
```

- Public reads are anonymous; private reads require an OAuth subject or an owned MCP session.
- Seat reservations, swaps, returns, and LMS exports use `prepare_* → confirm_action`, so they do
  not execute before explicit approval. `wait_for_library_seat` treats registration itself as consent
  to an automatic reservation; `cancel_library_wait` immediately performs the explicitly requested
  wait cancellation.
- Reservation intents use PostgreSQL row locks and claim leases as the consistency boundary;
  per-seat Redisson locks reduce duplicate upstream writes.
- Kafka carries production intent-status fan-out. Redis provides caching, shared rate limiting,
  and leader coordination with explicit fail-open or fail-closed behavior at each boundary.

## Engineering evidence

| Problem | Implementation and verification |
| --- | --- |
| Drift between runtime tools and the static server card | [52-tool inventory/schema parity test](src/test/java/com/ssuai/domain/mcp/config/McpToolContractInventoryTests.java) · [live tool audit](docs/audits/2026-07-14-live-tool-hardening.md) |
| Cross-user session confusion or unapproved writes | [authoritative session resolution](docs/adr/0098-authoritative-mcp-session-resolution.md) · [scoped confirm contract](docs/adr/0086-confirm-action-async-and-scoped-supersede.md) |
| External API latency, 429s, and concurrent reservations | [failure scenarios](docs/failure-scenarios.md) · [reservation concurrency integration test](src/test/java/com/ssuai/domain/library/reservation/intent/LibraryReservationIntentConcurrencyIT.java) |
| Traceable retrieval under embedding failure | Lexical + embedding RRF, source metadata, and lexical fallback — [ADR 0020](docs/adr/0020-academic-policy-hybrid-rag.md) |
| Deployment of an unverified image | Multi-arch image publication runs after the test/JaCoCo gate — [CI workflow](.github/workflows/ci.yml) · [GitOps runbook](deploy/README.md) |
| Reproducing and preventing operational failures | [Troubleshooting highlights](docs/troubleshooting-highlights.md) · [load experiment](docs/performance/library-agent-load-test.md) |

The main stack is Java 21, Kotlin 2.4, Spring Boot 4.1, Spring AI, PostgreSQL,
Redis/Redisson, Kafka, Resilience4j, Testcontainers, Helm, ArgoCD, Prometheus, Tempo, and Loki.

## Connect

Register the endpoint in any client that supports remote MCP servers.

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "https://ssumcp.duckdns.org/mcp"
    }
  }
}
```

See the [MCP tools and authentication guide](docs/mcp-tools.md) for Claude Desktop, Cursor, and
client-specific authentication. With Java 21 or newer, the published launcher also supports
self-hosting:

```bash
npx ssumcp
```

<details>
<summary>Real MCP client sessions</summary>

Personal values and active seat identifiers are de-identified in the public images. These captures
show successful sessions, not a guarantee that every external university system is always available.

| Authenticated graduation check | Library reservation after approval |
| --- | --- |
| ![ChatGPT uses ssuMCP to explain remaining graduation requirements](docs/assets/chatgpt-graduation-guidance.png) | ![ChatGPT completes an approved library-seat reservation through ssuMCP](docs/assets/chatgpt-library-seat-reservation.png) |

| LMS export prepared | ZIP downloaded through a short-lived link |
| --- | --- |
| ![ChatGPT prepares an LMS material export](docs/assets/chatgpt-lms-export-ready.png) | ![The exported LMS archive is downloaded in a browser](docs/assets/chatgpt-lms-download.png) |

</details>

## Local development and verification

The default profile uses mock connectors, so no university account or external network is required.

```bash
git clone https://github.com/ghdtjdwn/ssuMCP.git
cd ssuMCP
./gradlew bootRun
```

With Docker available, the suite also runs Testcontainers integration tests against PostgreSQL and
Redis. A locally skipped container test is not reported as a pass; GitHub Actions with Docker is the
authoritative gate.

```bash
./gradlew test
./gradlew test jacocoTestReport jacocoTestCoverageVerification
./gradlew build
```

See [`.env.example`](.env.example) and the [deployment runbook](deploy/README.md) for real connectors
and operations. Never commit real credentials.

## Documentation

- [Documentation map](docs/README.md)
- [MCP tools and authentication contract](docs/mcp-tools.md) (Korean)
- [Security, sessions, and write consistency](docs/security-consistency.md) (Korean)
- [Architecture decision records](docs/adr/) (Korean)
- [Operational troubleshooting highlights](docs/troubleshooting-highlights.md) (Korean)
- [Interview questions with evidence](docs/interview-qa.md) (Korean)

## Scope and limitations

- This is not an official Soongsil University service. Changes to university pages or private APIs
  may temporarily break a connector.
- Private tools require a valid university account and authentication for the relevant provider.
- The public demo and Grafana are portfolio infrastructure and do not provide a commercial SLA.

## License

[MIT](LICENSE)
