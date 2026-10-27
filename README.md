# 컴시간neo

Kotlin과 Jetpack Compose Material 3로 만드는 컴시간알리미 비공식 오픈소스 Android 클라이언트입니다.

현재 버전: **0.1.0-alpha01 — 초안**. Android 8.0(API 26) 이상을 대상으로 합니다.

## 구현한 기능

- 학교 검색 → 학년·반 선택 및 기기 내 저장
- 오늘 수업 목록과 다음 수업 시작 시각
- 이번 주·다음 주 시간표, 표/일별 목록 전환
- 변경 수업 배지와 변경 전후 상세
- Material You 동적 색상(Android 12 이상), 밝게/어둡게/시스템 테마
- Room 오프라인 시간표 캐시, 수동 새로고침, 5분 캐시 후 앱 사용 중 갱신
- 교사명 표시 설정, 캐시 삭제, 오픈소스 고지

알림·위젯·즐겨찾는 여러 학급·교사용 시간표는 아직 구현하지 않았습니다.
시간표는 제공자가 게시한 자료에 한하며, 다음 주 미게시·서버 오류를 화면에서 구분합니다.
수업 종료 시각을 추측하지 않고 서버에서 제공한 시작 시각을 표시합니다.

## 빌드

JDK 17, Android SDK Platform 35, Build Tools 35.0.0이 필요합니다.
Android Studio에서 저장소를 열어 SDK를 지정하거나 로컬 `local.properties`에 `sdk.dir`을 설정하세요.
Gradle 8.11.1 래퍼와 SHA-256 검증값을 포함합니다.

```sh
./gradlew :core:model:test :core:comcigan:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows에서는 `gradlew.bat`을 사용하세요. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
GitHub Actions도 같은 검증 후 디버그 APK를 artifact로 제공합니다. 배포용 서명 키는 포함하지 않습니다.

명시적으로 실서버 연결을 확인하려면 아래 명령을 실행하세요. 일반 테스트는 서버에 연결하지 않습니다.

```sh
./gradlew :core:comcigan:run --args="경기북과학고"
```

Android Studio 에뮬레이터에서 실제 서버를 사용하는 UI 검증은 별도 앱 ID로 실행합니다.
일반 앱의 학교·학급·테마 설정을 건드리지 않도록 `-PemulatorSmoke=true`일 때만 계측 테스트를 활성화합니다.

```sh
./gradlew :app:connectedDebugAndroidTest -PemulatorSmoke=true -Pandroid.testInstrumentationRunnerArguments.class=io.github.mchany.comciganneo.EmulatorSmokeTest
```

학교 선택, 주간 전환, 금요일 열 표시, 다크 모드, 교사명 설정과 Activity 재생성을 검증합니다.
일반 APK가 필요하면 `-PemulatorSmoke=true` 없이 다시 빌드하세요.

## 구조

| 모듈 | 역할 |
|---|---|
| `app` | Compose 화면, Material 3 테마, ViewModel |
| `core:model` | 학교·학급·수업·주차 모델 |
| `core:comcigan` | 순수 Kotlin/JVM 파서, OkHttp 클라이언트, 실서버 검증 CLI |
| `core:data` | Room 캐시와 DataStore 설정 |

파싱 로직은 [pycomcigan](https://github.com/hegelty/pycomcigan)의 MIT 코드를 참고해 Kotlin으로 이식했습니다.
요청 경로와 자료 필드 식별자는 서비스 페이지에서 추출하며 원격 JavaScript를 실행하지 않습니다.
빈 교시와 실제 교시 번호, 현재/원본 시간표, 변경 표시를 보존합니다.

컴시간 원본 연결은 HTTP `comci.net:4082`를 사용합니다. 해당 호스트에만 cleartext를 허용합니다.
앱 전용 백엔드·계정·광고·분석 SDK는 사용하지 않습니다. 선택한 학교·학급과 시간표는 앱 내부에 저장됩니다.
학교 검색어·학교 코드는 조회를 위해 컴시간 서버로 전송됩니다.

## 검증과 제약

파서 회귀 테스트는 합성 데이터를 사용해 한글 인코딩, 서비스 페이지 변경, 세 자리 교사 코드,
변경 전후 방향, 빈 교시 보존, 주차 경계를 검증합니다.
실서버 검증과 기기 UI 검증 결과는 [검증 기록](docs/verification.md)에 구분해 기록합니다.
전체 제품 방향은 [앱 구상안](docs/android-app-proposal.md)을 참고하세요.

## 기여

빌드·테스트 명령과 [기여 안내](CONTRIBUTING.md)를 확인해 주세요.
파서 오류를 제보할 때는 학교·학급·조회 주차, 앱 버전, 재현 절차를 적어 주세요.
전체 원본 시간표나 개인정보가 포함된 응답은 공개 이슈에 첨부하지 마세요.

## 라이선스

앱: Apache-2.0. MIT에서 유래한 파서 부분의 저작권·라이선스는 별도로 보존합니다.
[서드파티 고지](THIRD_PARTY_NOTICES.md)를 참고하세요.
본 프로젝트는 컴시간 및 운영 주체와 공식적인 관계가 없습니다.
