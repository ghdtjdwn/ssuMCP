# ADR 0025 — LLM provider chain 분리와 provider별 Circuit Breaker

- **Status**: Accepted
- **Date**: 2026-06-12
- **Scope**:
  - `LlmProviderChain`
  - `LlmChatService` provider fallback loop
  - `JacksonConfig.primaryObjectMapper`
  - Resilience4j provider별 Circuit Breaker metric

## 배경

웹 챗봇은 여러 LLM provider를 순서대로 호출한다. 특정 provider가 quota, 5xx, 네트워크 오류로 반복 실패해도 기존 구조는 매 요청마다 같은 provider를 다시 먼저 호출했다. 실패가 이미 반복된 provider를 계속 때리면 사용자는 답변을 받기 전 불필요한 지연을 겪고, 서버는 실패가 뻔한 outbound 호출을 낭비한다.

동시에 `primaryObjectMapper`가 `LlmProviderConfig` 안에 있었다. 이 설정 클래스는 `ssuai.connector.chat=llm`일 때만 활성화되므로, 테스트나 로컬에서 `chat=mock`이면 `@Primary ObjectMapper`가 등록되지 않았다. 그런데 Redis 설정 등 다른 빈은 항상 ObjectMapper를 주입받기 때문에 mock 프로파일 기동이 깨질 수 있었다.

따라서 이번 결정은 두 문제를 함께 정리한다.

- `LlmChatService`에서 provider 순회 책임을 `LlmProviderChain`으로 분리한다.
- provider별 Circuit Breaker를 두어 OPEN 상태 provider는 throw가 아니라 skip한다.
- Jackson 기본 ObjectMapper는 chat provider 설정이 아니라 전역 설정으로 둔다.

## 검토한 대안과 탈락 사유

| 대안 | 판단 | 이유 |
| --- | --- | --- |
| `Map<String, Instant>` 쿨다운 직접 구현 | 탈락 | 구현은 작지만 metric이 없다. half-open probe도 직접 만들어야 하고, 실패율 기반 판단이 아니라 "마지막 실패 시각" 기반이라 장애와 일시적 1회 실패를 구분하기 어렵다. 면접에서도 표준 장애 격리 패턴을 적용했다는 설명력이 약하다. |
| Resilience4j Circuit Breaker | 채택 | count-based sliding window, failure rate threshold, OPEN → HALF_OPEN 자동 전환, Micrometer metric을 그대로 제공한다. 이미 Pyxis 보호 계층이 Resilience4j core API를 코드로 감싸는 구조라 같은 패턴을 재사용할 수 있다. |
| Spring Cloud CircuitBreaker | 탈락 | 추상 레이어가 하나 더 생긴다. 이 프로젝트는 이미 Resilience4j core를 직접 사용하며, provider skip처럼 호출 전 상태를 확인하는 로직도 직접 API가 더 명확하다. |

## 선택 근거

웹 검색은 `resilience4j best practices 2026` 키워드로 시작했고, 최종 근거는 공식 문서로 제한했다.

- Resilience4j CircuitBreaker 공식 문서: https://resilience4j.readme.io/docs/circuitbreaker
- Resilience4j Micrometer 공식 문서: https://resilience4j.readme.io/docs/micrometer

공식 문서는 count-based sliding window, minimum number of calls, failure-rate threshold, OPEN/HALF_OPEN/CLOSED 상태 전이, permitted half-open calls를 제공한다. 이번 요구사항은 "반복 실패 provider를 잠시 제외하고, 회복 시 probe를 허용하며, 상태를 Prometheus/Grafana에서 관찰"하는 것이므로 이 기능 집합과 직접 맞는다.

포트폴리오 가치 기준에서도 채택 가치가 높다. 단순 fallback이 아니라 provider별 장애 격리, metric 기반 관찰, half-open 회복까지 설명할 수 있기 때문이다. 완성 가능성도 높다. 이미 Pyxis에서 같은 라이브러리를 쓰고 있고, 추가 dependency 없이 구현·테스트·Grafana panel 증명이 가능하다.

