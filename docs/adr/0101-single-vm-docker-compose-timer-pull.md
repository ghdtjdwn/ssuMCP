# ADR 0101 — k3s/ArgoCD에서 단일 VM Docker Compose + 타이머 pull 반영으로 이관

- 상태: 채택 (2026-10-15 이관 완료, 2026-11-09 기록)
- 관련: 0007(prod-deploy-oracle-k3s), 0008(gitops-argocd-helm), 0069(observability-three-pillars),
  0078(capacity-plan-2ocpu-12gb), 0088(ha-replicas-hpa-pdb)
- 대체: 이 ADR은 0008을 대체한다. 0007의 k3s 부분, 0088의 replicas=2/HPA/PDB, 0069의 운영 스택은
  더 이상 적용되지 않는다. 각 ADR의 상태 줄에 표시했다.

## 배경

ADR 0007·0008은 Oracle Cloud Always Free A1 위의 단일 노드 k3s에 ArgoCD + Helm으로 백엔드를 운영하는
결정이었고, ADR 0078은 무료 한도가 2 OCPU/12GB로 줄어든 뒤의 용량 계획이었다. 2026-10-15에 그 서버를
종료하고 모든 개인 앱을 더 작은 VM 한 대로 옮겼다. 인프라 작업 기록에 적힌 목표는 다음과 같다.

> 목표: 기존 2 OCPU/12GB/200GB k3s 서버의 개인 앱 전부를 무료 한도 안의 1 OCPU/6GB/50GB 서버에서
> 동시에 실제 동작시키기

즉 이관의 이유는 **무료 한도(A1 코어·메모리·블록 스토리지) 안에서 개인 앱을 모두 돌리고 남은 한도를
다른 용도에 쓰기 위해 1 OCPU / 6GB로 줄이는 것**이었다. 같은 기록은 그 판단의 부속 결정을 이렇게 적는다.

> 판단: 무료 유지를 위해 겹침 운영 대신 점검 시간을 두고 기존 200GB 볼륨을 먼저 정리.
> ssuMCP 백엔드 1개·Kafka 힙 축소, n8n 정지 유지.

> 모니터링(Prometheus/Grafana/Loki/Tempo/promtail), k3s, ArgoCD, cert-manager는 새 서버로 가져가지
> 않는다.

> ArgoCD·image-updater(…)는 새 서버에서 동작하지 않는다.

1 OCPU / 6GB에서 k3s·ArgoCD·Image Updater·모니터링 스택을 그대로 유지할 여유는 없었다. 기존 k3s
서버에서도 ssuAgent의 새 이미지가 CPU 부족으로 Pending 상태에 머물러 운영에서 한 번도 뜨지 못한
적이 있었다. 반면 배포 경로에서 꼭 지켜야 할 성질은 분명했다. 테스트를 통과한 `main` 커밋만 운영에
올라가야 하고, 새 이미지가 공개 주소에서 응답하지 못하면 이전 이미지로 돌아가야 하며, 서버에
저장소 쓰기 권한이나 레지스트리 자격증명이 없어야 한다.

## 결정

