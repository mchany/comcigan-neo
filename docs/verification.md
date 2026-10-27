# 초안 검증 기록

확인일: 2026-10-27 (Asia/Seoul)
대상: 0.1.0-alpha01

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

초안의 ComciganClient와 ComciganParser로 아래 명령을 실행했다.

```sh
./gradlew :core:comcigan:run --args="경기북과학고"
```

| 학교·학급 | 주 시작일 | 수업이 있는 교시 | 변경 판정 |
|---|---|---:|---:|
| 경기북과학고등학교 1-1 | 2026-10-26 | 34 | 2 |
| 경기북과학고등학교 1-1 | 2026-11-02 | 34 | 0 |

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