## 동작 원리

`LlmProviderChain`은 `LlmCompletionRequest`를 받아 provider attempt 목록을 만든다. PUBLIC 요청은 public provider order를 먼저 시도하고, 모두 실패하면 private provider order를 private privacy mode로 이어서 시도한다. PRIVATE 요청은 private provider order만 사용한다. 기존의 `maxProviderAttempts` cap과 `availabilityVerificationPasses`도 그대로 유지한다.

provider별 Circuit Breaker 이름은 `llm-{providerName}`이다. 설정은 코드에서 고정한다.

- `slidingWindowType`: `COUNT_BASED`
- `slidingWindowSize`: `10`
- `minimumNumberOfCalls`: `5`
- `failureRateThreshold`: `50f`
- `waitDurationInOpenState`: `60s`
- `permittedNumberOfCallsInHalfOpenState`: `3`
- `automaticTransitionFromOpenToHalfOpenEnabled`: `true`

호출 전 `orderedProviders(...)`에서 `OPEN` 또는 `FORCED_OPEN` provider를 제외한다. 중요한 점은 OPEN provider를 호출하다가 예외를 던지는 방식이 아니라 **attempt 목록에서 skip**한다는 것이다. LLM fallback은 "가능한 provider를 찾아 답변을 완성"하는 경로이므로, 이미 OPEN으로 판정된 provider 예외를 사용자 응답 경로에 노출할 이유가 없다. 모든 provider가 OPEN이거나 미설정이면 기존처럼 `ChatUnavailableException`으로 귀결된다.

실제 provider 호출은 Circuit Breaker의 `executeSupplier`로 감싼다.

- 성공: provider Circuit Breaker에 success 기록.
- fallback 가능한 `LlmProviderException`: failure 기록 후 다음 provider로 진행.
- fallback 불가능한 `LlmProviderException`: failure 기록 후 `ChatUnavailableException`으로 중단.
- HALF_OPEN 동시성 등으로 `CallNotPermittedException`이 나오면 해당 attempt를 skip하고 다음 provider로 진행.

## 구현 레벨 선택

Circuit Breaker 설정은 YAML이 아니라 코드로 둔다. 이유는 세 가지다.

1. Pyxis 보호 계층이 이미 코드 설정을 사용한다.
2. provider fallback은 단순 annotation보다 호출 전 상태 확인과 skip 정책이 중요하다.
3. 코드 설정은 타입 안전성과 IDE 탐색성이 좋고, 테스트에서 같은 설정을 직접 확인하기 쉽다.

`ProviderAttempt` record는 유지한다. provider 객체와 적용할 privacy mode가 한 쌍으로 움직이기 때문이다. PUBLIC 요청이 private provider pool로 fallback될 때 같은 provider라도 privacy mode가 달라질 수 있으므로, 단순 provider list보다 record가 의도를 더 정확히 표현한다.

## Jackson 설정 위치

`primaryObjectMapper`는 LLM provider 설정이 아니라 애플리케이션 전역 JSON 정책이다. Java Time module, timestamp 설정은 chat provider가 real인지 mock인지와 무관하게 필요하다. 따라서 `global.config.JacksonConfig`로 이동하고, `LlmProviderConfig`에서는 provider 관련 bean만 유지한다.

## 운영 관찰

LLM provider Circuit Breaker는 `TaggedCircuitBreakerMetrics`로 Micrometer에 등록된다. Prometheus에서는 `resilience4j_circuitbreaker_state{name=~"llm-.*"}`로 provider별 상태를 볼 수 있다. Pyxis는 기존 `pyxis` Circuit Breaker를 같은 dashboard에서 `resilience4j_circuitbreaker_state{name="pyxis"}`로 함께 본다.
