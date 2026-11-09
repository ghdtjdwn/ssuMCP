# `deploy/` production deployment runbook

This is the operator guide for ssuMCP's production environment as it runs today
(after the 2026-10-15 cutover, [ADR 0101](../docs/adr/0101-single-vm-docker-compose-timer-pull.md)):

- Spring Boot backend as a **Docker Compose service on a single Oracle Cloud Always Free ARM VM**
  (1 OCPU, about 6 GB RAM, Ubuntu 24.04 aarch64).
- One **Caddy** instance in front of every service on the VM terminates TLS (Let's Encrypt, automatic
  issue and renewal) and reverse-proxies to the backend container.
- Container image built by CI and published to **`ghcr.io/ghdtjdwn/ssumcp`** (public, multi-arch).
- **Pull-based rollout**: a systemd timer on the VM checks GHCR every 15 minutes, swaps the image,
  verifies the public URL and rolls back on failure.
- Next.js frontend on Vercel (`https://ssuai.vercel.app`).
- PostgreSQL 17 + pgvector, Redis 8 and a single-node Kafka (KRaft) run as sibling containers on an
  internal Docker network with no host ports.

The Compose file, systemd units, Caddy site configuration and the autodeploy script are **not in this
repository**; they live in a private infrastructure repository. This repository owns the `Dockerfile`,
the image build in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml), and this runbook.

Until 2026-10-15 the same services ran on a single-node k3s cluster with ArgoCD + Helm. Those manifests
are preserved under [`deploy/archive/k3s/`](archive/k3s/README.md) for reference only; nothing applies
them any more.

Current public endpoints:

- Frontend: `https://ssuai.vercel.app`
- Backend: `https://ssumcp.duckdns.org`
- MCP Streamable HTTP: `https://ssumcp.duckdns.org/mcp` (POST only)

---

## 1. Topology on the VM

```text
Internet ──443──▶ Caddy (TLS, HTTP→HTTPS 308, HSTS/nosniff/no-referrer headers, SSE unbuffered)
                    │  edge network
                    ├──▶ ssuai-backend :8080   (this repository; management :8081 is container-internal only)
                    ├──▶ ssu-agent     :8000   (ghdtjdwn/ssuAgent)
                    └──▶ ssu-ai-service:8000   (ghdtjdwn/ssu-ai-service)
                         │  internal network (no host ports, no egress)
                         ├── postgres (PostgreSQL 17 + pgvector)
                         ├── redis    (volatile)
                         └── kafka    (KRaft, single node)
```

- Only 80/443 (and SSH) are open on the VM. Database, Redis and Kafka are reachable from the backend
  container only.
- The backend starts after PostgreSQL, Redis and Kafka report healthy (`depends_on` with health
  conditions). Every container has `restart: unless-stopped`, a healthcheck and bounded json-file logs.
- Memory budget on the 6 GB VM (container limits): backend 1 GiB with `-Xmx600m` (G1, periodic GC,
  `ExitOnOutOfMemoryError`), Kafka 512 MiB (256 MiB heap), PostgreSQL 512 MiB, Redis 128 MiB,
  Caddy 128 MiB. A 2 GiB swap (`swappiness=10`) is an OOM safety net, and `oom_score_adj` prefers
  killing stateless app containers before the database.
- The backend runs as **one** instance. The multi-instance safeguards in the code (claim leases, Redis
  shared rate limits) are still active but no longer exercised across replicas.

---

## 2. Normal deploy path

```text
merge to main
  → CI "Backend" job: ./gradlew test jacocoTestReport jacocoTestCoverageVerification
  → CI "MCP Server image" job: push ghcr.io/ghdtjdwn/ssumcp:sha-<40-char commit> (+ latest)
  → VM timer (every 15 min): newest main commit that has a sha-<commit> tag on GHCR
  → docker pull → pin image@sha256:<digest> in the service env → docker compose up -d --no-deps --wait
  → public URL check (up to 3 min) → success recorded, or automatic rollback
```

1. **Trigger.** Only pushes to `main` (and manual `workflow_dispatch`) publish an image, and only after
   the test/coverage job passes. Docs-only changes (`docs/**`, `**.md`, `deploy/**`) do not build an
   image, so they never cause a redeploy.
2. **Tag.** The image is tagged `sha-<full commit sha>`; the timer always pins the resolved
   `@sha256:` digest, never a mutable tag.
3. **Timer.** A systemd timer fires every 15 minutes (with a small random delay). The script lists the
   most recent `main` commits through the public GitHub API, walks from the newest commit down until it
   finds one whose `sha-<commit>` tag exists on GHCR, and compares that digest with the pinned one. Same
   digest means no action. The VM holds **no GitHub or registry credentials**; it reads only the public
   repository and the public package.
4. **Swap.** `docker pull`, atomically replace the image variable in the service env file, then
   `docker compose up -d --no-deps --wait --wait-timeout 420 <service>`. A JVM start takes roughly 1–2
   minutes, during which the backend is unavailable (single instance, no rolling update).
5. **Verify.** The script calls the public host through Caddy and requires **both**
   `GET /.well-known/oauth-protected-resource` → 200 and `POST /mcp` `initialize` → 200, retrying every
   10 seconds for up to 3 minutes.
6. **Record or roll back.** Each run appends one JSON line to a deploy-history log (`time`, `service`,
   `result`, `from`, `to`, `note`) and to the journal. `result` is `success`, `rolled_back`, `failed`
   (rollback also failed; manual attention) or `pull_failed`. On a failed verification the previous
   image is restored and verified again. A digest that was rolled back is skipped on later runs until a
   newer commit appears, so one bad image does not loop every 15 minutes.

