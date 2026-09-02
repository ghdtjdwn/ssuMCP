# ADR 0067 — LMS export 다운로드 토큰 1회 사용 보장

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30, 2026-09-03 정정 |
| 상태 | Accepted — 적용 |
| 범위 | `LmsExportController`, `LmsExportJobRepository`, `LmsExportStatus` |
| 연관 문서 | ADR 0033, ADR 0055, `docs/security-followups.md` R4 |

## 배경

LMS 강의자료 ZIP 내보내기는 ADR 0033에서 비동기 빌드와 capability URL 방식으로 설계했다. URL에는 원문 토큰이 query string으로 실리고, 서버는 저장된 SHA-256 해시와 constant-time 비교로 검증한다. 토큰 TTL은 짧고 응답에는 `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `Pragma: no-cache`가 붙는다.

READY 상태 링크를 TTL 동안 반복 사용할 수 있으면 링크를 얻은 다른 주체도 성공 다운로드 후 다시 ZIP을 받을 수 있다. 이를 막으려면 동일 capability를 사용한 동시 요청을 포함해 실제 바이너리 스트림의 승자가 한 건이어야 한다.

초기 ADR 문구는 파일 복사와 `flush()`가 성공한 뒤 토큰을 소비한다고 기록했지만, 현재 구현은 스트림을 반환하기 전에 `READY → DOWNLOADED`를 원자적으로 선점한다. 이 문서는 구현을 약화하지 않고 실제 보안 계약을 명시하도록 정정한다. 서버 측 copy나 flush 성공은 클라이언트가 파일 전체를 받았다는 확인 응답이 아니므로, 이를 소비 성공의 기준으로 삼을 수도 없다.

## 결정

실제 ZIP 바이너리 요청은 파일 스트림을 반환하기 전에 DB 조건부 UPDATE로 capability를 선점한다.

```sql
UPDATE lms_export_jobs
   SET status = 'DOWNLOADED', completed_at = :now
 WHERE id = :id AND status = 'READY'
