# ADR 0102 — 공식 근거 기반 학사정책 초안과 지정 검토자 워크플로

- 상태: 채택 (2026-07-28)
- 관련: 0020(학사정책 hybrid RAG), 0081(LLM spend breaker), 0100(운영 보안 경계)

## 배경

기존 학사정책 검색은 공식 출처의 사실·인용·개정 검증 상태를 반환하지만, 결과를 지정 검토자가 선점하고
수정·승인·반려하는 업무 단위는 없었다. 일반 챗봇 답변만 추가하면 학생 편의 기능은 되지만 행정 업무의
처리 시간, 수정 비율, 근거 포함률을 측정하거나 책임 있는 최종 결정을 남길 수 없다.

반복적인 공개 정책 문의의 처리 품질과 운영 개선을 검증하려면 AI를 최종 결정자로 두지 않고, 답변 초안
작성에는 사용하되 지정 검토와 정량 지표를 하나의 내구성 있는 흐름으로 연결해야 한다.

## 결정

1. 범위를 공개 학칙·졸업·장학 질문으로 한정한다. 입력 guard는 명시적 학번뿐 아니라 bare 8자리 학번,
   이름·생년월일·주소 문맥, 연락처·주민번호, secret/prompt 탈취와 개인 성적·수치 기반 판정을 저장 전에
   거부한다. 일반 GPA/TOPIK 기준, 신청 절차와 유효한 `YYYYMMDD + 시행/개정/기준` 문맥은 허용한다.
2. `REST Controller → PolicyReviewCaseService → PolicyReviewCaseRepository/AcademicPolicyService` 경계를
   유지한다. V18 `policy_review_cases`가 case, 근거 metadata, 검토 결과와 시간의 source of truth다. 검색과
   LLM 호출은 DB transaction 밖에서 실행하고 최종 save만 짧게 transaction 처리해 provider 지연 동안
   connection을 점유하지 않는다.
3. Copilot 검색은 `AcademicPolicyService.briefForPolicyReview`로 분리한다. startup·scheduled refresh가 만든
   현재 corpus만 lexical ranking하며 사용자 query를 embedding provider에 보내거나 create마다 official
   connector를 호출하지 않는다. `LIVE/MIXED/SEED` 문서 provenance는 유지하되, 캐시 사용 자체만으로
   `UNRESOLVED_CONDITION`을 추가하지 않는다. 기존 public hybrid/live API 행동은 바꾸지 않는다.
4. 상태는 `PENDING_REVIEW → IN_REVIEW → APPROVED|REJECTED`만 허용한다. claim은 기본 30분 lease다. 같은
   reviewer의 살아 있는 claim은 멱등이고, 다른 reviewer의 살아 있는 claim은 409다. 만료 뒤에는 누구든
   다시 claim할 수 있지만 만료된 claim으로 결정할 수 없다. `@Version`과 `expectedVersion`, V18 상태별
   CHECK가 애플리케이션과 DB 양쪽에서 전이를 방어한다. PENDING queue는 oldest-first다.
5. reviewer는 기존 system-admin 역할과 분리한 `SSUAI_COPILOT_REVIEWER_IDS` allowlist로 판단한다. 현재
   SmartID web principal은 학생 identity만 검증됐으므로 이 값은 직원 역할을 증명하지 않는 지정 학생 pilot
   reviewer allowlist다. 직원 계정은 실제 인증을 종단 검증하기 전 활성화하지 않는다. empty는 reviewer
   endpoint를 403으로 거부하고 생성도 503으로 막는다. 생성자 목록은 reviewer 권한과 분리해 HMAC requester
   key가 일치하는 최신 20건만 반환하므로 새로고침·재로그인 뒤에도 타 사용자 case를 노출하지 않고 복구한다.
   단건 생성자 조회도 `id + requester key`로 제한해 타 사용자 case와 없는 case를 같은 404로 처리한다.
6. `PolicyDraftGenerator`는 기존 `LlmProviderChain`에 `PRIVATE` mode로 공식 `facts`와 citations만 전달한다.
   tool은 비우고 `tool_choice=none`으로 고정한다. provider 부재는 정상 결정적 초안이며, provider 호출 실패는
   추출형 초안으로 내려가 `DRAFT_GENERATION_FAILED`를 남긴다. 어떤 경우도 자동 승인·게시·학습하지 않는다.
