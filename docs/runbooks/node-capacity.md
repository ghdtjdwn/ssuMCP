# 노드 디스크 응급조치 & 부트볼륨 확장 런북

대상: 프로덕션 단일 노드 k3s 클러스터 (Oracle Cloud ARM Ampere A1, Ubuntu 22.04,
k3s v1.35.4). 관련 배포 문서는 [`../../deploy/README.md`](../../deploy/README.md),
아키텍처 배경은 [`../adr/0007-prod-deploy-oracle-k3s.md`](../adr/0007-prod-deploy-oracle-k3s.md) 참조.

## 배경

- 2026-06-15부로 Oracle Free Tier ARM 할당량이 절반으로 축소됨: 4 OCPU/24GB → **2
  OCPU/12GB**, 월 1,500 OCPU-hr 상한. 인스턴스 shape은 그대로지만 실사용 가능 자원이
  줄어든 상태.
- 현재 부트볼륨 사용률이 **39G/49G = 81%**로 위험 수위. 목표는 **65% 이하**로 낮추는 것.
- 디스크 여유가 없으면 예정된 Kafka/OpenSearch 도입이 막힌다. OpenSearch는 디스크
  사용률이 **85%를 넘으면 인덱스가 read-only로 강제 전환**되는 워터마크를 갖고 있어,
  현재 81%는 사실상 여유가 거의 없는 상태다.
- 이 문서는 ① 즉시 적용 가능한 디스크 응급처치, ② 부트볼륨 자체를 49G→150G로 늘리는
  영구 조치 두 가지를 다룬다. Prometheus/Loki/Tempo 리텐션 축소는 이 문서 범위가
  아니며 3장을 참고.

---

## 1. 디스크 응급처치 (즉시 실행, 다운타임 사실상 없음)

### 1.1 진단

노드에 SSH 접속 후 사용량부터 파악한다.

```bash
ssh ubuntu@<VM_PUBLIC_IP>

# 전체 파티션 사용률
df -h

# rancher(k3s 데이터 디렉터리)와 로그 디렉터리별 용량 확인
sudo du -xsh /var/lib/rancher/* /var/log/* 2>/dev/null | sort -rh

# 컨테이너 이미지 목록과 크기
sudo k3s crictl images
```

`du` 결과에서 대개 아래 두 곳이 상위를 차지한다:

- `/var/lib/rancher/k3s/agent/containerd/` — 이미지 레이어 + 스냅샷
- `/var/log/journal/` 또는 `/var/log/pods/` — journald 로그, 컨테이너 stdout/stderr

### 1.2 미사용 이미지 정리

더 이상 어떤 파드도 참조하지 않는 이미지를 정리한다.

```bash
sudo k3s crictl rmi --prune
```

실행 직후 `df -h`로 얼마나 확보됐는지 바로 확인. ArgoCD Image Updater 특성상 과거
SHA 태그 이미지가 다수 쌓여 있을 가능성이 높으므로 이 단계에서 가장 큰 폭의 정리가
기대된다.

### 1.3 kubelet 이미지 GC 영구 설정

1.2는 1회성 수동 정리다. 재발을 막기 위해 kubelet의 이미지 가비지 컬렉션 임계값을
k3s 설정 파일에 영구 반영한다.

`/etc/rancher/k3s/config.yaml`을 편집(없으면 새로 생성)한다:

```yaml
kubelet-arg:
  - "image-gc-high-threshold=70"
  - "image-gc-low-threshold=60"
```

- `image-gc-high-threshold=70`: 디스크 사용률이 70%를 넘으면 kubelet이 자동으로
  미사용 이미지 삭제를 시작.
- `image-gc-low-threshold=60`: 60% 밑으로 내려올 때까지 삭제.
- k3s 기본값(85%/80%)보다 낮춰서 81% 근처에서 이미 GC가 계속 돌도록 만드는 것이
  이번 조치의 핵심.

설정 적용:

```bash
sudo systemctl restart k3s
```

