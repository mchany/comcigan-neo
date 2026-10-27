# 컴시간neo Android 앱 구상안

조사·실서버 확인: 2026-10-27 16:46 KST. 이 문서는 구현 전 제안서이며 APK 또는 Kotlin 앱을 만든 상태는 아니다.

## 제안

Kotlin + Jetpack Compose Material 3로 학생용 시간표 클라이언트를 만든다. 컴시간 서버에 직접 접속하고 로컬에 시간표를 저장한다. 파서는 순수 Kotlin/JVM 모듈로 분리한다. 기준 구현은 MIT 라이선스의 hegelty/pycomcigan master 커밋 cf738ee8945ef3c435cf1abf72e43ee90e32d1a7을 사용한다.

Python 런타임이나 Node 런타임을 앱에 넣는 대신 파싱 알고리즘을 Kotlin으로 옮긴다. 초기 버전은 학생용 학급 시간표에 집중한다.

## 파서 조사 결과

| 후보 | 언어·라이선스 | 확인 결과 | 판단 |
|---|---|---|---|
| hegelty/pycomcigan | Python·MIT | 현재 소스의 학교 검색, 이번 주·다음 주 시간표 조회 성공. timetable.py 마지막 수정은 2026-07-11, 최신 저장소 커밋은 2026-09-27 | Kotlin 이식의 기준으로 추천 |
| Pro203S/parse-comcigan | TypeScript·MIT | 1.1.2 커밋은 2026-06-10. 학생 시간표 지원. 요청 경로·자료 필드 번호 하드코딩, 8교시 반복 확인. 패키지 실행은 미검증 | 보조 비교 자료 |
| myoun/comcigan-kt | Kotlin·명시적 LICENSE 파일 미발견 | master 최근 커밋 2023-10-15. 현재 서비스 페이지에서 교사 데이터 정규식 매칭 실패. 동기 네트워크 접근이 singleton 초기화에 포함됨 | 그대로 의존하지 않음 |
| star0202/comcigan.ts | TypeScript·LGPL 계열 | main 최근 커밋 2024-11-02. 현재 서비스 페이지에서 교사 데이터 정규식 매칭 실패 | 그대로 의존하지 않음 |
| leegeunhyeok/comcigan-parser | Node.js·MIT | 원격 페이지 스크립트 eval과 HTML 재파싱 방식. 현재 라이브러리 전체 실행은 미검증 | Kotlin 신규 구현 기준으로 우선하지 않음 |
| son-daehyeon/Comcigan-API | Java·이번 조사에서 라이선스 미확인 | 학생·교사 시간표, 변경 정보 지원을 README에 설명. 2026-10-20 보관 처리. 실행 미검증 | 교사용 확장 시 참고 |

GitHub pushed_at은 기본 브랜치 소스 수정일과 다를 수 있다. 위 날짜는 가능한 경우 기본 브랜치 커밋 날짜를 사용했다.

소스:
- https://github.com/hegelty/pycomcigan
- https://github.com/hegelty/pycomcigan/commit/584e93a331ca6d4464955d2eb32eead4d68cb72b
- https://pypi.org/project/pycomcigan/1.4.1/
- https://github.com/Pro203S/parse-comcigan/blob/f586631532be61b59ccb42476dd0bd933c342bf2/src/class.ts
- https://github.com/myoun/comcigan-kt/blob/c6ae0ce4cbfd4a4bb29d6df9805686dae806b4c4/src/main/kotlin/app/myoun/comcigan/ComciganUtil.kt
- https://github.com/star0202/comcigan.ts/blob/d34611241dd0f82c63d634a480e097f2f10764d9/src/constants.ts
- https://github.com/leegeunhyeok/comcigan-parser/blob/30bf17e5908fc3299f3ecbb2a3294f1d6de9d370/index.js
- https://github.com/son-daehyeon/Comcigan-API

## 실서버 검증 범위

docs/research/python-live-check.json에 확인 시각과 결과를 기록했다.

| 학교·학급 | 주 시작일 | 과목이 있는 수업 수 | 파서가 감지한 변경 |
|---|---|---:|---:|
| 경기북과학고등학교 1-1 | 2026-10-26 | 34 | 2 |
| 경기북과학고등학교 1-1 | 2026-11-02 | 34 | 0 |
| 오현중학교 1-1 | 2026-10-26 | 32 | 14 |

원본 helpers.py와 timetable.py의 파싱 코드를 실행했다. 환경에 requests 패키지가 없어 HTTP 전송 부분만 로컬 requests.py의 urllib 어댑터로 대체했고, 요청 제한 시간을 설정했다. PyPI 배포 패키지를 그대로 설치한 테스트나 Kotlin 포트 테스트는 아니다. 과목 문자열, 주 시작일, 비어 있지 않은 수업 개수와 변경 판정을 확인했으며 공식 화면과 모든 교시를 대조하지는 않았다. 다른 학교·주차와 향후 동작은 별도 검증해야 한다.

