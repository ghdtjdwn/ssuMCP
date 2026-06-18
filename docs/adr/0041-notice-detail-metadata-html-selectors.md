# ADR 0041: 공지 상세 메타데이터 HTML 셀렉터 보강

## 배경

`get_notice_detail`의 실데이터 경로는 `RealNoticeConnector.fetchDetail(url)`에서
SSU:catch 공지 상세 HTML을 Jsoup으로 가져온 뒤 본문만 파싱하고 있었다. 본문은
`div.bg-white > hr + div` 셀렉터로 정상 추출되지만, 응답의 `date`, `status`,
`department`, `category`는 빈 문자열로 하드코딩되어 상세 공지 응답에서 제목 외
메타데이터를 사용할 수 없었다. 제목도 상세 페이지 실제 제목 위치인 `h1`이 아니라
`h3`을 보고 있어 현재 상세 HTML에서는 비어 있을 수 있었다.

2026-06-18 기준 실측 출처:

- 목록: `https://scatch.ssu.ac.kr/공지사항/`
- 상세 예시: 목록의 최신 3개 상세 링크
  - `제16회 숭실 캡스톤디자인 경진대회 “융합팀” 모집 안내`
  - `(서울글로벌센터) 외국인 유학생 프로그램 참여자 모집 안내`
  - `30일간의 서울일주 4기 외국인 유학생 모집 안내`

## 대안

### 1. WordPress REST API 사용

`https://scatch.ssu.ac.kr/wp-json/wp/v2/posts` 계열 API는 DRA 정책으로
인증 사용자만 접근할 수 있어 HTTP 401 `rest_cannot_access`를 반환한다. 운영
커넥터가 공개 공지 조회만을 위해 관리자 인증을 요구하게 만들 수 없고, 현재 본문
수집도 HTML GET으로 동작하므로 이 경로는 배제한다.

### 2. 상세 페이지 HTML만 파싱

상세 페이지의 본문 카드(`div.bg-white.p-4.mb-5`) 안에 카테고리, 제목, 게시일이
반복적으로 존재한다. 본문 바로 앞의 같은 카드에서 추출하므로 목록 페이지와 상세
페이지 사이의 데이터 불일치가 없고, `fetchDetail(url)` 단독 호출에서도 동작한다.

### 3. 목록 페이지 메타데이터를 상세 응답으로 이월

목록 파서는 이미 `date`, `status`, `department`, `category`를 추출한다. 특히
부서는 목록에는 있지만 상세 HTML에는 없다. 다만 `fetchDetail(url)`은 URL만 입력으로
받고, 호출 시점에 해당 URL이 최신 목록 페이지에 남아 있다는 보장이 없다. 매 상세
호출마다 목록을 재탐색하면 오래된 상세 URL에서 실패하거나 불필요한 네트워크 비용이
생긴다. 따라서 이번 수정의 기본 소스는 상세 HTML로 두고, 상세 HTML에 없는
`status`와 `department`는 빈 값으로 유지한다.

## 결정

상세 페이지 HTML을 우선 소스로 사용한다. REST API는 접근 차단 때문에 사용하지
않고, 목록 이월은 `fetchDetail(url)`의 독립성과 오래된 URL 호환성을 해칠 수 있어
이번 범위에서 채택하지 않는다.

HTML GET은 실측에 사용한 것과 같은 데스크톱 브라우저 User-Agent를 사용한다.

## 동작 방식

신규 셀렉터는 `ssuai.notice.selectors` 설정으로 노출한다.

- `detail-title`: `div.bg-white.p-4.mb-5 > h1`
- `detail-date`: `div.bg-white.p-4.mb-5 div.clearfix > div.float-left:has(i.ion-ios-calendar)`
- `detail-category`: `div.bg-white.p-4.mb-5 > span.label`
- `detail-department`: 빈 문자열 기본값. 현재 상세 HTML에 부서 필드가 없어 예약만 한다.
- `detail-body`: 기존 `div.bg-white > hr + div` 유지

게시일은 상세 HTML에서 `2026년 6월 18일` 형태로 내려오므로 기존 목록용
`2026.06.18` 정규화와 함께 `yyyy년 M월 d일` 형식을 `yyyy-MM-dd`로 변환한다.

테스트는 네트워크를 사용하지 않는다. `src/test/resources/fixtures/notice/notice_detail_metadata.html`
에 실측 상세 카드 구조를 보존한 fixture를 저장하고, `RealNoticeConnector.parseNoticeDetail`
이 제목, 게시일, 카테고리, 본문을 정확히 추출하는지 검증한다.

## 한계

현재 상세 페이지에는 진행 상태와 담당 부서가 없다. 따라서 이번 수정 후에도
`status`와 `department`는 상세 HTML 소스 기준으로 빈 값이 맞다. 부서까지 항상
채우려면 상세 응답 경로를 로컬 공지 인덱스와 결합하는 별도 설계가 필요하다.
