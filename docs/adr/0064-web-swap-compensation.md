# ADR 0064 — 웹 좌석 swap 보상(compensation) 경로

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-23 |
| 상태 | Accepted — 구현(브랜치 `fix/library-web-swap-compensation`) |
| 범위 | `LibraryReservationWebController.executeSwap`(+`compensateSwap`/`partialSwapFailure`) |
| 연관 | `ConfirmActionMcpTool.compensateSwap`(MCP 경로 원본 보상 로직) · 내부 분석에서 발견 |

---

## 배경 — 무슨 문제

좌석 이석(swap)은 upstream(Pyxis)에 **원자적 swap API가 없어** 2단계로 구현된다: ① 기존 좌석 discharge(반납) → ② 새 좌석 reserve(예약). MCP 경로(`ConfirmActionMcpTool`)는 ②가 실패하면 **기존 좌석을 재예약하는 보상(compensation)** 으로 사용자 상태를 복구한다(ADR · 외부 리뷰 #12). 그러나 **웹 컨트롤러(`LibraryReservationWebController.executeSwap`)에는 그 보상이 없었다** — ① 성공 후 ② 실패 시 `FAILED_RACE`/`FAILED_UPSTREAM`만 반환하고 끝. 결과: 사용자가 **기존 좌석도 잃고 새 좌석도 못 얻는** 실제 데이터 손실. "코어/MCP엔 적용했는데 형제 경로(웹)엔 미이식"의 전형.

## 결정

웹 `executeSwap`에 MCP와 **동일한 보상 로직**을 이식한다(중복이지만 두 경로가 같은 안전 보장을 갖도록; 공용 executor 추출은 호출 컨텍스트/응답타입이 달라 후속).

- ② reserve(new) 실패(`LibrarySeatNotAvailableException`·`RuntimeException` 양쪽) → `compensateSwap`: 기존 좌석(`oldSeatId`) **재예약 시도**.
- 보상 성공 → 감사 `OUTCOME_FAILURE_RACE` + `swapReserve(old)` 이벤트 재발행(discharge가 이미 좌석맵에서 비웠으므로) + 사용자에게 "기존 좌석 유지됨" 안내(status `FAILED_RACE`).
- 보상 실패 → 감사 `OUTCOME_PARTIAL_FAILURE` + "현재 보유 좌석 없음, 재예약 필요" 안내(status `FAILED_UPSTREAM`), warn 로깅(운영 가시성).
- `oldSeatId`이 null인 방어 케이스 → 곧장 partial-failure.

## 대안과 기각 이유

- **공용 swap executor로 MCP·웹 단일화** — 가장 깔끔하나 MCP는 `McpPrivateToolResponse<String>`(한국어 LLM 메시지), 웹은 `LibraryReservationConfirmResponse`(status 코드)로 응답·메시지 체계가 다르고 세션/감사 처리도 미묘하게 달라, 한 번에 추출하면 회귀 위험이 큼. **지금은 로직 미러**(동등 안전성 확보)하고 공용화는 후속 리팩터로 분리.
- **보상 없이 명확한 에러만** — 사용자가 좌석을 잃는 실제 피해를 방치 → 기각.
- **응답에 새 status 코드 추가** — 프론트(`ReservationConfirmModal`)가 `SUCCESS`/`PROCESSING` 외 전부를 "실패+메시지 표시"로 처리하므로, 기존 `FAILED_RACE`/`FAILED_UPSTREAM` 재사용이 안전(타입 드리프트·프론트 변경 0). 사용자 구분은 **메시지**로 전달.

## 동작 방식 / 검증

- 단위테스트(@WebMvcTest, 신규 — 기존엔 swap 경로 테스트가 0이라 갭이 숨어 있었음): ① reserve(new) 실패 → reserve(old) 성공 → status `FAILED_RACE` + 보상 reserve 호출 + `OUTCOME_FAILURE_RACE` 검증. ② reserve(new)·reserve(old) 둘 다 실패 → status `FAILED_UPSTREAM` + `OUTCOME_PARTIAL_FAILURE` 검증.
