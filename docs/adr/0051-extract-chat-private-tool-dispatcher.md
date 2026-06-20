# ADR 0051 - 채팅 비공개 도구 디스패치 분리

- **상태**: Accepted - 2026-06-20 구현
- **날짜**: 2026-06-20
- **범위**: `LlmChatService`의 u-SAINT/LMS/도서관 비공개 도구 인프로세스 실행

## 배경

`LlmChatService`는 LLM 대화 루프뿐 아니라 도구 검색, 인자 해석, 공개 MCP 호출, 비공개 도구 직접 실행까지 담당해 957줄에 이르렀다. 앞선 리팩터링에서 공급자 fallback은 `LlmProviderChain`, 시스템 프롬프트 구성은 `SystemPromptBuilder`, 도구 결과 축약은 `ToolResultCompactor`로 이미 분리했지만, u-SAINT/LMS/도서관의 9개 서비스 의존성과 세 도메인의 세션·감사 로그·예외 변환은 여전히 한 클래스에 결합돼 있었다.

비공개 도구는 MCP loopback을 거치면 servlet thread가 달라져 `ThreadLocal` 인증 문맥을 전달할 수 없으므로 현재 채팅 thread에서 서비스를 직접 호출해야 한다. 따라서 이번 작업은 실행 방식을 바꾸는 기능 개선이 아니라, 동기 인프로세스 실행이라는 기존 제약을 유지하면서 책임과 의존성의 소유자만 옮기는 순수 리팩터링이다.

Martin Fowler의 Extract Class 카탈로그는 한 클래스가 둘 이상의 책임을 수행할 때 관련 데이터와 함수를 별도 클래스로 묶고 원래 클래스가 위임하도록 설명한다. Spring Framework 문서도 필수 협력 객체를 생성자 인자로 명시하는 방식을 지원한다. 이 두 원칙을 현재 구조에 적용하되, 동작 보존을 최우선 제약으로 두었다.

## 결정

새 Spring 컴포넌트 `ChatPrivateToolDispatcher`를 만들고 다음 책임을 이동한다.

- `SaintScheduleService`, `SaintGradesService`, `SaintChapelService`, `SaintGraduationService`, `SaintScholarshipService`, `SaintGpaSimulationService`
- `LmsAssignmentsService`
- `LibrarySeatService`, `LibraryLoansService`
- 기존 `dispatchPrivateSaintTool`, `dispatchPrivateLmsTool`, `dispatchPrivateLibraryTool`
- 세 도메인 전용 로그인·세션 만료 안내 문자열과 서비스별 직접 호출

`LlmChatService.executeToolCall(...)`은 계속 유일한 도구 실행 진입점이다. 기존 switch에서 인자를 같은 순서로 해석한 뒤 비공개 도구만 `ChatPrivateToolDispatcher`의 도구별 메서드에 위임한다. 공개 도구의 MCP 호출 경로는 변경하지 않는다.

`ObjectMapper`, `ToolResultCompactor`, `toolError(...)`는 공개 도구 및 다른 채팅 로직과 공유되므로 옮기지 않고 호출 시 전달한다. 이 방식은 공유 책임을 새 컴포넌트에 중복시키지 않으며, 기존 JSON 직렬화·축약·오류 JSON 생성 구현을 그대로 사용한다.

## 대안과 기각 이유

- **현 구조 유지**: 변경 위험은 없지만 9개 도메인 서비스가 대화 오케스트레이터에 계속 직접 결합되고, 이미 시작한 god-class 축소가 비공개 도구 경계에서 멈추므로 기각했다.
- **세 개의 공통 helper만 옮기고 서비스 호출 lambda는 `LlmChatService`에서 만든다**: 코드 일부는 줄지만 9개 서비스 의존성과 각 도구의 직접 호출 책임이 원래 클래스에 남아 응집된 책임 추출이 되지 않으므로 기각했다.
- **도구 인자 해석까지 dispatcher로 옮기거나 복제한다**: `optionalArgument`, 숫자 변환, JSON 해석 helper는 공개 MCP 경로와 공유된다. 이를 이동하면 공개 경로가 새 컴포넌트에 의존하고, 복제하면 같은 입력의 해석 결과가 갈라질 수 있어 순수 리팩터링 범위를 넘으므로 기각했다.
- **비공개 도구도 loopback MCP로 통일한다**: 호출 형태는 단순해지지만 servlet thread 경계를 넘으며 현재 `ThreadLocal` 인증 문맥이 소실된다. 인증 동작을 바꾸므로 기각했다.

## 동작 보존 장치

1. 도구명 switch와 필수·선택 인자 해석 순서를 `LlmChatService`에 그대로 유지했다.
2. 인증 및 library session 확인 후에만 service supplier를 실행하는 지연 호출 순서를 유지했다.
3. 안내 문자열, 오류 JSON 생성, fingerprint 계산, 감사 로그 template, 예외별 변환, `compactAndCap` 호출 순서를 변경하지 않았다.
4. 새 컴포넌트에도 기존과 같은 `ssuai.connector.chat=llm` 조건을 적용했다.
5. 로그 category를 `LlmChatService`로 유지해 운영 로그 필터와 대시보드 동작도 바꾸지 않았다.
6. 비동기 처리나 별도 executor를 도입하지 않아 기존 `ThreadLocal` 문맥이 같은 thread에서 유지된다.

`LlmChatService`의 물리 줄 수는 957줄에서 751줄로 감소했다. 전체 코드 줄 수 최소화보다 대화 오케스트레이션과 비공개 도구 실행의 변경 이유를 분리하는 것을 우선했다.

## 검증

Windows 환경에서 `./gradlew.bat test`에 해당하는 전체 Gradle `test` task를 실행했다.

- 903 tests
- 0 failures
- 0 skipped
- 성공률 100%
- Gradle 결과: `BUILD SUCCESSFUL`

테스트 코드는 제품 동작 기대값을 바꾸지 않았고, 기존 package-private 생성자에 9개 mock service를 묶은 dispatcher를 전달하도록 fixture wiring만 수정했다.

## 참고 자료

- Martin Fowler, [Extract Class](https://refactoring.com/catalog/extractClass.html)
- Spring Framework Reference, [Dependency Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)

## 예상 면접 질문

1. 비공개 도구를 공개 도구처럼 MCP loopback으로 호출하지 않고 인프로세스 dispatcher로 유지한 이유는 무엇인가?
2. `ObjectMapper`와 `ToolResultCompactor`를 dispatcher 소유로 함께 옮기지 않고 호출 인자로 전달한 이유는 무엇인가?
3. 순수 리팩터링에서 응답 문자열뿐 아니라 로그 category와 service supplier 실행 순서까지 보존해야 하는 이유는 무엇인가?
