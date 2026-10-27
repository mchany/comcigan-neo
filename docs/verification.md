# 검증 기록

## 0.2.1-beta — 오늘·주간 시간표 화면 압축 — 2026-10-27 KST

- 개발 버전 `0.2.1-beta` / versionCode `4`. `main`에 병합된 급식 기능 위에 미반영 화면 개선을 적용한다.

- 오늘의 급식 카드를 제거하고 수업 목록을 `과목 교사` 한 줄로 표시한다. 다음 수업 안내는 교시·과목·시작 시각을 담은 작은 두 줄 카드로 표시하며 높이가 짧은 화면만 한 줄로 압축한다.
- 급식 학교 변경, 오늘로 이동, 알레르기 번호 안내, 영양정보, 원산지, 자료 출처의 기본 버튼 들여쓰기를 없애 주변 텍스트와 왼쪽 시작선을 맞췄다. 에뮬레이터 캡처와 텍스트 좌표로 확인했다.
- 주간 표의 교시별 최소 100dp와 가로·세로 스크롤 의존을 없앴다. 유한한 화면 높이를 교시 수로 나누고, 표의 다섯 요일과 마지막 교시가 하단 탐색 위에 들어오도록 한다. 긴 이름은 생략 표시하며 수업 상세에서 원문을 볼 수 있다.
- 기존 `enableEdgeToEdge()`를 유지하고 safe drawing 인셋을 한 번씩 적용·소비한다. 학교 헤더와 하단 탐색을 줄이고 높이가 짧은 가로 화면은 측면 탐색으로 전환한다. 상태 표시줄 테마 적용 시 Activity를 감싼 Context도 처리한다.
- 인증키 입력 UI와 기기별 키 저장·사용 경로를 제거했다. 빌드에 포함한 키만 사용하며, 과거에 입력했던 기기 내 키는 내장 키를 덮어쓰지 않는다.
- `TimetableLayoutTest`는 320×400, 360×500, 411×700, 640×190, 800×900dp의 콘텐츠 영역과 글꼴 1.6배·2배 조건에서 오늘 8교시·주간 40개 셀이 모두 영역 안에 표시되는지 확인한다. 오늘 과목·교사의 실제 TextLayoutResult가 한 줄인지도 확인한다.
- `MealUiTest`는 급식 탭·상세·날짜 이동·화면 재생성을 유지하면서 오늘 배너 및 인증키 설정·입력 필드가 없음을 확인한다. 에뮬레이터 계측 테스트 3개 통과.
- 기존 JVM 테스트 24개, 일반 디버그 APK 빌드와 Android Lint 통과(오류 0, 기존 의존성 안내 경고 6개).
- 실제 에뮬레이터의 중일고등학교 2학년 1반 설정을 보존해 업데이트했다. 오늘의 7개 수업과 주간 35개 셀이 한 화면에 보이는 것을 캡처로 확인했다. 실기기 전체에 대한 검증을 의미하지는 않는다.
- 에뮬레이터를 실제 320×568dp로 변경한 뒤에도 주간 7교시 전체와 과목·교사가 표시되는 것을 확인하고 원래 1080×2400 / 420dpi 설정으로 복원했다.
- `neis.properties` 키로 해당 주 급식 9건 전체 조회 성공. 생성된 BuildConfig와 최종 APK의 내장 키가 파일과 일치함을 확인했으며 실제 키는 로그·소스·커밋에 포함하지 않았다.

## 0.2.0-beta 급식 기능 — 2026-10-27 KST

- Kotlin 2.1.20 / JDK 17 / Gradle 8.11.1 / AGP 8.9.2를 유지했다. 최소 SDK 26, 대상 SDK 35.
- `core:neis`는 kimcore/neis.kt 커밋 `555daa7d0c24fb98b45e3c2f18bfe3ffa3c11135`의 API 구조·필드 매핑을 참고하고 기존 OkHttp 4.12.0으로 구현했다. MIT 고지를 보존했다.
- `:core:model:test :core:comcigan:test :core:neis:test`: JVM 테스트 총 24개, 실패 0개. 기존 파서 9개, 기존 주차 1개, 급식 모델 2개, NEIS 파서·클라이언트 10개, HTTP 전송 2개.
- 확인 범위: API 오류/자료 없음 구분, 날짜·학교 검증, 페이지 전체 수집과 불완전 샘플 거부, 메뉴 숫자·알레르기 원문 보존, 누락 열량·영양 필드, 미지 식사 코드, UTF-8, 다운로드 중 취소, 오류에 인증 URL 미노출, 빈 날짜 캐시 만료·연말 주차.
- `:app:assembleDebug :app:lintDebug`: 일반 패키지 `io.github.mchany.comciganneo`, 버전 `0.2.0-beta` / code 3 생성. Lint 오류 0개. 의존성 최신 버전 안내 경고 6개는 유지했다.
- `:app:testDebugUnitTest`는 기존과 같이 NO-SOURCE이며 UI 검증은 아래 계측 테스트로 수행했다.

