# 학사정책 검토 Copilot REST 계약

학사정책 검토 Copilot은 공개 학칙·졸업·장학 질문을 공식 근거로 검색해 지정 검토자용 답변 초안을 만들고,
승인 또는 반려 전까지 외부에 확정 답변으로 게시하지 않는 검토 워크플로다. 개인 성적·자격 판정은
범위 밖이다. 설계 근거는 [ADR 0102](adr/0102-grounded-policy-review-copilot.md)에 있다.

## 상태와 권한

```text
PENDING_REVIEW → IN_REVIEW → APPROVED
                            └→ REJECTED
```

- 모든 endpoint는 ssuAI access JWT가 필요하다.
- 생성자는 자신이 만든 case만 읽을 수 있다. owner 목록은 reviewer 권한과 무관하게 HMAC requester key가
  일치하는 최근 20건만 최신순으로 반환한다. 단건 조회도 `id + requester key`를 함께 조회하며 타인 소유와
  미존재 case를 동일한 404로 처리해 ID 존재 여부를 노출하지 않는다.
- `SSUAI_COPILOT_REVIEWER_IDS` allowlist에 포함된 사용자만 queue, claim, decision, metrics를 사용한다.
- 현재 SmartID web 인증은 학생 principal만 종단 검증됐다. 따라서 pilot allowlist는 지정 학생 검토자를
  뜻하며 직원 신분을 증명하지 않는다. 실제 직원 계정은 인증 검증 전 활성화하지 않는다.
- allowlist 기본값은 비어 있으며 모든 reviewer endpoint를 403으로 거부한다. 이 상태에서는 검토되지
  않을 case가 쌓이지 않도록 생성도 `503 COPILOT_UNAVAILABLE`로 거부한다.
- identity HMAC secret이 없거나 32 bytes 미만이면 모든 Copilot 작업을 `503 COPILOT_UNAVAILABLE`로 막는다.
- claim lease는 기본 30분이다. 같은 reviewer의 active claim은 멱등이고 다른 reviewer의 active claim은
  `409 CONFLICT`다. 만료 뒤에는 reclaim할 수 있지만 만료된 claim으로 decision할 수 없다.

## Endpoint

모든 응답은 `{ data, error, traceId }` envelope을 사용한다. case `id`는 JSON number다.

| Method | Path | 계약 |
| --- | --- | --- |
| `POST` | `/api/copilot/policy-cases` | 공개 학사정책 질문을 검증하고 근거 기반 초안을 생성한다. |
| `GET` | `/api/copilot/policy-cases` | 로그인한 생성자 자신의 최근 case 최대 20건을 최신순으로 조회한다. |
| `GET` | `/api/copilot/policy-cases/{id}` | 생성자 또는 reviewer가 case를 읽는다. |
| `GET` | `/api/reviewer/policy-cases?status=PENDING_REVIEW` | 대기 case 100건을 oldest-first로 조회한다. 다른 상태와 전체 조회는 최신순이다. |
| `POST` | `/api/reviewer/policy-cases/{id}/claim` | 대기 또는 lease-expired case를 선점한다. |
| `POST` | `/api/reviewer/policy-cases/{id}/decision` | 선점한 reviewer가 승인 또는 반려한다. |
| `GET` | `/api/reviewer/policy-cases/metrics` | 저장된 case의 익명 집계 지표를 조회한다. |

생성 요청 예시:

```json
{
  "question": "20260301 시행 기준의 졸업 학점 규정은 어떻게 되나요?",
  "category": "graduation"
}
```

결정 요청 예시:

```json
{
  "expectedVersion": 1,
  "decision": "APPROVE",
  "finalAnswer": "지정 검토자가 확인·수정한 최종 답변"
}
```

반려 시에는 `decision=REJECT`, `rejectionReason`이 필요하며 `finalAnswer`는 사용하지 않는다. 응답은
`aiDraft`, `finalAnswer`, `rejectionReason`, citations, `reviewReasonCodes`, source/provider metadata,
`reviewStartedAt`, `claimExpiresAt`, `claimedByCurrentReviewer`, `reviewedAt`, `version`을 함께 제공한다.

## 근거와 안전 보류

case 검색은 scheduled/startup refresh가 만든 현재 공식 corpus를 lexical-only로 사용한다. 사용자 query를
embedding provider에 보내거나 create마다 official source를 호출하지 않는다. 현재 corpus의
`LIVE/MIXED/SEED` provenance는 응답에 유지한다. LLM에는 `PRIVATE` privacy mode, 사용자 질문, 검색에서
직접 추출한 `facts`와 citations만 전달하고 tool 없이 `tool_choice=none`으로 고정한다. provider가 없거나
실패하면 추출형 답변으로 내려가며 자동 승인하지 않는다.

`reviewReasonCodes`는 임의의 신뢰도 백분율 대신 검증 가능한 원인을 표현한다.

