# TOML 기반 릴리즈 운영

## 최초 설정

이 PR 자체는 릴리즈를 만들지 않는다. release.toml을 현재 공개 버전 1.1.0 / versionCode 16으로 맞췄다. 2026-11-12 GitHub v1.1.0의 공개 APK를 내려받아 aapt로 버전 1.1.0/code16/minSdk26과 정식 앱 ID를, apksigner로 서명 유효성을 확인했다.

이 APK의 인증서 SHA-256은 `4ec949d20e32a7b037d4c41071d162a6a014455d277a83aabaf6a274abf4e8ba`다. ANDROID_CERT_SHA256 변수에 이 값을 등록한다. 공개 인증서 지문이며 개인키나 APK 파일 해시가 아니다.

GitHub 저장소 Settings → Environments에서 다음 환경을 **미리 생성**하고 deployment branch를 main으로 제한한다. 자동 생성된 환경을 그대로 쓰지 않는다. 완전 자동 운영이면 required reviewers를 설정하지 않는다. 승인 규칙이 있다면 해당 단계는 승인을 기다린다.

| Environment | Secrets | Variables |
|---|---|---|
| release-build | NEIS_API_KEY | 없음 |
| release-signing | ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD | ANDROID_CERT_SHA256 |
| release-publishing | 없음 | 없음 |
| release-readme | 없음 | 없음 |

키는 등록하지 않은 상태로 PR을 제공한다. Settings → 해당 Environment → Secrets/Variables에서 위 이름 그대로 등록한다.

- ANDROID_KEYSTORE_BASE64: **기존 배포에 사용한** JKS/keystore 파일의 Base64. 신규 keystore를 만들면 기존 앱 업데이트 호환성이 깨진다.
- ANDROID_KEYSTORE_PASSWORD: 해당 keystore 비밀번호.
- ANDROID_KEY_ALIAS: 기존 개인키 alias.
- ANDROID_KEY_PASSWORD: 개인키 비밀번호. keystore 비밀번호와 같더라도 별도로 등록한다.
- ANDROID_CERT_SHA256: 신뢰하는 기존 배포 APK의 인증서 SHA-256. 콜론 유무·대소문자는 무관하다. APK 파일 SHA-256과 혼동하지 않는다.
- NEIS_API_KEY: 기존 나이스 인증키. 키는 APK에 포함되어 추출 가능하며 비밀 보관 수단이 아니다.

기존 APK의 인증서 확인:

```powershell
& '<Android SDK>/build-tools/35.0.0/apksigner.bat' verify --print-certs '<기존 APK>'
& '<Android SDK>/build-tools/35.0.0/aapt.exe' dump badging '<기존 APK>'
```

Base64를 화면에 출력하지 않고 등록하는 예시(사용자가 직접 실행):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('<기존 keystore 경로>')) |
  gh secret set ANDROID_KEYSTORE_BASE64 --repo mchany/comcigan-neo --env release-signing