7. 신뢰도 백분율을 만들지 않고 `NO_EVIDENCE`, `FALLBACK_SOURCE`, `REVISION_UNVERIFIED`,
   `UNRESOLVED_CONDITION`, `DRAFT_GENERATION_FAILED` 원인으로 안전 보류를 설명한다.
8. 개선 지표는 전체 entity를 JVM에 적재하지 않고 DB conditional aggregate 한 행으로 계산한다. 승인율,
   무수정 승인율, 수정률, citation coverage, safe-hold rate, 초안·검토 시간을 제공한다. 운영 meter에는
   사용자 입력이나 식별자를 label로 사용하지 않는다.
9. requester와 reviewer는 raw principal 대신 dedicated `SSUAI_COPILOT_IDENTITY_HMAC_SECRET`의
   HmacSHA256 key로 비교·저장한다. secret이 없거나 32 bytes 미만이면 feature 전체를 503으로 막는다.
   old PENDING와 lease-expired IN_REVIEW는 `created_at` 기준 30일, terminal은 `reviewed_at` 기준 180일 뒤
   삭제하며 살아 있는 claim은 보존한다.
10. 생성은 별도 Redis 공유 IP bucket(기본 10/min)으로 제한한다. public caller가 Vercel 경유를 증명하는
    인증 신호가 없으므로 Copilot은 전역 hop 설정과 무관하게 Traefik이 append한 오른쪽 한 hop만 신뢰한다. Vercel 경유 사용자는 더
    거친 공유 bucket으로 묶일 수 있지만, direct caller가 위조한 XFF prefix로 bucket을 회피하는 것보다 안전하다.
    인증된 proxy 경계가 추가되기 전에는 이 route의 고정 hop 수를 올리지 않는다.

## 대안

- 챗봇 답변을 바로 학생에게 확정 답변으로 노출: 개정 확인 실패와 조건 누락을 사람이 통제할 수 없고
  책임 소재가 불명확해 기각했다.
- 기존 `action_audit` 재사용: 학교 상태 변경의 prepare/confirm 감사 수명과 답변 검토 case의 상태·지표가
  달라 의미를 섞으므로 기각했다.
- 모델 confidence 백분율 저장: calibration 근거가 없고 검토자·운영자에게 거짓 정밀도를 주므로 기각했다.
- 첫 버전부터 ssuAgent graph와 자동 배포·알림 연결: 공개 정책 초안에는 별도 장기 orchestration이 필요하지
  않고 자동 배포가 승인 경계를 흐리므로 기각했다.
- 지표 dashboard만 구현: 측정할 실제 업무 흐름이 없어 업무 개선 근거가 되지 않으므로 기각했다.

## 검증과 한계

MockMvc는 인증, validation, 403, 409, 503과 envelope field를 검증한다. 단위 테스트는 범위 guard의
false-positive, cached lexical-only/no-embedding 검색, PRIVATE/no-tools LLM 요청, provider 폴백, lease 전이,
allowlist, HMAC과 빈 분모 지표 공식을 검증한다. Flyway/H2 통합 테스트는 V18 상태 CHECK, conditional
aggregate, 동시 claim 한 명만 성공함과 `@Version` 증가를 검증한다. retention 통합 테스트는 30일 active
privacy window, 살아 있는 claim 보존과 180일 terminal window를 확인한다.

HMAC key는 가명화이며 익명화가 아니다. 현재 버전은 key id나 dual-read rotation을 두지 않으므로 secret을
바꾸면 기존 creator/claim owner key를 새 principal로 해소할 수 없다. pilot 중 secret은 안정적으로 백업하고,
rotation이 필요하면 maintenance window에서 기존 row 만료·migration 전략을 먼저 추가한다. 이 과도한
rotation 체계는 초기 pilot 범위에서 제외한다. 또한 실제 직원 principal은 아직 검증되지 않았고, 구현된
지표는 측정 장치일 뿐 실제 pilot 전에는 시간 절감이나 정확도 향상을 증명하지 않는다.