같은 시점의 /st 응답에 대해 myoun과 star0202의 교사 필드 정규식은 불일치했고 Q성명 래퍼를 허용하는 패턴은 일치했다. 이는 해당 소스의 초기화 단계 호환성 문제를 보여준다.

## 파서 구현 원칙

1. 서비스 페이지에서 요청 경로, 요청 접두사, 교사·과목·현재 시간표·원본 시간표·갱신일 필드 식별자를 추출한다. 현재 번호를 Kotlin 상수로 고정하지 않는다.
2. 한글 검색어 URL 인코딩과 응답 디코딩을 분리한다. 참고 구현은 서비스 페이지를 cp949로 읽고 검색·시간표 JSON을 UTF-8로 읽는다. Android에서 대응 charset과 확장 한글을 테스트한다.
3. Base64 요청 생성은 별도 함수로 분리한다.
4. 수업 코드는 단순 문자열 뒤 두 자리로 쪼개지 않는다. 현재 참고 구현은 마지막 세 자리를 교사 인덱스로 사용하고, 앞 부분에 서버의 분리 값을 적용해 과목 인덱스를 구한다.
5. 변경 수업의 > 접두사, 변경알림 설정, 현재·원본 배열의 방향을 보존한다.
6. 빈 교시를 제거해 이후 수업이 당겨지지 않도록 날짜·교시 번호를 명시적으로 저장한다. 반 수·학년 수·교시 수는 서버 메타데이터를 따른다.
7. 교시 시작 시각만 있는 경우 종료 시각을 임의로 사실처럼 표시하지 않는다. 수업 길이 사용자 설정이 있으면 추정 표시를 붙인다.
8. 원본 이름 마스킹을 존중하고 UI에서 임의로 이름을 복원하지 않는다. 과목 약칭도 원문을 기본으로 표시한다.
9. 네트워크 실패, 빈 시간표, 다음 주 미게시, 필드 구조 변경을 서로 다른 오류로 처리한다.
10. 원격 JavaScript를 실행하지 않고 필요한 데이터와 식별자만 읽는다.

## 화면과 사용 흐름

첫 실행: 학교명 검색 → 지역과 학교명 확인 → 학년·반 선택 → 오늘 화면. 계정은 요구하지 않는다.

하단 탐색은 오늘 / 주간 / 설정 세 곳으로 시작한다. 학급 전환은 상단 학급 선택 칩과 하단 시트로 처리한다.

### 오늘

- 상단: 학교·학급, 날짜, 마지막 동기화 시각.
- 대표 카드: 현재 또는 다음 수업. 서버의 일과 시간과 로컬 시각을 사용한다.
- 아래: 하루 수업 목록. 교시·과목·교사와 변경 여부를 표시한다.
- 변경 수업은 색상 외에 변경 배지와 텍스트를 사용한다. 탭하면 원래 수업 → 현재 수업을 보여준다.
- 주말·방학·수업 종료·아직 데이터 없음 상태를 별도로 설계한다.
- 통신 실패 시 저장된 시간표와 마지막 성공 시각을 표시한다.

### 주간

- 이번 주 / 다음 주 선택, 오늘로 이동, 주 시작일 표시.
- 월~금 시간표 표. 날짜와 교시 머리글을 구별하고 과목명 가독성을 우선한다.
- 셀 탭 시 상세 하단 시트. 긴 과목명, 교사명, 변경 전후 내용 표시.
- 글자가 커지면 일별 목록 보기로 전환할 수 있다.
- 다음 주 데이터가 없으면 미게시로 표시하고 이번 주 자료를 다음 주로 복제하지 않는다.

### 설정

- 학교·학급, 즐겨찾는 학급.
- 시스템/밝게/어둡게 테마, 동적 색상/기본 테마 선택.
- 교사명 표시, 동기화 주기와 변경 알림.
- 저장 자료 삭제, 오픈소스 고지, 소스 저장소, 비공식 클라이언트 안내.

## Material You 디자인

Compose Material 3를 사용한다. Android 12 이상은 시스템 동적 색상을 지원하고 하위 버전은 기본 색상 팔레트를 제공한다. 배경은 surface 계열, 현재 수업은 primaryContainer, 변경 정보는 일관된 강조 토큰을 사용한다. 과목별 색상은 선택 기능으로 두고 기본은 차분하게 구성한다.

둥근 카드, 적절한 여백, 큰 날짜·현재 수업 타이포그래피를 사용하되 주간 표의 정보 밀도를 유지한다. TalkBack 읽기 순서, 글꼴 확대, 48dp 터치 영역을 검증한다. 태블릿·가로 화면은 오늘 목록과 주간 표를 함께 보는 구성을 후속 지원한다.

근거:
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/ui/compose/accessibility

## 기술 구조 제안

- Kotlin, Jetpack Compose, Material 3, Navigation Compose.
- ViewModel + StateFlow의 단방향 상태 흐름.
- OkHttp + kotlinx.coroutines로 네트워크 처리.
- kotlinx.serialization으로 JSON 읽기 및 내부 모델 정규화.
- Room에 시간표와 동기화 이력, DataStore에 학교·학급·테마 설정 저장.
- WorkManager로 주기 동기화, Glance 위젯은 후속 버전.
- 최소 지원 Android 8.0 제안. 실제 의존 라이브러리의 지원 범위를 프로젝트 생성 시 확인한다.