> **주의**: `systemctl restart k3s`는 API 서버(컨트롤 플레인)를 재시작하므로 재시작
> 완료까지 수 초~수십 초간 `kubectl` 명령이 실패할 수 있다. 이미 떠 있는 파드의
> 컨테이너 런타임(containerd)은 별도 프로세스이므로 **파드 자체는 재시작되지 않고
> 계속 서비스한다.** 순수 API 다운타임이며 트래픽 영향은 없다.

적용 확인:

```bash
sudo systemctl status k3s
sudo k3s kubectl get nodes
ps aux | grep '[k]ubelet' | grep -o 'image-gc-[a-z-]*=[0-9]*'
```

### 1.4 journald 로그 용량 상한

`/etc/systemd/journald.conf`에서 `SystemMaxUse` 항목을 다음과 같이 설정(주석 해제
또는 추가):

```ini
SystemMaxUse=500M
```

적용:

```bash
sudo systemctl restart systemd-journald
journalctl --disk-usage
```

`journalctl --disk-usage` 출력이 500M 이하로 수렴하는지 확인. journald가 즉시
기존 로그를 500M까지 줄이지 않으면 다음 회전 주기까지 기다리거나
`sudo journalctl --vacuum-size=500M`으로 즉시 강제 정리한다.

### 1.5 containerd 컨테이너 로그 로테이션 확인

k3s 내장 containerd 자체는 컨테이너 stdout/stderr 로그를 직접 관리하지 않는다.
실제 로테이션은 kubelet의 `--container-log-max-size` / `--container-log-max-files`
인자가 담당하며, 로그 파일은 `/var/log/pods/`에 쌓이고 `/var/log/containers/`로
심볼릭 링크된다.

현재 적용된 값 확인:

```bash
ps aux | grep '[k]ubelet' | grep -o 'container-log-max-[a-z]*=[^ ]*'
sudo du -xsh /var/log/pods/* 2>/dev/null | sort -rh | head -20
```

값이 비어 있거나(=무제한) 개별 파드 로그가 비정상적으로 큰 경우, 1.3과 같은 위치에
kubelet-arg를 추가한다:

```yaml
kubelet-arg:
  - "image-gc-high-threshold=70"
  - "image-gc-low-threshold=60"
  - "container-log-max-size=10Mi"
  - "container-log-max-files=3"
```

적용 후 `sudo systemctl restart k3s`로 반영(1.3과 동일한 다운타임 특성).

### 1.6 검증

```bash
df -h
sudo du -xsh /var/lib/rancher/* /var/log/* 2>/dev/null | sort -rh
```

기대 결과: 사용률이 81%에서 **65% 이하**로 하락. 목표에 못 미치면:

- `sudo k3s crictl images` / `sudo k3s crictl ps -a`로 정지된(exited) 컨테이너가
  이미지 GC 대상에서 빠져 있는지 확인 후 `sudo k3s crictl rm <id>`로 정리.
- `/var/lib/rancher/k3s/agent/containerd/io.containerd.snapshotter.v1.overlayfs/`
  아래 고아 스냅샷 디렉터리 유무 확인(정상적으로는 crictl rmi --prune이 정리함).
- 그래도 부족하면 2장의 부트볼륨 확장으로 근본 해결.

---

## 2. 부트볼륨 확장 49G → 150G

> Oracle Free Tier는 계정당 총 **200GB** 블록 스토리지를 무료로 제공한다(부트볼륨 +
> 추가 블록 볼륨 합산). 150GB로 확장하면 나머지 여유는 50GB뿐이므로, 다른 블록
> 볼륨을 쓰고 있다면 반드시 총합을 먼저 계산해야 과금 없이 진행할 수 있다.

### 2.1 사전 확인 (OCI 콘솔)

1. OCI 콘솔 → **Block Storage → Boot Volumes**에서 현재 부트볼륨 크기(49GB)와
   상태를 확인.
