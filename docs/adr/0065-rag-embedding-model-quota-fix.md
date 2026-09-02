# ADR 0065 — 하이브리드 RAG 임베딩 복구: 모델 교정(quota) + 진단 로깅

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-23 |
| 상태 | Accepted — 구현(브랜치 `fix/rag-embedding-model-and-logging`) |
| 범위 | `AcademicEmbeddingClient`(진단 로깅) · `application.yml`(임베딩 모델 기본값) · `deploy/charts/ssuai-backend/values.yaml`(prod 모델) |
| 연관 ADR | [0020](0020-academic-policy-hybrid-rag.md)(하이브리드 RAG) · [0062](0062-supply-chain-k8s-pod-security.md)(이 ADR의 자매 발견 = Dockerfile 빌드 중단) |
| 연관 분석 | 내부 보안 분석 I1 |

---

## 배경 — 무슨 문제

라이브 실측(2026-06-23): `search_academic_policy_sources`가 기본·`live:true` 양쪽 `embeddingUsed:false, fusionMethod:lexical`. prod 로그: `AcademicEmbeddingClient: request failed (RestClientException); 216 missing chunk(s); embeddingActive=false`, `academic_embeddings` 테이블 **0행**. 즉 하이브리드 RAG의 벡터 절반이 prod에서 **휴면** — 헤드라인 기능이 lexical-only로만 동작(공개 데모 불가).

**플래그·키는 정상이었다**: prod configmap `SSUAI_ACADEMIC_EMBEDDING_ENABLED=true`, 시크릿에 `SSUAI_GEMINI_API_KEY` 존재. 그런데도 모든 임베딩 호출이 실패.

## 진단 (가설 → 기각, 코드/라이브 대조)

1. **"플래그가 꺼졌다"** → 기각. 레포 기본값은 false지만 prod configmap이 true로 오버라이드(kubectl 확인).
2. **"preview 모델이 OpenAI-호환 `/embeddings`에서 안 뜬다"** → 기각. 내 머신에서 `gemini-embedding-2-preview`·`gemini-embedding-001` 둘 다 단건 임베딩 **HTTP 200**.
3. **"prod egress 차단"** → 기각. prod pod에서 직접 curl(pod의 키)로 단건 임베딩 **HTTP 200**.
4. **실제 원인 = 무료티어 quota.** prod pod에서 **96-input 배치**(prod `batch-size`)를 `gemini-embedding-2-preview`로 호출 → **HTTP 429**(`quotaValue: 100`, `retryDelay: 52s`). 216 청크(=배치 3개)가 preview의 빡빡한 quota를 넘겨 매 refresh마다 429 → `embeddingActive=false`. chart는 preview를 **2026-06-11 메모**("001 quota가 crash-loop로 0에 고착")를 근거로 선택했는데, **2026-06-23엔 001 quota가 회복**(96 배치 → 200, 429가 ~11s만에 회복)되어 있었다. 게다가 클라이언트가 예외를 **클래스명만 로깅**(`RestClientException`)해서 "429 quota"라는 진짜 원인이 가려져 있었다(미진단의 직접 원인).

## 결정

1. **임베딩 모델 `gemini-embedding-2-preview` → `gemini-embedding-001`** (`application.yml` 기본값 + chart `values.yaml` prod). 근거: 001은 quota가 회복됐고 429 회복이 ~11s라, 기존 `batch-interval-ms=15000`(15s 배치 페이싱) + 30s 재시도 백오프로 216 청크가 임베딩된다. preview(회복 52s·quota 100)는 그 페이싱으로도 계속 429.
2. **진단 로깅 추가**: `AcademicEmbeddingClient`가 `RestClientResponseException`일 때 **HTTP status + 응답 본문**(예: 429 quota 메시지)을 로깅. 키는 요청 헤더에만 있고 응답엔 없으므로 비노출 정책 유지(비-HTTP 예외는 메시지가 키를 echo할 수 있어 타입만 로깅).

## 대안과 기각 이유

- **preview 유지 + batch-size 축소/페이싱 증가** — preview는 quota 자체가 100으로 작고 회복 52s라, 216 청크를 페이싱으로 욱여넣기 불안정. 001이 근본적으로 여유. → 모델 교정.
- **chart의 기존 결정(preview) 신뢰** — 그 결정의 전제("001 quota 0 고착")가 **12일 지난 stale**. 라이브 1차 증거(001 배치 200)가 명백히 반증 → 최신 증거 채택. (배포 후 corpus가 실제로 임베딩되는지 검증해 안전망 확보.)
- **로깅을 안 고치고 모델만 교정** — 재발 시 또 "정체불명 RestClientException"으로 묻힘. 관측성 개선이 이 사건의 핵심 교훈이라 함께 적용.

## 동작 방식 / 검증

- 배포 후: corpus refresh가 001로 216 청크 임베딩 → `academic_embeddings` 행 > 0, 로그 `embeddingActive=true chunks>0`, 라이브 `search_academic_policy_sources`가 `embeddingUsed:true, fusionMethod` 하이브리드. lexical vs hybrid 검색 품질 수치 기록(docs).
- 재시도 로깅: 429 시 `status=429 body={...quota...}`가 로그에 남아 다음 quota 이슈는 즉시 진단 가능.
- 단위테스트: 기존 `AcademicEmbeddingClientTests`(callEmbeddings 오버라이드로 실패/페이싱 검증) 그린 유지.