Android Emulator 37.2.12 / Medium Phone API 37.0 / Android 17에서 별도 `.smoke` 앱으로 다음 2개 테스트가 통과했다.

1. `MealMigrationTest`: 0.1.0-beta의 Room v1 테이블에 기존 시간표를 넣은 뒤 v2로 업그레이드했다. 시간표 보존, 새 급식 캐시 쓰기·읽기, 교육청 코드별 분리, 급식만 삭제할 때 시간표 유지 확인.
2. `MealUiTest`: 합성 캐시로 오늘 중식 카드, 급식 탭, 영양정보 펼치기, 석식 선택, 다음 날짜의 등록 정보 없음, 오늘 복귀, 다크 모드와 Activity 재생성 확인. 스크린샷 4개를 시각적으로 확인했다. 이 검사는 나이스 실서버 식단이나 인증키 검증을 대체하지 않는다.

실서버는 새 Kotlin CLI로 중일고등학교 검색 후 2026-10-27 당일 중식·석식 총 2건을 정상 조회했다. 인증키 없는 샘플 검사이며, 전체 주간은 키가 있어야 조회한다. 앞선 HTTP 직접 조사에서도 같은 학교의 해당 주 전체 9건 중 무키 응답이 5건으로 잘리는 것을 확인했다.

인증키는 현재 환경에 설정되지 않았다. **배포용 인증키로 전체 주간 수집, 운영 한도, 학교 연결의 실제 검색·확정 흐름을 확인하는 작업은 남아 있다.** 앱 내 인증키 설정과 `NEIS_API_KEY` 환경 변수 / Git에서 제외되는 `neis.properties` 주입을 지원한다. 실제 키를 소스·로그·커밋에 포함하지 않았다.

이번 기기 검증은 Android 17 에뮬레이터 한 대와 합성 급식 자료 기준이다. 실기기, Android 8~16, TalkBack, 큰 글꼴 및 서버 장애 장기 검증은 별도다. 키 없는 빌드가 자동으로 샘플 5건을 전체 식단처럼 표시하지 않는 것을 구현 원칙으로 유지한다.

확인일: 2026-10-27 (Asia/Seoul)
대상: 0.1.0-beta

## 자동 검증

아래 Gradle 작업이 Windows/JDK 17/Android SDK 35 환경에서 성공했다.