초기 Gradle 모듈은 :app, :core:model, :core:comcigan, :core:data 정도로 제한한다. 파서는 Android Context·UI와 분리해 JVM에서 검증 가능하도록 한다.

데이터 흐름:
Compose → ViewModel → TimetableRepository → Room
TimetableRepository → Kotlin 파서 → OkHttp → 컴시간 서버

내부 모델:
- School: 컴시간 학교 코드, 지역, 학교명.
- ClassSelection: 학교 코드, 학년, 반.
- TimetableSnapshot: 학교 코드, 주 시작일, 서버 갱신일, 가져온 시각, 파서 버전.
- LessonSlot: 날짜, 교시, 현재 수업, 원본 수업, 원본 변경 표시, 수업 상태.
- Lesson: 과목명, 교사명, 제공되는 경우 강의실.
- PeriodTime: 시작 시각, 제공되는 경우 종료 시각.

실서버는 HTTP comci.net:4082를 사용했다. Android에서는 해당 호스트에 한정한 Network Security Config를 설계하고 실제 단말에서 접속을 검증한다. 네이티브 앱의 직접 HTTP 요청에는 브라우저 CORS 제약이 적용되지 않으므로 CORS 때문에 프록시를 추가할 필요는 없다.

근거:
- https://developer.android.com/privacy-and-security/security-config
- https://developer.android.com/topic/architecture/data-layer/offline-first

## 동기화·변경 알림

화면에서는 로컬 데이터를 먼저 읽고 캐시가 오래됐으면 새로 가져온다. 수동 새로고침은 짧은 중복 요청 제한을 둔다. 학교 전체 원본 조회 한 번을 여러 학급 화면에서 공유한다.

백그라운드 기본 주기는 60분으로 제안하고 30/60/120분 선택을 제공한다. WorkManager 주기 작업은 최소 15분이며 OS 상황에 따라 지연된다. 따라서 실시간 변경 알림이나 정확한 시각의 실행을 약속하지 않는다.

원본의 변경 표시와 사용자가 직전 동기화 이후 새로 발견한 변경을 구별한다. 최초 조회·학급 변경·주차 전환 때 기준 스냅샷을 만들고, 같은 학교·학급·주차의 후속 자료를 비교한다. 알림은 실제 과목·교사·수업 상태 변경에만 발생하고 중복을 방지한다. Android 알림 권한은 해당 기능을 켤 때 요청한다.

근거:
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/develop/ui/views/notifications/notification-permission

## 개발 순서와 완료 기준

1. 파서 검증 단계: Kotlin 포트, 중학교·고등학교 표본, 이번 주·다음 주, 변경 수업, 공강·휴강, 8교시 이상, 동명이교 검색, 한글 인코딩 확인. 실제 데이터와 공식 화면의 교시별 대조 후 UI 구현에 연결한다.
2. 첫 출시: 학교·학급 선택, 오늘/주간, 동적 색상·다크 모드, 로컬 캐시, 수동 새로고침, 오류·미게시 상태.
3. 편의 기능: 주기 동기화, 변경 이력·알림, 즐겨찾는 학급, 홈 화면 위젯.
4. 후속 확장: 태블릿, 이미지 공유, 교사용 시간표. 급식·학사일정은 별도 데이터 제공자가 필요한 별도 범위다.

파서 회귀 테스트는 익명화한 응답 fixture로 실행한다. 교사 코드 세 자리, 과목 분리 값, > 표시, 현재/원본 방향, 빈 교시 보존, 학기·주 경계가 핵심이다. 실서버 검사는 로컬 회귀 테스트와 구분하고 낮은 빈도로 운용한다.

캐시 후 비행기 모드에서 시간표를 볼 수 있고, 실패한 동기화가 저장 데이터를 지우지 않으며, 변경 알림이 최초 로딩에 쏟아지지 않는 것을 출시 기준에 포함한다.

## 오픈소스 운영

앱 자체 라이선스는 Apache-2.0을 후보로 제안한다. pycomcigan에서 옮긴 부분의 MIT 저작권·라이선스 고지는 보존한다. Kotlin 레퍼런스 저장소는 명시적 라이선스를 발견하지 못했으므로 코드를 복사하는 기반으로 사용하지 않는다.

프로젝트에 README, 기여 안내, 변경 이력, 의존성 고지와 파서 고장 제보 양식을 둔다. 초기 배포는 GitHub Releases APK를 제안한다. 추후 F-Droid 배포를 고려하면 독점 분석·광고 SDK 없이 구성하고 빌드 재현성을 관리한다.

## 남은 검증

실제 Kotlin/Android 네트워크·charset 동작, 현재 화면과 교시별 일치, 더 다양한 학교·비정상 응답, 다음 주 미게시·학기 전환은 아직 확인하지 않았다. 참고 파서의 최소 8교시 배열 생성 등 편의 동작은 앱 데이터 모델에 그대로 고정하지 않는다.