2. OCI 콘솔 → **Block Storage → Block Volumes**에서 이 인스턴스에 연결된 다른
   블록 볼륨이 있는지 확인하고, 있다면 그 용량까지 합산해서 150GB 확장 후에도
   총합이 200GB 무료 한도를 넘지 않는지 계산한다.
3. 한도를 넘는 경우 목표 크기를 낮추거나(예: 130GB) 다른 볼륨을 축소/삭제할지
   먼저 결정한다 — 이 판단 없이 바로 리사이즈하면 초과분에 과금이 발생할 수 있다.

### 2.2 백업 먼저 (필수)

리사이즈 전 반드시 수동 백업을 생성한다. **이것이 실패 시 되돌릴 수 있는 유일한
수단**이다(2.5 참고).

1. 같은 Boot Volumes 화면에서 대상 볼륨 선택 → **Create Manual Backup**.
2. 백업 상태가 `Available`로 바뀔 때까지 대기 (보통 수 분~수십 분, 데이터량에
   따라 다름).
3. 백업이 완료된 것을 콘솔에서 직접 확인한 뒤에만 다음 단계로 진행한다.

### 2.3 OCI 콘솔에서 온라인 리사이즈

1. 같은 볼륨의 **More Actions → Resize**.
2. 새 크기 `150` GB 입력 → 확인.
3. Oracle 부트볼륨은 **온라인 리사이즈**를 지원하므로 인스턴스를 끌 필요는 없다.
   콘솔에서 작업 상태가 완료(Available)될 때까지 대기.

### 2.4 인스턴스 내부에서 파티션/파일시스템 확장

콘솔에서 볼륨 크기를 늘려도 OS가 즉시 인식하지 못하므로 디바이스를 재스캔한다.

```bash
sudo dd iflag=direct if=/dev/oracleoci/oraclevda of=/dev/null count=1
echo 1 | sudo tee /sys/class/block/sda/device/rescan
```

커널이 새 크기를 인식했는지 확인:

```bash
lsblk
sudo parted /dev/sda print
```

`lsblk` 출력에서 `/dev/sda` 전체 크기가 150G로 보이는지 확인한 뒤, 파티션과
파일시스템을 확장한다:

```bash
sudo growpart /dev/sda 1
sudo resize2fs /dev/sda1
```

> 루트 파일시스템이 ext4가 아니라 xfs인 경우 `resize2fs` 대신
> `sudo xfs_growfs /`를 사용한다. `mount | grep ' / '`로 파일시스템 타입을 먼저
> 확인할 것.

### 2.5 검증

```bash
df -h /
lsblk
```

기대 결과: `/` 파티션 크기가 약 150GB로 표시되고, 기존 39G 사용량 기준 사용률이
81%에서 26% 내외로 크게 낮아짐. 1장의 조치와 합치면 실사용량 자체도 더 줄어든
상태일 것.

### 2.6 롤백 불가 — 반드시 숙지

**OCI 부트볼륨은 축소(shrink)를 지원하지 않는다.** 150GB로 확장하면 이후 49GB로
되돌리는 기능 자체가 없으며, 이는 단방향(one-way) 작업이다.

- 리사이즈 자체가 실패하거나 파일시스템이 손상된 경우, 되돌릴 방법은 2.2에서
  만든 백업으로부터 **새 볼륨을 복원**해서 인스턴스에 재연결하는 것뿐이다. 이
  복구 경로는 인스턴스 재부팅과 짧은 다운타임을 수반한다.
- 따라서 2.2 백업을 건너뛰지 말 것. 백업이 없으면 리사이즈 실패 시 복구 수단이
  없다.

---

## 3. 관련 문서 교차 참조

Prometheus / Loki / Tempo의 리텐션(retention) 기간 축소 및 리소스 재조정은 이
문서의 범위가 아니다. 해당 작업은 별도 브랜치 `chore/p0-resource-rightsizing`에서
처리한다. 이 런북은 노드 디스크 응급처치와 부트볼륨 확장(하드웨어/스토리지 레벨
조치)만 다룬다.
