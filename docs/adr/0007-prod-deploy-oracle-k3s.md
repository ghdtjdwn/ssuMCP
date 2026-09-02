# ADR 0007 — Production deploy: k3s on Oracle Cloud Free Tier + Vercel

- **Status**: Accepted (Task 06 merged; live at https://ssumcp.duckdns.org with Vercel frontend at https://ssuai.vercel.app)
- **Date**: 2026-05-07
- **Scope**: `deploy/`, `backend/.../WebCorsConfig.java`, `application-prod.yml`, `frontend/` Vercel project, `.github/workflows/ci.yml` image-build job

## Context

Task 05의 frontend MVP가 머지되면서 ssuAI는 backend와 frontend가 로컬에서
end-to-end로 동작하는 상태가 되었습니다. 다음 단계는 실제 infrastructure에서
지속적으로 운영할 수 있는 라이브 URL을 확보하는 것입니다.

요구사항은 다음과 같았습니다.

1. **지속 가능한 비용**. 개인 운영 범위에서 월 비용을 감당할 수 있어야 하고,
   장기간 같은 URL을 유지할 수 있어야 합니다.
2. **한국 region**. Backend 가 숭실대 사이트를 긁어오므로 connector latency
   예산을 지키려면 인근 region 이 필요합니다.
3. **운영 통제권**. Linux, container, Kubernetes, GitOps와 observability를
   직접 구성하고 장애 시 원인을 추적할 수 있어야 합니다.
4. **Spring Boot + Next.js** 라는 stack 의 특성. Backend 는 stateful
   process (cache, scraping rate-limit), frontend 는 Next.js 라
   first-party platform 이 별도로 존재합니다.

후보군을 위 4가지 기준으로 평가했습니다.

## Decision

**Backend 는 Oracle Cloud Free Tier ARM Ampere A1 (`ap-seoul-1`) 위의
single-node k3s 클러스터에 배포합니다. Frontend 는 Vercel 에 배포합니다.**

세부 결정:

- **Cluster**: k3s (lightweight Kubernetes, ~100 MB RAM 오버헤드).
  Bundled Traefik ingress + ServiceLB 사용. 한 VM 한 클러스터로 시작하고,
  필요 시 Task 07+ 에서 multi-node 로 확장.
- **TLS**: cert-manager + Let's Encrypt prod (HTTP-01 challenge).
  자동 갱신.
- **Domain**: `ssuai-api.duckdns.org` (duckdns 무료 dynamic DNS). 향후
  custom domain 으로 swap 은 ingress YAML 한 줄 + cert-manager 재발급.
- **Image registry**: GitHub Container Registry (`ghcr.io`). Public repo
  무료, Docker Hub free tier 의 pull rate-limit 회피.
- **Image build**: GitHub Actions 가 `main` push 마다 AMD64/ARM64
  multi-platform image index를 빌드하고 `:<sha>` + `:latest` 로 push.
  Oracle k3s는 ARM64 manifest를 선택하고, PlayMCP/KC 같은 AMD64 runtime은
  같은 immutable SHA tag에서 AMD64 manifest를 선택한다.
- **Deploy**: 이번 task 는 수동 `kubectl apply`. ArgoCD GitOps 는 Task 07.
- **Frontend**: Vercel 의 GitHub 연동 자동 deploy. 환경변수
  `NEXT_PUBLIC_SSUAI_API_BASE` 만 prod backend URL 로 설정.
- **Prod CORS**: `WebCorsConfig` 의 `@Profile("prod")` 변형이
  `SSUAI_FRONTEND_ORIGIN` env var 한 개만 명시적으로 allowlist. Wildcard
  도, Vercel preview 서브도메인도 허용하지 않음.

## Consequences

**좋은 점**

- **영구 무료**가 진짜로 영구. Oracle Free Tier ARM 은 신용카드 인증만
  하면 만료 없이 4 OCPU / 24 GB RAM / 200 GB 스토리지 / 10 TB egress 를
  계속 쓸 수 있습니다.
- **Seoul region**. 숭실대 사이트와 같은 권역 안에 backend 가 있어
  connector latency 예산이 빡빡해지지 않습니다.
- **K8s 리소스를 직접 관리**. `Deployment` / `Service` / `Ingress` /
  `ConfigMap` / `Secret` / `ClusterIssuer` / `Certificate`를 Git에서 추적합니다.
- **확장 경로가 자연스러움**. ArgoCD (Task 07), Prometheus + Grafana
  + Loki (Task 08), Postgres + Redis pod (auth task)를 같은 클러스터에
  단계적으로 추가할 수 있습니다.
- **Frontend / backend 분리 deploy**. Vercel의 first-party Next.js
  지원(edge cache, image optimization, preview deploy)을 사용하고,
  backend 운영은 K8s에 집중합니다.
- **Public ghcr.io image로 공급망 추적 가능**. 누구나
  `docker pull ghcr.io/<user>/ssuai-backend:<sha>` 로 같은 이미지 검증 가능.

### 2026-07-15 amendment — one immutable tag, two runtime architectures

PlayMCP/KC 호환을 위해 CI image job을 AMD64 전용으로 바꾼 뒤, ARM64인
production Image Updater가 새 tag를 선택하지 않아 main 변경이 배포되지 않는
회귀가 발생했다. ARM64 전용으로 되돌리면 PlayMCP/KC를 다시 깨뜨리므로,
병렬 image job은 유지하고 `linux/amd64,linux/arm64` multi-platform index를
발행한다. 별도 tag나 별도 job보다 하나의 commit SHA가 두 runtime에서 같은
소스와 release identity를 가리켜 추적과 rollback이 단순하다.

### 2026-07-15 amendment — 검증 성공 뒤에만 배포 image 발행

위 amendment의 “병렬 image job 유지” 결정은 같은 날 main CI 실패로 폐기했다.
PR에서 통과한 circuit-breaker 테스트가 main에서 request-journal timing flake로
실패했지만, 독립 image job은 multi-platform SHA를 정상 발행했다. Image Updater가
그 tag를 즉시 선택해 required test가 red인 commit이 production에 배포되는 실제
실패 모드가 확인됐다.

따라서 `image-build`는 `needs: backend`로 test·JaCoCo 성공을 선행 조건으로 둔다.
이미지를 병렬로 미리 빌드하고 성공 뒤 push만 분리하는 대안은 배포 지연을 줄이지만,
두 job 사이 multi-platform build artifact 전달·cache 관리가 복잡해 현재 규모에서
가치가 낮다. 약 3~5분의 배포 지연을 받아들이고 “GHCR에 발행된 SHA는 권위 게이트를
통과했다”는 단순한 공급망 계약을 선택한다.

**대가**

- **Setup 시간 1~2일**. Cloud Run 이라면 30분이면 끝날 일이 Oracle 계정
  생성 + VM provisioning + k3s 설치 + cert-manager + ingress + duckdns +
  ghcr.io + GitHub Actions 까지 포함하면 하루 걸립니다.
- **Single-node 클러스터의 한계**. 노드 자체 장애 = 전체 다운. Free
  tier 의 ARM 인스턴스가 가끔 reclaim 되는 사례도 보고됩니다 (드물지만
  발생). 재배포 runbook 이 `deploy/README.md` 에 살아있어야 하는 이유.
- **운영 책임이 본인에게**. 패치, OS 업데이트, k3s upgrade, cert-manager
  upgrade — managed 서비스라면 platform 이 해줄 일을 직접 합니다. 1년
  단위로 재방문해야 합니다.
- **수동 deploy (이번 task)**. SHA 를 manifest 에 박아 `kubectl apply`
  하는 워크플로는 production-grade가 아닙니다. Task 07에서 ArgoCD 자동
  동기화를 추가해야 반복 배포가 안전해집니다.
- **Free tier 제약을 spec 에 박아넣음**. 향후 비용을 들여 GKE / EKS 로
  옮기더라도 k3s manifest 가 거의 그대로 동작하지만, 노드 풀, RBAC,
  network policy 등 prod-grade 디테일은 다시 손봐야 합니다.

## Alternatives considered

- **Google Cloud Run + Supabase + Upstash** — GCP serverless stack.
  각 free tier 안에서 운영할 수 있고 Tokyo region이 있어 latency도 허용
  범위다. 다만 backend 운영 정책이 여러 managed service에 분산되고 K8s
  배포 자산을 재사용할 수 없다.
- **GKE Autopilot** — Managed K8s. 90일 $300 크레딧 동안 무료지만 그
  이후엔 control plane 비용 ~$73/월 이 발생해 "영구 무료" 조건을 위반.
  장기 운영 기간에 URL을 유지할 수 없다.
- **Fly.io** (Tokyo region) — Dockerfile만으로 배포할 수 있지만 기존 K8s
  manifest와 운영 절차를 재사용할 수 없다.
- **Railway** — 한국 학생 사이에서 흔하지만 free tier 가 사실상 $5
  크레딧으로 고갈성이고 region 이 US 뿐. 영구 무료 + Seoul 두 조건을
  모두 깸.
- **AWS ECS Fargate / App Runner** — Fargate는 무료 tier가 없고 App Runner는
  active hour 단위로 과금되어 비용 조건을 충족하지 못한다.
- **Self-hosted on Oracle ARM with Docker Compose only (k3s 없이)** —
  setup은 쉽지만 선언적 배포, self-heal, 이후 GitOps 확장 경로가 없다.

## Open questions / future tasks

- **Vercel preview deploy 의 CORS** — `*.vercel.app` 의 회전 서브도메인을
  prod backend 가 어떻게 받아들일지. 정책 결정이 필요한 별도 사안 (regex
  allowlist vs. 별도 staging backend vs. 무시). 이번 task 에서는 production
  Vercel origin 한 개만 allowlist.
- **Custom domain** (`ssuai.app` 등) — 연 $1~$10. 영구 무료 조건에서
  벗어나므로 별도 결정. 사용자가 비용을 부담할 의사가 생기면 ingress
  hostname 한 줄 + cert-manager 재발급으로 swap.
- **Multi-replica + HPA** — 현재 트래픽 (개발자 + 리뷰어 2명) 에는
  과합니다. Task 08 의 Prometheus 가 들어온 다음 metrics-server 위에서
  HPA 를 켜는 것이 자연스럽습니다.
- **Backup / DR** — single-node + DB 없음 = 백업 대상이 없음. Postgres
  pod 가 들어오는 task 부터 정책 필요 (pg_basebackup → object storage).
- **WAF / DDoS** — Cloudflare 무료 layer를 ingress 앞에 둘지 여부.
  현재 트래픽과 위협 모델에서는 우선순위가 낮다.