1. **실행 환경**: OCI Always Free A1 VM 한 대(1 OCPU, 약 6GB RAM, Ubuntu 24.04 aarch64)에서 rootful
   Docker + Docker Compose로 운영한다. 프로젝트(ssuAI, 그 밖의 개인 앱)마다 Compose 프로젝트 하나를
   두고, 진입점은 Caddy 하나다. Caddy가 TLS(Let's Encrypt 자동 발급·갱신)를 종료하고 HTTP를 308로
   HTTPS에 보내며 보안 헤더를 붙인다. 백엔드의 management 포트 8081은 프록시하지 않는다.
2. **이미지 빌드는 그대로**: CI가 test/JaCoCo 게이트 뒤에 `ghcr.io/ghdtjdwn/ssumcp:sha-<커밋>`
   멀티아키 이미지를 올린다(`main` push와 수동 실행만). 이 저장소는 `Dockerfile`과 이미지 CI만
   소유한다.
3. **반영은 서버가 끌어온다(pull)**: systemd 타이머가 15분마다 스크립트를 실행한다. 스크립트는 공개
   GitHub API로 `main` 최근 커밋을 읽고, 최신 커밋부터 GHCR에 `sha-<커밋>` 태그가 있는 첫 이미지를
   후보로 삼아 digest를 고정한다. 현재 digest와 같으면 아무것도 하지 않는다. 다르면
   `docker pull` → env 파일의 이미지 변수 한 줄 원자 교체 → `docker compose up -d --no-deps --wait`
   → 공개 주소 확인(`GET /.well-known/oauth-protected-resource` 200 **그리고** `POST /mcp initialize`
   200, 최대 3분) 순서로 진행한다.
4. **실패 시 자동 복구**: 공개 주소 확인에 실패하면 이전 이미지로 되돌려 같은 확인을 다시 한다. 결과는
   JSON Lines 배포 이력(`success`, `rolled_back`, `failed`, `pull_failed`)과 journal에 남는다. 한 번
   `rolled_back`된 digest는 새 커밋이 나올 때까지 건너뛰어 같은 실패를 15분마다 반복하지 않는다.
5. **자격증명 최소화**: 서버는 공개 저장소와 공개 패키지만 읽으므로 GitHub 토큰도 레지스트리 로그인도
   갖지 않는다. GitHub Actions 쪽에도 서버 접속 키가 없다. ArgoCD Image Updater가 쓰던 `contents:write`
   PAT는 더 이상 필요 없다.
6. **실행 정의의 위치**: Compose 파일, systemd 유닛, Caddy 설정, 자동 반영 스크립트는 비공개 인프라
   저장소에서 관리한다. 이 저장소의 `deploy/README.md`는 그 경로를 운영자가 확인할 수 있는 수준으로
   설명하되 서버 식별자나 내부 경로는 싣지 않는다.
7. **구 정의 보관**: `deploy/charts/`, `deploy/argocd/`, `deploy/cluster-bootstrap/`, `deploy/scripts/`는
   `deploy/archive/k3s/`로 옮겨 참고용으로만 남긴다. CI는 더 이상 Helm 차트를 다루지 않으며,
   `deploy/**` 변경은 이미지 빌드를 일으키지 않는다.
8. **가져가지 않은 것**: Prometheus/Grafana/Loki/Tempo/promtail, cert-manager, ArgoCD, HPA/PDB,
   replicas=2. n8n은 데이터만 옮기고 Compose profile로 꺼 둔다. 백엔드의 OTLP 트레이스 내보내기는
   끈다.

메모리 예산(컨테이너 limit)은 백엔드 1g(JVM `-Xmx600m`, G1, `ExitOnOutOfMemoryError`), Kafka 512m(힙
256m), PostgreSQL 512m, Redis 128m, Caddy 128m이다. 2GiB 스왑(swappiness 10)은 OOM 안전망이고,
`oom_score_adj`는 DB를 가장 늦게(−500), 상태 없는 앱과 Kafka를 먼저(+300) 죽이도록 둔다.

## 대안

- **1 OCPU / 6GB에서 k3s + ArgoCD 유지**: 컨트롤 플레인·ArgoCD·Image Updater·모니터링이 앱보다 먼저
  예산을 쓴다. 기존 2 OCPU 서버에서도 CPU 부족 Pending이 발생했으므로 선택하지 않았다.
- **GitHub Actions에서 SSH로 push 배포**: 저장소 secret에 서버 키를, 서버에 CI 러너 인바운드를
  허용해야 한다. 서버가 아무 자격증명도 갖지 않고 러너도 서버에 닿지 않는 pull 방식이 노출 면적이
  작다. 대신 반영 지연(최대 15분)을 받아들였다.
- **`latest` 태그를 주기적으로 pull**: 어떤 커밋이 서비스 중인지 추적할 수 없고 digest 고정·롤백이
  없다. `sha-<커밋>` 태그와 digest 고정을 유지했다.
- **수동 배포**: ADR 0008이 기각한 이유(사람이 매번 SSH를 타야 함)가 그대로 유효하다.

## 검증과 한계

- 2026-10-15 이관 직후 공개 검증 92 PASS / 0 WARN / 0 FAIL, 재부팅 후 재검증도 같은 결과였다.
  2026-11-09 기준 배포 이력에는 ssuMCP 백엔드 자동 반영 `success` 14건이 있고 `rolled_back`·`failed`는
  없다. 백엔드 교체는 JVM 기동 때문에 1~2분 걸린다.
- **HPA/PDB 없음, 인스턴스 1개**: 롤링 교체가 없어 반영 중 1~2분 동안 백엔드가 응답하지 않는다.
  코드의 멀티 인스턴스 안전장치(claim lease, Redis 공유 rate limit)는 유지되지만 replica 간 경로는 더
  이상 실행되지 않는다.
- **GitOps write-back 없음**: 저장소에는 어떤 이미지가 운영 중인지 기록되지 않는다. 운영 중 버전은
  공개 `server-card.json`의 `serverInfo.version`과 `POST /mcp initialize`의 `serverInfo.version`으로,
  특정 커밋 반영 여부는 서버의 배포 이력으로 확인한다. ArgoCD UI 같은 상태 화면은 없다.
- **관측성 공백**: 메트릭·트레이스·중앙 로그·알림이 없다. 헬스는 Docker healthcheck와 반영 시 공개 주소
  확인뿐이며, Compose는 unhealthy 컨테이너를 스스로 재시작하지 않는다. 이전 ADR 0069의 3-pillars는
  코드 쪽(Micrometer·OTel·JSON 로그) 구성만 남아 있다.
- **반영 지연**: 머지 후 CI 빌드 + 최대 15분 타이머 + JVM 기동으로 보통 15~20분이다.
- **운영 단순화**: 디버깅 대상이 `docker ps`, `docker logs`, systemd 타이머 상태, 배포 이력 파일로
  줄었다. 대신 단일 VM이 단일 장애점이고, 메모리 예산은 위 표가 전부다.
- **공개 헬스 신호**: `/actuator/health`·`/actuator/info`는 공개 주소에서 404다(Caddy가 8080만
  프록시). 공개 문서는 `/.well-known/oauth-protected-resource`, `/.well-known/mcp/server-card.json`,
  `POST /mcp initialize`, `/api/meals/today`를 헬스 신호로 쓴다.