```

repository 메서드 `claimReadyForDownload`는 변경된 row 수를 반환한다. 결과가 1인 요청만 스트림을 받고, 0인 요청은 `410 Gone`과 `DOWNLOADED` 상태를 받는다. PostgreSQL의 UPDATE 원자성과 조건 재평가가 동시 요청 사이의 단일 승자를 결정하며, 스트리밍 동안 transaction이나 row lock을 유지하지 않는다.

여기서 `DOWNLOADED`는 클라이언트의 완전 수신 증명이 아니라 서버가 한 요청에 일회성 스트림 권한을 부여했다는 뜻이다. 선점 뒤 연결이 끊기거나 파일 read가 실패하면 같은 링크는 다시 사용할 수 없다. 브라우저 페이지는 첫 다운로드 요청 뒤 버튼을 비활성화하고, 실패했다면 새 내보내기를 만들도록 안내한다.

HTML 페이지와 `format=json` 상태 조회는 토큰을 검증하지만 capability를 소비하지 않는다. READY 상태에서 실제 ZIP 바이너리를 요청하는 경로만 선점 대상이다.

## 대안과 기각 이유

### 스트림 완료 뒤 소비

복사와 flush가 끝난 뒤 `READY → DOWNLOADED`를 수행하면 연결 실패 시 같은 링크로 재시도할 수 있다. 그러나 두 동시 요청이 모두 READY를 읽고 같은 ZIP을 받을 수 있어 일회성 capability가 아니게 된다. flush도 서버 또는 중간 버퍼에 쓰기가 끝났다는 의미일 뿐, 브라우저의 완전 수신을 증명하지 않는다. 재시도 편의보다 private archive의 단일 스트림 보장을 우선한다.

### 스트리밍 동안 DB row lock 유지

READY row를 잠근 transaction 안에서 ZIP 전체를 전송하면 단일 승자를 유지하면서 실패 복구를 추가할 수 있다. 대신 느린 클라이언트나 끊긴 연결이 DB connection과 row lock을 오래 점유한다. 현재 트래픽과 파일 크기에서도 요청 수명만큼 DB 자원을 묶는 설계는 운영 위험이 더 크다.

### `CLAIMED` 중간 상태와 lease 도입

선점 후 전송 성공 시 `DOWNLOADED`, 실패 시 READY 복구 또는 lease 만료 재시도를 구현할 수 있다. 하지만 서버가 클라이언트의 완전 수신을 알 수 없는 문제는 남고, lease 중 재시도 정책·동일 파일의 중복 전송·프로세스 중단 복구를 위한 상태와 스케줄러가 추가된다. 명확한 제품 요구와 관측 자료 없이 상태 머신을 늘리지 않는다.

### 별도 consumed 테이블 또는 Redis SETNX

`lms_export_jobs.status`가 이미 다운로드 생명주기의 PostgreSQL source of truth다. 별도 저장소는 만료 정리와 장애 시 일관성 표면만 늘린다.

## 마이그레이션과 롤백

`lms_export_jobs.status`는 `VARCHAR(16)`이고 `DOWNLOADED` 값과 `completed_at` 컬럼이 이미 존재한다. 호출 의미, 브라우저 안내, 문서와 테스트를 현재 계약에 맞추는 변경이므로 Flyway 마이그레이션은 없다.

애플리케이션 롤백에도 스키마 호환 문제는 없다. 이미 `DOWNLOADED`가 된 job은 기존과 같이 terminal 상태로 남는다. 다만 선점 전 소비 방식 자체를 되돌리면 동시 스트림 허용이라는 보안 회귀가 생기므로, 코드 롤백 전에 이 ADR의 위협 가정을 다시 검토해야 한다.

## 동작 방식

1. canonical job id, query token, MCP owner credential을 검증한다.
2. HTML 요청은 상태 페이지를 반환하고, `format=json`은 현재 상태만 반환한다.
3. 만료·빌드 중·실패·이미 다운로드된 상태는 기존 JSON 계약으로 종료한다.
4. READY job의 파일 존재 여부와 export base 내부 canonical path를 검증한다.
5. 바이너리 요청은 `claimReadyForDownload(jobId, now)`를 호출한다.
6. affected row가 0이면 다른 요청이 선점했거나 상태가 바뀐 것이므로 `410 Gone`을 반환한다.
7. affected row가 1인 요청만 파일을 복사하고 flush한다.
8. 스트림이 실패해도 상태는 `DOWNLOADED`로 유지한다. 서버는 token·파일 경로·예외 메시지 없이 실패 유형만 기록하고, 사용자는 새 내보내기를 생성한다.

## 동시성 불변식

- 같은 READY job에 대한 동시 `claimReadyForDownload` 중 정확히 한 건만 1을 반환한다.
- 승자가 commit하기 전 경쟁 UPDATE는 대기하고, commit 뒤 `status = READY` 조건을 다시 평가해 0을 반환한다.
- JSON poll과 HTML 페이지 조회는 READY 상태를 바꾸지 않는다.
- 바이너리 response body는 claim 결과가 1인 요청에만 만들어진다.

이 불변식은 실제 PostgreSQL Testcontainers 통합 테스트로 검증한다. 두 transaction을 동시에 시작하고 승자 transaction을 잠시 유지해 경쟁 UPDATE 경로를 강제로 통과시킨 뒤, 결과가 `[0, 1]`이고 최종 상태가 `DOWNLOADED`인지 확인한다. 컨트롤러 테스트는 선점 실패의 410 응답, 상태 조회 비소비, 스트림 실패 후에도 선점이 유지되는 계약을 검증한다.

## 잔여 위험

- 선점 직후 클라이언트 연결이 끊기면 사용자가 파일을 받지 못하고 링크는 소진된다.
- URL 토큰은 브라우저 주소 기록이나 접근 로그 취급에 주의해야 한다. no-referrer/no-store/no-cache와 짧은 TTL은 계속 유지한다.
- archive는 만료 정리 시점까지 공유 PVC에 남을 수 있지만, 사용한 capability로 다시 제공되지 않는다.

이 트레이드오프는 복구 가능한 사용자 재내보내기보다 private archive의 동시 중복 제공 차단을 우선한 결과다.
