# `deploy/archive/k3s/` — retired k3s / ArgoCD desired state

Everything under this directory is **kept for reference only**. None of it is applied anywhere
today, and nothing in CI renders, lints, or deploys it.

These files were the production desired state for ssuMCP from the first commit of this repository
(2026-05-27; the underlying decisions are [ADR 0007](../../../docs/adr/0007-prod-deploy-oracle-k3s.md)
and [ADR 0008](../../../docs/adr/0008-gitops-argocd-helm.md), 2026-05) until the **2026-10-15**
cutover, when the single-node k3s cluster was shut down and its boot volume deleted. Since then the
service runs as a Docker Compose service on one VM and new images are picked up by a server-side
timer. The current path is described in [`deploy/README.md`](../../README.md) and the decision in
[ADR 0101](../../../docs/adr/0101-single-vm-docker-compose-timer-pull.md).

| Path | What it was |
| --- | --- |
| `charts/ssuai-backend/` | Helm chart for the backend Deployment, HPA, PDB, Ingress (Traefik), Redis, ServiceMonitor, Grafana dashboards, PrometheusRules |
| `charts/postgres/`, `charts/kafka/` | Hand-written charts for PostgreSQL + pgvector and the KRaft single-node Kafka broker |
| `charts/monitoring/`, `charts/loki/`, `charts/promtail/`, `charts/tempo/` | Values for kube-prometheus-stack, Loki, Promtail and Tempo (Tempo and n8n were already scaled to 0 since 2026-09, ADR 0078) |
| `charts/n8n/` | n8n chart used for Discord notice automation |
| `argocd/` | ArgoCD Application manifests, Image Updater CR and values, ArgoCD ingress and bootstrap README |
| `cluster-bootstrap/clusterissuer.yaml` | cert-manager ClusterIssuer for Let's Encrypt |
| `scripts/` | k3s install, Secret apply, image bump and local render/apply/verify helpers |

Notes for readers:

- ADRs written before 2026-10-15 cite these files under their original paths (`deploy/charts/...`,
  `deploy/argocd/...`). Those references are historical and were not rewritten.
- The `values.yaml` image tags are whatever ArgoCD Image Updater last wrote back before the
  cutover; they do not describe the image running today. The running version is visible in the
  public `serverInfo.version` field (see `deploy/README.md`).
- Secrets were never committed here. `*.example.yaml` files show only the shape of the Secrets
  that existed in the cluster.
