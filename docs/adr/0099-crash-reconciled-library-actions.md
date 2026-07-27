# ADR 0099 — 프로세스 중단 후 도서관 action 재조정

- 상태: 채택 (2026-07-27)
- 관련: 0059(reservation audit SoT), 0086(confirm action), 0098(provider write fence)

## 배경

MCP 좌석 반납과 변경은 provider credential 철회와 경쟁하지 않도록 MCP session row lock을 잡은
트랜잭션 안에서 upstream write를 실행했다. 이 트랜잭션에 `action_audit`의 `EXECUTING` 전이와 종료
기록도 참여했기 때문에, upstream 반납 성공 뒤 프로세스가 종료되면 DB 트랜잭션만 rollback될 수 있었다.
특히 swap은 기존 좌석 반납과 새 좌석 예약 사이에 중단되면 사용자가 좌석을 잃고도 audit이 끝나지 않는다.

외부 Pyxis write API는 idempotency key나 원자적 swap을 제공하지 않는다. 동일 write를 무조건 재시도하는
방식은 중복 효과를 만들 수 있으므로 사용할 수 없다.

## 결정

1. MCP action claim과 terminal update는 `REQUIRES_NEW` 트랜잭션으로 커밋한다. provider session fence의
   바깥 트랜잭션이 rollback돼도 `EXECUTING`과 최종 결과가 남는다.
2. 1분 넘게 `EXECUTING`인 MCP library action을 주기적으로 찾는다. 재조정은 현재 provider generation을
   session row lock 아래 다시 검증하고, action이 여전히 실행 중인지 확인한 뒤 시작한다.
3. upstream `getCurrentCharge`를 권위 있는 관찰 지점으로 쓴다.
   `Optional.empty()`는 upstream이 `success=true`와 함께 명시적 `success.noRecord` 또는 빈 목록을
   반환한 경우에만 허용한다. 현재 charge는 양의 정수 `id`가 확인된 payload만 신뢰하며,
   timeout·5xx·실패 플래그·형식 불명 payload는 모두 미확정 상태로 전파한다.
   timeout, 5xx, 인증, rate limit, 알 수 없는 `success=false`, malformed body는 예외로 전파해 아래 상태
   판단이나 보상 write를 실행하지 않는다.
   - cancel: 기존 charge가 없으면 성공으로 확정한다. 그대로 있으면 discharge를 안전하게 재실행한다.
   - swap: 새 좌석이면 성공, 기존 좌석이면 복구된 실패로 확정한다. 예약이 없으면 기존 좌석 재예약을
     보상 시도하고, 그것도 실패하면 `PARTIAL_FAILURE`로 명시한다.
   - reserve: linked intent가 있으면 기존 worker가 계속 소유한다. intent가 커밋되지 않았다면 안전한
     사용자 재시도를 위해 실패로 종료한다.
4. 여러 pod가 같은 후보를 읽어도 provider session fence가 순서를 만들고, action 종료는 row lock 아래
   `EXECUTING`일 때만 수행한다. 두 번째 실행자는 upstream write 전에 terminal 상태를 보고 종료한다.
5. upstream 조회 결과가 불명확하거나 일시 오류이면 성공을 추측하지 않고 `EXECUTING`을 유지해 다음
   pass에서 다시 관찰한다. 결과별 metric은 `library.action.reconciliation{result=...}`로 기록한다.
6. 동기 cancel/swap write의 timeout, 5xx, malformed acknowledgement 등도 적용 여부가 불명확하다.
   이 경우 terminal 실패를 기록하거나 즉시 보상하지 않고 `EXECUTION_PENDING`을 반환한다. action은
   `EXECUTING`으로 남고 같은 reconciliation 경로가 실제 현재 charge를 확인한다. 사용자는 동일 write를
   반복하지 않도록 안내받는다.

## 대안

- 모든 write를 무조건 재시도: idempotency 계약이 없어 중복 반납·예약 위험이 있으므로 기각했다.
- DB transaction을 upstream 호출 동안 열어 두기만 함: process death와 네트워크 분할을 해결하지 못한다.
- swap 전체를 단일 DB transaction으로 모델링: DB 원자성이 외부 학교 시스템까지 확장되지 않는다.
- 운영자가 수동으로 모든 `EXECUTING` 행을 정리: 발견이 늦고 사용자 상태를 추측하게 되므로 기각했다.

## 검증과 한계

단위 테스트는 logout/write fence 직렬화, durable completion 멱등성, cancel 관찰, swap 보상, intent 누락,
조회 실패와 write 결과 불명확 시 무작업 유지까지 검증한다. connector contract test는 timeout·5xx·알 수
없는 payload가 `Optional.empty()`로 축소되지 않는지 확인한다. 최종 게이트는 전체 Gradle test와 JaCoCo다.
실제 upstream은 idempotency와 강한 일관성을
보장하지 않으므로 프로세스 종료 직후 짧은 관찰 지연은 남는다. production process-kill drill은 실제
사용자 좌석을 바꾸므로 별도 승인 후 수행한다.

## 면접에서 설명할 질문

- DB transaction만으로 외부 API write의 exactly-once를 만들 수 없는 이유는 무엇인가?
- idempotency key가 없는 API에서 retry보다 read-after-write reconciliation이 안전한 경우는 언제인가?
- 두 reconciler가 동시에 실행돼도 중복 write를 막는 경계는 어디인가?