```

나머지 비밀번호는 GitHub UI 또는 gh secret set의 대화형 입력으로 등록한다. 비밀번호를 명령행 인수·저장소 파일에 넣지 않는다. Base64도 비밀값이며 암호화가 아니다. GitHub Secret의 항목당 48KB 제한은 인코딩 후 크기에 적용한다.

## README 직접 반영 권한

릴리즈 게시와 README 반영은 job에 부여한 contents: write 권한의 GITHUB_TOKEN을 사용한다. GitHub App, App 개인키, Pull requests 권한과 Allow auto-merge 설정은 필요 없다. 기존 RELEASE_APP_ID와 RELEASE_APP_PRIVATE_KEY는 이 Workflow에서 사용하지 않는다.

README는 공개된 최신 릴리즈 메타데이터를 읽은 뒤 Contents API로 main의 README.md 한 파일만 직접 커밋한다. PR·별도 브랜치·CI 완료 이벤트를 만들지 않는다. GITHUB_TOKEN으로 만든 커밋은 새 push Workflow를 실행시키지 않으므로 반복 실행이 생기지 않는다.

main의 보호 규칙이 직접 쓰기를 금지하면 README 단계가 실패한다. 이 경우 저장소 정책에서 자동 반영을 허용할 수 있는지 확인해야 하며, Workflow는 보호 규칙을 바꾸거나 강제 푸시하지 않는다.

## 릴리즈 절차

릴리즈 관련 정의는 `.github/workflows/release.yml` 하나로 관리한다. 검증은 check job, 빌드·서명·게시는 각각 격리된 job, README 직접 반영은 readme job으로 실행한다.

검증한 버전 정보는 job 출력으로 준비 단계에 전달해 TOML 내보내기를 중복 실행하지 않는다. 릴리즈 APK job의 표시 이름은 Build release APK로 두어 Android CI의 build 검사와 구분한다.

| 이벤트 | 실행 범위 |
|---|---|
| main 대상 PR | 시크릿 없는 릴리즈 설정·회귀 검증 |
| main push | 검증 후 명시적인 chore: release 커밋만 빌드·서명·게시·README 직접 커밋 |
| 수동 operation=release | main의 버전·태그 정합성 검사 후 릴리즈 파이프라인 |
| 수동 operation=sync-readme | 최신 공개 릴리즈 안내만 main README에 직접 반영 |

PR 검증은 PR별 concurrency 그룹을 사용한다. 배포·README 쓰기는 release-production 그룹으로 직렬화한다. workflow_run 완료 이벤트를 사용하지 않는다.

1. main 최신 기준의 릴리즈 준비 PR에서 release.toml의 version, version_code, summary, changes를 수정한다.
2. 버전은 정식 X.Y.Z이며 version_code는 모든 기존 정식 배포보다 커야 한다. 다음 예시는 1.1.1 / 17이다.
3. PR 제목을 정확히 chore: release 1.1.1로 작성하고 squash merge한다. GitHub의 (#PR번호) 접미사는 허용한다.
4. main의 마지막 커밋 첫 줄이 이 규칙과 일치할 때만 자동 배포한다. 일반 chore, feat, docs 커밋은 배포하지 않는다.
5. Actions Release에서 빌드 → 격리 서명 → 게시 → README 상태를 확인한다.

한 push에는 릴리즈 요청 한 개만 넣고 마지막 커밋으로 둔다. 한 번에 릴리즈 요청 하나씩 진행한다. concurrency는 진행 중 작업을 취소하지 않지만 기본 대기 슬롯은 한 개이므로 요청이 몰리면 오래된 대기 실행이 취소될 수 있다. 취소된 요청은 버전 순서와 공개 상태를 확인한 뒤 복구한다.

TOML은 Gradle org.tomlj에서만 읽는다. Gradle의 :app:exportReleaseMetadata가 검증된 JSON을 내보내며 github-script는 이 JSON으로 게시와 README 갱신을 처리한다. Gradle이 TOML 버전을 직접 사용하므로 커밋과 APK 소스의 버전이 일치한다. versionCode는 실행 번호로 만들지 않아 재실행 시 변경되지 않는다. 별도 버전 상수를 수정할 필요가 없다.

summary와 changes는 사용자에게 보일 문장이다. 릴리즈 설명은 이 정보와 **이번 실행의 검증 결과**로 자동 조립한다. 변경한 코드에서 사용자용 설명을 임의로 추측하지 않는다. 물리 기기·설정 보존·ANR 검증은 수행하지 않았다고 명시한다.

릴리즈 이후 README의 현재 버전·요약·다운로드 배지·APK·릴리즈 노트·체크섬 링크를 동일 입력으로 갱신한다. 관리 영역은 release:start / release:end 마커 사이이며 마커 누락·중복 시 중단한다.

## 검증과 보안 경계

- Node.js 내장 테스트 러너의 GitHub 처리 회귀 테스트 및 앱/모듈 Debug·Release 단위 테스트, Debug·Release Lint.
- 별도 .smoke 패키지의 UI 계측 테스트 소스 컴파일. UI 기기 실행은 하지 않는다.
- Gradle의 :app:verifyReleaseApk가 .smoke가 아닌 정식 앱 ID, non-debuggable, minSdk26, 기대 버전·코드와 JUnit·Lint 보고서를 검사한다. unsigned 폴더에 APK·검증 근거를 내보낸다.
- :app:verifyReleaseApk는 먼저 실행한 JUnit·Debug/Release Lint 보고서가 모두 존재하고 성공해야 통과한다. UI 컴파일 항목은 별도 컴파일 태스크 성공 후 -PreleaseUiCompileVerified=true를 전달한 Workflow에서만 기록한다.
- 나이스 키는 빌드 단계에만 전달한다. 비어 있거나 DEX 반영을 확인할 수 없으면 실패한다.
- 배포용 빌드 캐시를 저장하지 않고 APK·검증 결과만 artifact로 올린다.
- 서명 job은 소스 checkout·Gradle 실행 없이 SDK 도구와 unsigned APK만 받는다.
- zipalign 후 기존 키로 서명하고 기대 인증서 지문·서명·정렬을 확인한다.
- 태그는 고정된 SHA를 가리키며, APK·SHA256SUMS.txt·release-metadata.json을 원격 재다운로드해 확인한 뒤 공개한다.
- 실서버 파서·나이스 검사, 에뮬레이터 실행·실기기 업데이트는 현재 자동 릴리즈 필수 단계에 포함하지 않았다. 기존 Parser Health는 별도로 유지한다.

기존 -dontobfuscate, R8 최적화·리소스 축소 정책은 유지한다. NEIS 입력은 빌드 비밀값이지만 APK 안에서는 공개 가능한 자격 증명이라는 기존 한계도 유지된다. 제한·회수 정책은 나이스 운영 정책에 맞춰 관리한다.

## 재실행과 장애 복구

- 테스트/키/서명 실패: 공개하지 않는다. 원인 수정 후 실패 job을 재실행한다.
- 업로드 중 실패: 같은 SHA·버전·바이트의 기존 Draft 첨부만 이어서 처리한다. 다른 첨부는 덮어쓰지 않는다.
- 게시 API 응답 유실: 재실행 시 원격 공개 상태와 첨부를 먼저 확인한다.
- 공개 APK와 로컬 산출물이 다름: 중단한다. 기존 공개 파일을 교체하지 않는다.
- 재빌드는 같은 소스라도 APK 바이트가 달라질 수 있다. 게시 실패만 복구할 때는 기존 sign job의 immutable artifact를 재사용하도록 publish/readme 실패 job만 재실행한다.
- unsigned artifact 보존 3일, signed artifact 보존 7일이다. 만료 후에는 무조건 덮어쓰지 말고 원격 첨부와 상태를 확인한다.
- README 실패: APK 공개는 유지하고 readme 실패 job을 재실행한다. Actions workflow_dispatch로 전체 재실행하면 재빌드 차이로 게시가 거부될 수 있다.
- 공개 후 결함: 기존 APK 교체 대신 더 높은 버전·versionCode로 수정 배포한다.

최초 baseline v1.1.0은 기존 메타데이터 없는 배포의 예외다. 이후 새 릴리즈에 metadata가 없으면 versionCode를 추측하지 않고 실패한다. 기존 v1.1.0 태그를 이 PR의 소스로 옮기거나 같은 버전으로 재발행하지 않는다.

## README만 다시 반영

Actions → Release → Run workflow에서 main과 operation **sync-readme**를 선택한다. APK를 다시 빌드하거나 게시하지 않고 현재 최신 공개 릴리즈의 안내만 main에 반영한다.

현재 파일의 SHA를 조건으로 갱신하며, 409 충돌이 발생하면 최신 본문을 읽어 최대 3회 시도한다. 관리 영역 밖의 사용자 수정은 보존한다. 이미 일치하면 커밋을 만들지 않고 끝낸다. 403·422 권한/보호 규칙 오류는 재시도하지 않는다.

```sh
gh workflow run release.yml --repo mchany/comcigan-neo --ref main -f operation=sync-readme
```

## 개발 검증

```sh
node --test tools/release.test.cjs
./gradlew :app:exportReleaseMetadata :app:testReleaseAutomation
actionlint -shellcheck= -pyflakes= .github/workflows/release.yml
./gradlew :core:model:test :core:comcigan:test :core:neis:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

실제 키 등록·서명·릴리즈·README 직접 커밋은 운영 설정 후 첫 릴리즈에서 확인해야 한다. GitHub 처리는 모의 Octokit API로, Gradle APK 검증은 합성 키를 넣은 실제 빌드와 거부 시나리오로 검증한다. 이는 실제 배포 성공을 대신하지 않는다.