End to end, a merge to `main` is usually serving within **about 15–20 minutes** (CI build time + timer
interval + JVM start).

### Rollback

- Automatic: step 6 above.
- Manual, preferred: revert the offending commit on `main`. CI publishes a new `sha-<commit>` image
  and the timer picks it up like any other deploy.
- Manual, emergency: pin the previous `image@sha256:` digest in the service env file on the VM and
  run `docker compose up -d --no-deps --wait <service>`; the next timer run will treat the newest GHCR
  digest as a candidate again, so disable the timer first if you need the pin to stick.

### Pausing automatic deploys

The script honours a marker file next to the Compose project: create it to pause automatic deploys,
delete it to resume. A `--dry-run` flag prints what would be deployed without changing anything, and a
lock prevents two runs from overlapping.

---

## 3. Verify production

Actuator is **not** exposed publicly: `https://ssumcp.duckdns.org/actuator/health` and
`/actuator/info` return 404 because Caddy proxies only the application port 8080. The Compose
healthcheck calls `/actuator/health/readiness` on the management port 8081 inside the container.
Use the public signals below instead.

```bash
# Which version is serving (serverInfo.version follows build.gradle `version`)
curl -s https://ssumcp.duckdns.org/.well-known/mcp/server-card.json | jq .serverInfo.version

# MCP initialize must return 200 with the same version in result.serverInfo.version
curl -s -X POST https://ssumcp.duckdns.org/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}' \
  -D - | sed -n '1p;/serverInfo/p'
# Then DELETE /mcp with the returned Mcp-Session-Id header to release the session.

# OAuth protected-resource metadata (also what the autodeploy check uses)
curl -i https://ssumcp.duckdns.org/.well-known/oauth-protected-resource

# REST read through the same service layer
curl https://ssumcp.duckdns.org/api/meals/today | jq .

# Browser-origin CORS check: must not echo Access-Control-Allow-Origin
curl -I -H "Origin: https://attacker.example" https://ssumcp.duckdns.org/api/meals/today

# Expected 404s
curl -i https://ssumcp.duckdns.org/actuator/health
curl -i https://ssumcp.duckdns.org/actuator/prometheus
```

`serverInfo.version` tells you the release line, not the commit. To confirm a specific commit landed,
compare the `sha-<commit>` the CI run published with the `to` field of the latest `success` line in the
deploy-history log on the VM.

---

## 4. When a deploy did not land

Work through these in order; each one is a read-only check.

| Check | Where | What you are looking for |
| --- | --- | --- |
| Did CI publish the image? | GitHub Actions → `CI` → "MCP Server image (ghcr.io, multi-arch)" on the merge commit | Job ran (it is skipped when the push touched only ignored paths) and pushed `sha-<commit>` |
| Is the tag on GHCR? | `ghcr.io/ghdtjdwn/ssumcp` package page | A `sha-<commit>` tag with a linux/arm64 manifest |
| Did the timer run? | `systemctl list-timers` and `systemctl status` of the autodeploy timer/service on the VM | Last and next run times; a `failed` service unit means a `failed`/`pull_failed` result |
| What did the script decide? | Deploy-history log (JSON lines) and `journalctl` for the service | The last line for `ssuai-backend`: `success` with `to` = expected digest, or `rolled_back` / `pull_failed` with a note |
| Is deployment paused? | Marker file next to the Compose project | Remove it to resume |
| Is the container healthy? | `docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'` | `healthy`, and the image digest matches the log |
| Does the public URL answer? | Section 3 | 200s, and `serverInfo.version` as expected |

Common causes, in observed order of likelihood:

- CI skipped the image job because the change was docs-only, or the test job failed. Nothing to deploy.
- The newest commit has no image yet (CI still running); the timer deploys the newest commit **with**
  an image, so an older commit may serve for one more cycle.
- The new image failed the public check and was rolled back (`rolled_back`). Read the backend logs
  for the failed start; the same digest is skipped until a new commit lands.
- `pull_failed`: GHCR or network issue; the next run retries.

---

## 5. Observability and backups

- The former Prometheus/Grafana/Loki/Tempo stack was **not migrated** and is not running;
  `https://ssumcp.duckdns.org/grafana` returns 404 and the backend's OTLP trace export is disabled.
  Health today is the Docker healthcheck plus the public URL check at deploy time. Compose does not
  restart a container that turns unhealthy on its own.
- Container logs are json-file with rotation (10 MB × 3); read them with `docker logs`.
- A daily `pg_dump -Fc` of the backend PostgreSQL database is uploaded to a private object-storage
  bucket with 7-day retention; a restore test script exists in the infrastructure repository.
- n8n (Discord notice automation) is kept as a disabled Compose profile with its data migrated; it is
  not running.

---

## 6. Configuration and secrets

- Production configuration is injected as environment variables from a root-only env file on the VM
  (`.env` semantics, mode 0600); nothing is committed here. `SERVER_FORWARD_HEADERS_STRATEGY=native` is
  required behind Caddy so OAuth metadata is emitted with `https` URLs.
- Rotating a secret means editing that env file and recreating the backend container
  (`docker compose up -d --no-deps <service>`); there is no Kubernetes Secret or Helm value any more.
- Variable names and what each connector needs are documented in [`.env.example`](../.env.example)
  and [`docs/architecture.md`](../docs/architecture.md).

---

## 7. Cost reminders

- The VM is inside the Oracle Always Free A1 allowance (1 OCPU / 6 GB per this VM). Adding a service
  means taking memory from the budget in section 1, not adding a node.
- Egress is far above this project's traffic, but keep an eye on it.