| Code | 의미 |
| --- | --- |
| `NO_EVIDENCE` | 질문과 직접 일치하는 공식 근거가 없다. |
| `FALLBACK_SOURCE` | live source 일부를 seed fallback으로 대체했다. |
| `REVISION_UNVERIFIED` | 인용한 개정본을 확인하지 못했다. |
| `UNRESOLVED_CONDITION` | 공식 근거만으로 확정하지 못한 조건이 있다. |
| `DRAFT_GENERATION_FAILED` | LLM 생성 실패 후 추출형 초안으로 안전하게 대체했다. |

결정적 mock 모드의 추출형 초안은 정상 동작이므로 `DRAFT_GENERATION_FAILED`가 아니다.

## 입력 범위

허용 예시는 일반 정책 기준과 신청 절차다.

- `일반적으로 GPA 3.5와 15학점이면 백마장학 기준이 어떻게 되나요?`
- `제가 교내장학금을 어디에서 신청하고 일정은 어디서 확인하나요?`
- `TOPIK 4급 장학 기준은 무엇인가요?`

명시적 학번과 bare 8자리 학번, 이름·생년월일·주소 문맥, 이메일·전화번호·주민번호, 비밀정보·내부
prompt 요청, 개인 수치와 결합한 자격 판정은 400으로 거부한다. 예:
`20261234 학생의 졸업 학점을 확인해 주세요.`. 유효한 날짜와 시행·적용·개정·기준 문맥이 함께 있는
`20260301 시행 기준...`은 허용한다.

## 지표 정의

`totalCases`, 상태별 case 수, `averageDraftLatencyMs`, `averageReviewDurationMs`와 다음 비율을 DB
conditional aggregate 한 행으로 제공한다.

- `approvalRate = approvedCases / (approvedCases + rejectedCases)`
- `unchangedApprovalRate = 원문 그대로 승인 / approvedCases`
- `correctionRate = 수정 승인 / approvedCases`
- `citationCoverageRate = citation이 1개 이상인 case / totalCases`
- `safeHoldRate = reviewReasonCodes가 1개 이상인 case / totalCases`

Prometheus 운영 meter는 생성·보류·결정 수와 지연 시간만 기록한다. 질문, case id, category, requester,
reviewer, source를 label이나 로그에 넣지 않는다. 이 지표는 실제 pilot 결과를 수집하기 위한 장치이며,
측정 전 절감 시간이나 정확도 향상을 주장하지 않는다.

## 데이터 수명과 운영 설정

- requester/reviewer 식별자는 dedicated secret을 사용한 HmacSHA256 key로 저장한다. 이는 가명화이며
  익명화가 아니다. secret rotation용 key id/dual-read는 초기 pilot 범위가 아니므로 secret을 임의로
  변경하면 기존 소유권을 해소할 수 없다.
- 질문과 초안은 검토에 필요해 저장한다. old `PENDING_REVIEW`와 claim lease가 만료된 old `IN_REVIEW`는
  `created_at` 기준 기본 30일 뒤 삭제하고 active claim은 보존한다. `APPROVED`와 `REJECTED`는
  `reviewed_at` 기준 기본 180일 뒤 삭제한다.
- 생성 endpoint는 Redis 공유 IP bucket으로 기본 분당 10회 제한하며 Redis 장애 시 pod-local limiter로
  저하된다. 인증되지 않은 public Vercel 경유 여부는 신뢰할 수 없으므로 Copilot 생성 route는 전역 hop
  설정과 무관하게 Traefik이 append한 오른쪽 한 hop만 사용한다. Vercel 경유 사용자는 더 거친 공유 bucket으로 묶일 수 있지만 direct caller가
  XFF prefix를 위조해 bucket을 회피할 수는 없다. reviewer claim/decision은 LLM 생성 bucket을 소비하지 않는다.

| 환경 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `SSUAI_COPILOT_REVIEWER_IDS` | empty | 쉼표 구분 reviewer allowlist. 개인정보이므로 Kubernetes Secret 등으로 주입한다. |
| `SSUAI_COPILOT_IDENTITY_HMAC_SECRET` | empty | 최소 32 bytes의 전용 random secret. Secret으로 주입하며 누락·짧은 값은 feature 503이다. |
| `SSUAI_COPILOT_CLAIM_LEASE` | `30m` | reviewer claim 유효 시간 |
| `SSUAI_RATELIMIT_COPILOT_PER_MINUTE` | `10` | 생성 endpoint의 IP별 1분 예산 |
| `SSUAI_RETENTION_POLICY_REVIEW_ACTIVE_DAYS` | `30` | old pending·lease-expired in-review 보존 기간 |
| `SSUAI_RETENTION_POLICY_REVIEW_TERMINAL_DAYS` | `180` | approved·rejected 보존 기간 |

운영 활성화 전에는 V18 migration 적용, reviewer allowlist와 identity HMAC secret 주입, 비운영 지정 학생
계정으로 create→claim→lease expiry/reclaim→decision→metrics smoke test와 queue 적체 경보 기준을 확인한다.
직원 계정은 SmartID principal을 실제 계정으로 검증할 때까지 allowlist에 넣지 않는다. migration과 secret
변경은 운영 변경 승인 절차를 따른다.