```sh
./gradlew :core:model:test :core:comcigan:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- JVM 회귀 테스트 **10개 통과**: 파서 9개, 모델의 연도·주 경계 1개.
- 디버그 APK 생성 성공.
- Android Lint 오류 0개. 의존성의 새 버전 제안 경고는 남겨 두었다.
- `app:testDebugUnitTest`에는 별도 JVM UI 테스트가 없으며 NO-SOURCE다. 에뮬레이터 UI 테스트는 아래 별도 항목으로 검증했다.
- Room 데이터베이스 v1 스키마를 저장소에 포함했다.

회귀 항목: 기존/래퍼형 교사 필드, 길이가 바뀐 요청 경로, MS949 검색어 인코딩,
검색 응답 NUL 패딩·동명이교, 세 자리 교사 코드, 과목 분리 값, 변경 전후 방향,
빈 교시 보존, 교시 수 메타데이터 이후의 패딩, 미게시·잘못된 응답·학급.

## Kotlin 실서버 조회

ComciganClient와 ComciganParser로 아래 명령을 실행했다. 문서 예시는 중일고등학교로 통일하고 베타 준비 중 재조회했다.

```sh
./gradlew :core:comcigan:run --args="중일고등학교"
```

| 학교·학급 | 주 시작일 | 수업이 있는 교시 | 변경 판정 |
|---|---|---:|---:|
| 중일고등학교 1-1 | 2026-10-26 | 34 | 2 |
| 중일고등학교 1-1 | 2026-11-02 | 29 | 7 |

변경 판정에는 현재 수업이 비어 있는 취소·변경 교시도 포함한다.

학교 검색과 두 주의 조회·파싱이 정상 종료됐다. 원본 전체 응답이나 교사명은 로그에 출력하지 않았다.
이전 Python 기준 구현 검증은 [별도 결과](research/python-live-check.json)에 보존했다.

## Android Studio 에뮬레이터 검증

Android Emulator 37.2.12 / Medium Phone API 37.0 / Android 17 / x86_64 / 1080×2400 기기에서 디버그 APK를 설치·실행했다.

- 학교 검색, 학급 선택, 오늘 시간표·변경 배지, 이번 주·다음 주, 일별 목록 전환 확인.
- 다크 모드와 교사명 표시 설정, Activity 재생성 후 상태 유지 확인.
- 비행기 모드에서 앱 프로세스를 종료하고 다시 시작해 Room 캐시가 표시됨을 확인.
- 오프라인 새로고침 실패 시 오류 안내와 기존 수업 목록이 함께 유지됨을 확인. 검증 후 네트워크 상태를 복원했다.
- UI 계측 테스트 1개가 여러 사용 흐름을 검증한다. 실제 서버를 사용하며 기본 JVM 테스트와 분리되어 있다.
- 최초 계측 실행에서 Espresso 3.6 계열의 InputManager.getInstance 반사 호출이 Android 17에서 실패했다. 공식 수정이 포함된 Espresso 3.7.0 및 AndroidX Test Runner 1.7.0 / JUnit 확장 1.3.0으로 변경했다.
- 기본 휴대폰 폭에서 금요일 열이 화면 밖으로 밀리는 현상을 수정했다. 일반 글자 크기는 다섯 요일이 함께 표시되고 큰 글자는 가로 스크롤을 유지한다.

테스트 도구의 실행 후 정리가 일반 앱 데이터에 영향을 주지 않도록 별도 `.smoke` 앱 ID를 사용한다.
일반 빌드에서는 Android 계측 테스트를 비활성화하고 `-PemulatorSmoke=true`로 명시적으로 실행한다.

```sh
./gradlew :app:connectedDebugAndroidTest -PemulatorSmoke=true -Pandroid.testInstrumentationRunnerArguments.class=io.github.mchany.comciganneo.EmulatorSmokeTest
```

에뮬레이터 검증 캡처와 상세 로그는 로컬 `work/`에 보관하고 공개 저장소에는 포함하지 않는다.
참고: [AndroidX Test 공식 변경 기록](https://developer.android.com/jetpack/androidx/releases/test)

## 0.1.0 베타 서명 APK

Android Studio Rabbit 1 (2026.2.1)의 **Generate Signed App Bundle or APK → APK → release**로 배포 키를 지정해 생성했다.
Studio의 Gradle JDK는 Eclipse Temurin 17.0.9로 설정했다. SDK 35와 Gradle 8.11.1을 사용했으며, CLI에서 `:app:lintRelease`도 통과했다.

- 패키지: `io.github.mchany.comciganneo`
- 버전: `0.1.0-beta` / versionCode `2`
- APK 크기: 12,373,549바이트
- 최소 SDK 26 / 대상 SDK 35 / 디버그 비활성화 확인
- `apksigner verify --verbose --print-certs`: 검증 성공, APK Signature Scheme v2, RSA 3072비트
- 서명 인증서 SHA-256: `4ec949d20e32a7b037d4c41071d162a6a014455d277a83aabaf6a274abf4e8ba`
- APK SHA-256: `435b4d71e240d9c1d969883b0a08fe90e026074d6e0801fa169acc1d5c81b573`

GitHub의 `v0.1.0-beta` 사전 릴리즈에 APK와 `SHA256SUMS.txt`를 첨부한다. 서명 키와 비밀번호는 저장소와 릴리즈에 포함하지 않는다.
위 에뮬레이터 실행 검증은 디버그 빌드에서 수행했다. 서명한 release APK의 기기 설치·실행 검증은 아직 수행하지 않았다.

## 아직 확인하지 않은 항목

- 실물 Android 기기, Android 8~16 버전, TalkBack 및 다양한 글꼴·화면 크기
- 공식 시간표 화면과 모든 교시를 대조하는 검증
- 학기 전환, 다양한 학교·서버 장애에 대한 장기 검증

위 항목은 JVM 실서버 검사나 APK 빌드 성공으로 대체하지 않는다.

## 로컬 빌드 환경

현재 작업 폴더의 한글 경로에서 Gradle 테스트 worker 클래스 로딩 오류가 발생해,
같은 폴더에 임시 ASCII 드라이브 경로를 연결하여 빌드했다.
JDK·SDK·Gradle 캐시·설치 스크립트는 프로젝트 로컬의 무시된 경로에 보관하고 커밋하지 않았다.
다른 개발 환경에서는 README의 일반 SDK 설치·Gradle 명령을 사용한다.
