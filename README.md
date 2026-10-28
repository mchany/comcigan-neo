# 컴시간neo

Kotlin과 Jetpack Compose Material 3로 제작한 컴시간알리미 비공식 오픈소스 Android 클라이언트입니다.

현재 배포 버전: **0.2.1-beta — 급식 조회 기능 및 시간표 화면 개선**. Android 8.0(API 26) 이상에서 설치할 수 있습니다.

개발 현황: **1.0.0 준비 중**. 초기 설정, 시간표·급식 알림, Material 3 홈 위젯을 구현했으며 아래 개발 빌드 안내에서 확인할 수 있습니다.

1.0.0 릴리즈 APK에는 R8 코드·리소스 축소를 적용합니다. [용량 축소 및 백그라운드 업데이트 검증](docs/apk-size.md)을 참고하세요.

[![APK 다운로드 · 0.2.1-beta](https://img.shields.io/badge/APK_다운로드-0.2.1--beta-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mchany/comcigan-neo/releases/download/v0.2.1-beta/comcigan-neo-0.2.1-beta.apk)
[![릴리즈 노트 · 0.2.1-beta](https://img.shields.io/badge/릴리즈_노트-0.2.1--beta-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/mchany/comcigan-neo/releases/tag/v0.2.1-beta)

배포용 서명을 적용한 APK이며, **나이스 인증키가 포함되어 별도 키 입력 없이 급식을 조회할 수 있습니다.**
APK를 내려받아 설치한 뒤 학교·학급을 선택하고, 급식 탭에서 급식 학교를 연결하세요.
0.1.0-beta와 동일한 배포 인증서로 서명했으며, APK의 SHA-256은 릴리즈에 첨부된 [SHA256SUMS.txt](https://github.com/mchany/comcigan-neo/releases/download/v0.2.1-beta/SHA256SUMS.txt)에서 확인할 수 있습니다.

## 구현한 기능

- 학교 검색 → 학년·반 선택 및 기기 내 저장
- 오늘 수업 목록과 다음 수업 시작 시각
- 이번 주·다음 주 시간표, 표/일별 목록 전환
- 변경 수업 배지와 변경 전후 상세
- Material You 동적 색상(Android 12 이상), 밝게/어둡게/시스템 테마
- Room 오프라인 시간표 캐시, 수동 새로고침, 5분 캐시 후 앱 사용 중 갱신
- 교사명 표시 설정, 캐시 삭제, 오픈소스 고지
- 나이스 학교 연결과 급식 탭의 날짜별 조식·중식·석식
- 화면 높이에 맞추는 오늘·주간 시간표, 과목·교사 한 줄 수업 목록
- 급식 메뉴·열량·알레르기 번호·영양·원산지, 주간 캐시와 오프라인 조회

즐겨찾는 여러 학급·교사용 시간표는 아직 구현하지 않았습니다.
시간표는 제공자가 게시한 자료에 한하며, 다음 주 미게시·서버 오류를 화면에서 구분합니다.
수업 종료 시각을 추측하지 않고 서버에서 제공한 시작 시각을 표시합니다.

## 초기 설정 (1.0.0 준비)

처음 설치하면 학교·학급 → 급식 학교 → 알림 설정 순서로 진행합니다. 급식 학교 연결은 나중에 설정할 수도 있습니다.
급식 탭의 중복 학교 선택 카드는 제거했으며, 연결 변경은 **설정 > 급식 > 연결 관리**에서 제공합니다. 기존 사용자의 학교·학급과 급식 연결은 유지합니다.

## 매일 알림 (1.0.0 준비)

워크스페이스의 개발 빌드에서는 첫 실행 시 학교·학급 → 급식 학교 → 알림 설정 순서로 진행합니다.
시간표와 급식 알림을 각각 켜거나 끄고 받을 시각을 설정할 수 있으며, 이후에는 **설정 > 알림**에서 변경합니다. 기본값은 두 알림 모두 꺼짐입니다.

- 시간표: **오늘의 시간표에요** 알림을 펼치면 당일 수업을 교시순으로 보여줍니다. 교사명 표시 설정을 따릅니다.
- 급식: **오늘의 급식이에요** 알림을 펼치면 메뉴가 등록된 조식·중식·석식만 보여줍니다.
- 시각은 매일 한국 시간 기준입니다. Android 13 이상에서는 알림 권한을 허용하고, 정확한 시각에 받으려면 **정확한 시각 허용**에서 알람 및 리마인더 접근을 허용하세요. 조회와 배터리 설정에 따라 늦어질 수 있습니다.
- 조회에 실패하면 해당 날짜의 저장된 자료와 마지막 조회 시각을 표시합니다. 저장된 자료도 없으면 조회 실패를 안내합니다.
- 알림을 누르면 각각 오늘 시간표·급식 탭을 엽니다. 재부팅·앱 업데이트·기기 시각 변경 후 예약을 다시 등록하며, 알림별 하루 중복 발송을 방지합니다.

## 홈 화면 위젯 (1.0.0 준비)

설정의 **위젯 > 홈 화면에 추가** 또는 런처의 위젯 목록에서 컴시간neo를 선택하세요.
오늘의 시간표·급식 각각 2×2와 4×2 기본 크기를 제공하며, 홈 화면에서 가로·세로 크기를 조절할 수 있습니다.

- 시간표: 작은 카드에 다음 시작 수업과 이후 수업을, 넓은 카드에 오늘 수업 목록을 함께 표시합니다. 서버가 제공한 시작 시각만 사용하며 종료 시각·수업 진행률을 추측하지 않습니다.
- 급식: 오늘 메뉴가 있는 조식·중식·석식만 표시합니다. 넓은 카드에서는 식사별 열로 나누며, 긴 시간표와 메뉴는 위젯 안에서 스크롤합니다.
- 색상: **앱과 동일**은 앱의 배경화면 기반 Material 색상을 따릅니다. 라벤더·로즈·민트·피치·블루도 선택할 수 있고, 밝기와 교사명 표시는 앱 설정을 따릅니다.
- 디자인: 앱과 같은 Material 3 색상 역할을 사용한 둥근 카드와 오늘·식사 칩으로 구성합니다. 시간표의 선택 교시는 부드러운 강조색으로, 급식은 식사별 내부 카드로 구분합니다.
- 카드를 누르면 오늘·급식 탭을 열고, ↻ 버튼으로 다시 조회합니다. 저장된 자료와 조회 시각을 먼저 표시하며, 자동 갱신은 기기의 절전 정책에 따라 지연될 수 있습니다.

## 빌드

JDK 17, Android SDK Platform 35, Build Tools 35.0.0이 필요합니다.
Android Studio에서 저장소를 열어 SDK를 지정하거나 로컬 `local.properties`에 `sdk.dir`을 설정하세요.
Gradle 8.11.1 래퍼와 SHA-256 검증값을 포함합니다.

```sh
./gradlew :core:model:test :core:comcigan:test :core:neis:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows에서는 `gradlew.bat`을 사용하세요. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
GitHub Actions도 같은 검증 후 디버그 APK를 artifact로 제공합니다. 배포용 서명 키는 포함하지 않습니다.

명시적으로 실서버 연결을 확인하려면 아래 명령을 실행하세요. 일반 테스트는 서버에 연결하지 않습니다.

```sh
./gradlew :core:comcigan:run --args="중일고등학교"
```

Android Studio 에뮬레이터에서 실제 서버를 사용하는 UI 검증은 별도 앱 ID로 실행합니다.
일반 앱의 학교·학급·테마 설정을 건드리지 않도록 `-PemulatorSmoke=true`일 때만 계측 테스트를 활성화합니다.

```sh
./gradlew :app:connectedDebugAndroidTest -PemulatorSmoke=true -Pandroid.testInstrumentationRunnerArguments.class=io.github.mchany.comciganneo.EmulatorSmokeTest
```

학교 선택, 주간 전환, 금요일 열 표시, 다크 모드, 교사명 설정과 Activity 재생성을 검증합니다.
일반 APK가 필요하면 `-PemulatorSmoke=true` 없이 다시 빌드하세요.

## 급식 설정

급식은 [나이스 교육정보 개방 포털](https://open.neis.go.kr)의 공식 API를 사용합니다.
**공식 릴리즈 APK에는 인증키가 포함되어 있습니다.** 사용자가 별도로 키를 발급하거나 입력할 필요가 없습니다.
직접 빌드할 때는 아래 방법으로 키를 설정하세요. 키 없는 호출은 5건 샘플로 제한되므로 앱은 이를 주간 전체 식단으로 사용하지 않습니다.

1. **급식 학교 연결**에서 검색 결과의 학교명·교육청·주소를 확인하고 연결합니다. 컴시간 학교 코드는 나이스 코드와 별도입니다.
2. 급식 탭에서 날짜와 조식·중식·석식을 선택합니다. 오늘 화면은 시간표에 집중합니다.

사용자에게 인증키를 입력받지 않고 빌드에 포함한 기본 키만 사용합니다. 예전 버전에서 직접 입력한 키는 사용하지 않습니다.

배포자가 기본 키를 넣으려면 환경 변수 `NEIS_API_KEY` 또는 Git에서 제외되는 루트 `neis.properties`에 설정합니다.

```properties
NEIS_API_KEY=발급받은_인증키
```

기본 키는 APK에서 추출할 수 있으므로
비밀 보관 수단이 아닙니다. 운영 한도·공유 정책을 확인하고 배포하세요. 키와 인증 URL은 로그에 기록하지 않습니다.

급식은 한 주씩 저장합니다. 식단이 있는 날짜는 6시간, 자료 없는 날짜는 1시간 뒤 앱 사용 중 갱신하고,
수동 새로고침으로 즉시 다시 조회할 수 있습니다. 자료 없음만으로 급식 미실시·휴교를 단정하지 않습니다.
API의 수정일자는 포털 연계 일자이므로 앱에는 별도로 마지막 성공 조회 시각을 표시합니다.

명시적인 Kotlin 실서버 검증은 다음 명령으로 실행합니다. 키가 없으면 해당 날짜의 샘플만 검사하고,
키가 있으면 이번 주 전체 조회를 검사합니다. 일반 테스트는 실서버에 연결하지 않습니다.

```sh
./gradlew :core:neis:run --args="중일고등학교"
```

급식 UI와 0.1.0-beta DB 업그레이드 검사는 별도 `.smoke` 패키지에서 실행합니다. UI 테스트는 합성 캐시를 사용합니다.

```sh
./gradlew :app:connectedDebugAndroidTest -PemulatorSmoke=true -Pandroid.testInstrumentationRunnerArguments.class=io.github.mchany.comciganneo.MealMigrationTest,io.github.mchany.comciganneo.MealUiTest
```

## 구조

| 모듈 | 역할 |
|---|---|
| `app` | Compose 화면, Material 3 테마, ViewModel |
| `core:model` | 학교·학급·수업·주차 모델 |
| `core:comcigan` | 순수 Kotlin/JVM 파서, OkHttp 클라이언트, 실서버 검증 CLI |
| `core:neis` | JDK 17/OkHttp 기반 나이스 학교·급식 API, 오류·완전성 검증 |
| `core:data` | Room 캐시와 DataStore 설정 |

앱의 화면은 `ui/`, 매일 알림은 `notifications/`, 홈 위젯은 `widgets/`로 나눕니다.
`NeoApp`은 화면 이동을 담당하고 초기 설정·설정 화면은 각각 별도 파일에 둡니다.
`core:data`에서는 Room 테이블·마이그레이션, 저장소, 사용자 오류 문구를 분리해 관리합니다.

파싱 로직은 [pycomcigan](https://github.com/hegelty/pycomcigan)의 MIT 코드를 참고해 Kotlin으로 이식했습니다.
요청 경로와 자료 필드 식별자는 서비스 페이지에서 추출하며 원격 JavaScript를 실행하지 않습니다.
빈 교시와 실제 교시 번호, 현재/원본 시간표, 변경 표시를 보존합니다.

컴시간 원본 연결은 HTTP `comci.net:4082`를 사용합니다. 해당 호스트에만 cleartext를 허용합니다.
앱 전용 백엔드·계정·광고·분석 SDK는 사용하지 않습니다. 선택한 학교·학급과 시간표는 앱 내부에 저장됩니다.
학교 검색어·학교 코드는 조회를 위해 컴시간 서버로 전송됩니다.
급식 검색어·교육청 및 표준학교코드·조회 기간·인증키는 HTTPS로 나이스 서버에 전송됩니다.
급식 클라이언트의 API 구조·필드 매핑은 MIT 라이선스의 [kimcore/neis.kt](https://github.com/kimcore/neis.kt)를 참고했습니다.
Ktor 런타임이나 JitPack 의존성을 추가하지 않고 기존 환경에 맞게 구현했습니다.

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

앱: [MIT](LICENSE). MIT에서 유래한 파서 부분의 원저작권·라이선스 고지는 보존합니다.
[서드파티 고지](THIRD_PARTY_NOTICES.md)를 참고하세요.
본 프로젝트는 컴시간 및 운영 주체와 공식적인 관계가 없습니다.
