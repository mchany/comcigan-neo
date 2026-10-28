# 1.0.0 APK 용량 축소

[Android 공식 가이드](https://developer.android.com/topic/performance/reduce-apk-size?hl=ko)의 코드·리소스 축소 방식을 적용한다. APK 직접 설치 배포를 유지하며 App Bundle이나 기기별 별도 APK를 만들지 않는다.

## 적용 범위

- 릴리즈에서 R8 최적화·난독화와 미사용 리소스 축소를 켠다. 디버그는 기존처럼 축소하지 않는다.
- 사용하지 않는 Compose 미리보기 의존성은 디버그에서만 포함한다. 라이브러리의 언어별 리소스는 한국어·영어와 기본 리소스로 한정한다.
- 벡터 아이콘·위젯 배경·오픈소스 고지는 유지한다. 지원 CPU ABI를 제거하지 않고 네이티브 라이브러리의 APK 직접 로딩을 유지한다.
- 버전은 `1.0.0`, versionCode는 이전에 제공한 1.0.0 APK의 `5`보다 높은 `6`이다. 키·서명 파일은 소스에 포함하지 않는다.

이전 서명 APK는 **12,775,713바이트**이며 압축된 DEX가 12,054,857바이트로 약 94%를 차지했다. 첫 R8 일반 빌드의 서명 전 APK는 **1,773,276바이트**다. 최종 서명 APK의 실제 크기·SHA-256은 해당 빌드 산출물에서 별도로 확인한다.

## 백그라운드 작업 보존

`app/proguard-rules.pro`는 `DailyNotificationWorker`, `WidgetRefreshWorker`의 기존 클래스 이름과 `(Context, WorkerParameters)` 공개 생성자를 유지한다. WorkManager가 이전 앱에서 저장한 작업을 업데이트 후에도 기본 WorkerFactory로 생성할 수 있도록 한다. 나머지 코드는 계속 최적화하며 앱 전체를 보존하는 광범위한 규칙을 사용하지 않는다.

WorkManager 초기화, JobScheduler 서비스, 알람·부팅·시간 변경·업데이트 수신기, 위젯 provider와 RemoteViews 서비스는 제거하지 않는다. AndroidX의 소비자 규칙과 manifest에서 생성되는 규칙을 함께 사용한다. Room 스키마·마이그레이션과 Kotlin 직렬화 데이터 구조도 유지한다.

## 실제 축소 APK 업데이트 검사

동일한 앱 코드를 별도 `.smoke` 패키지로 검증한다. 테스트 인증서만 사용하며 일반 앱 데이터와 배포 키를 건드리지 않는다.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease -PemulatorSmoke=true -PreleaseSmoke=true
```

Android 13 이상 에뮬레이터를 시작하고 프로젝트 루트에서 실행한다. PowerShell과 PATH의 `adb`·`aapt`가 필요하며, `-Adb`, `-Aapt`, `-Serial`로 도구 경로·기기를 지정할 수 있다. 설치 전에 APK 패키지가 모두 `.smoke`인지 검사한다.

```powershell
./tools/verify-optimized-upgrade.ps1
```

검사는 디버그 APK에 합성 시간표·급식 캐시와 예약 작업을 저장한 뒤, **실제 R8 축소·난독화·리소스 축소를 거친 non-debuggable APK로 업데이트**한다. 축소 APK에는 테스트용 keep 규칙이나 테스트 러너를 붙이지 않는다. 앱의 백그라운드 프로세스를 종료하고 저장된 두 Worker의 성공, 실제 예약 시각의 두 알림, 시간표·급식 화면의 기존 캐시 복원을 확인한다. 결과는 `work/optimized-upgrade/`에 기록한다.

CI에서도 일반 릴리즈의 R8·리소스 축소 빌드와 Lint를 실행하고 난독화 매핑을 보관한다. 실제 배포 서명은 로컬에서 별도로 적용하며 GitHub 릴리즈는 자동 게시하지 않는다.
