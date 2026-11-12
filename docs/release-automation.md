# TOML 기반 릴리즈 운영

## 최초 설정

이 PR 자체는 릴리즈를 만들지 않는다. release.toml을 현재 공개 버전 1.1.0 / versionCode 16으로 맞췄다. 2026-11-12 GitHub v1.1.0의 공개 APK를 내려받아 aapt로 버전 1.1.0/code16/minSdk26과 정식 앱 ID를, apksigner로 서명 유효성을 확인했다.

이 APK의 인증서 SHA-256은 `4ec949d20e32a7b037d4c41071d162a6a014455d277a83aabaf6a274abf4e8ba`다. ANDROID_CERT_SHA256 변수에 이 값을 등록한다. 공개 인증서 지문이며 개인키나 APK 파일 해시가 아니다.

GitHub 저장소 Settings → Environments에서 다음 환경을 **미리 생성**하고 deployment branch를 main으로 제한한다. 자동 생성된 환경을 그대로 쓰지 않는다. 완전 자동 운영이면 required reviewers를 설정하지 않는다. 승인 규칙이 있다면 해당 단계는 승인을 기다린다.

| Environment | Secrets | Variables |
|---|---|---|
| release-build | NEIS_API_KEY | 없음 |
| release-signing | ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD | ANDROID_CERT_SHA256 |
| release-publishing | RELEASE_APP_PRIVATE_KEY | RELEASE_APP_ID |
| release-readme | RELEASE_APP_PRIVATE_KEY | RELEASE_APP_ID |

키는 등록하지 않은 상태로 PR을 제공한다. Settings → 해당 Environment → Secrets/Variables에서 위 이름 그대로 등록한다.

- ANDROID_KEYSTORE_BASE64: **기존 배포에 사용한** JKS/keystore 파일의 Base64. 신규 keystore를 만들면 기존 앱 업데이트 호환성이 깨진다.
- ANDROID_KEYSTORE_PASSWORD: 해당 keystore 비밀번호.
- ANDROID_KEY_ALIAS: 기존 개인키 alias.
- ANDROID_KEY_PASSWORD: 개인키 비밀번호. keystore 비밀번호와 같더라도 별도로 등록한다.
- ANDROID_CERT_SHA256: 신뢰하는 기존 배포 APK의 인증서 SHA-256. 콜론 유무·대소문자는 무관하다. APK 파일 SHA-256과 혼동하지 않는다.
- NEIS_API_KEY: 기존 나이스 인증키. 키는 APK에 포함되어 추출 가능하며 비밀 보관 수단이 아니다.
- RELEASE_APP_ID / RELEASE_APP_PRIVATE_KEY: 아래 README 동기화용 GitHub App 설정.

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

## README 동기화용 GitHub App

개인 계정 Settings → Developer settings → GitHub Apps에서 개인 App을 생성한다.

1. Repository permissions: Contents Read and write, Pull requests Read and write, Metadata Read-only.
2. Webhook은 이 자동화에서 필요하지 않으므로 비활성화한다.
3. 설치 범위를 mchany/comcigan-neo 하나로 제한한다.
4. App ID를 RELEASE_APP_ID로, 생성한 PEM 개인키 원문을 RELEASE_APP_PRIVATE_KEY로 위 두 Environment에 등록한다.
5. 저장소 Settings → General에서 Allow auto-merge를 켜고 squash merge를 허용한다.
6. main 보호 규칙에 필요한 CI를 지정한다. Workflow·Gradle·릴리즈 스크립트 변경은 검토한다.

App이 main 보호 규칙을 우회하도록 구성할 필요는 없다. README는 codex/readme-release-sync 브랜치에서 PR로 반영한다. 사람 리뷰가 필수이면 자동 병합 예약만 하고 대기하며, Workflow 경고에 PR 링크와 pending 상태를 표시한다. 사람 승인 없이 완결하려면 저장소의 리뷰 정책도 이에 맞아야 한다.

github-script의 Octokit REST·GraphQL 클라이언트가 GitHub 게시·README PR을 관리한다. 별도 Python 설치·파싱 도구와 외부 JavaScript 패키지를 추가하지 않는다. GITHUB_TOKEN이 만든 PR의 후속 CI 실행 제약을 피하기 위해 README에는 설치 토큰을 사용한다. 실제 릴리즈의 태그·첨부 게시에는 짧은 수명의 GITHUB_TOKEN을 사용한다. App은 서명 키와 나이스 키를 받지 않는다.

## 릴리즈 절차

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
- README pending: PR 생성은 완료가 아니다. 필수 검사·리뷰를 통과해 실제 병합되었는지 확인한다.
- 새 릴리즈 공개 전에 이전 README PR 자동 병합을 해제한다. 단일 PR을 새 main/릴리즈 내용으로 갱신하고 stale README 검사 후 자동 병합을 다시 설정한다.
- 공개 후 결함: 기존 APK 교체 대신 더 높은 버전·versionCode로 수정 배포한다.

최초 baseline v1.1.0은 기존 메타데이터 없는 배포의 예외다. 이후 새 릴리즈에 metadata가 없으면 versionCode를 추측하지 않고 실패한다. 기존 v1.1.0 태그를 이 PR의 소스로 옮기거나 같은 버전으로 재발행하지 않는다.

## 코드 수정 후 기존 Draft 복구

실패한 실행의 Re-run failed jobs는 **원래 SHA의 Workflow와 코드**를 다시 실행한다. 게시 코드의 버그를 수정한 경우에는 아래 복구 실행으로 기존 서명 산출물에 수정된 게시 코드를 적용한다.

1. 수정 PR을 main에 병합한다.
2. Actions → **Recover Release** → Run workflow에서 브랜치를 main으로 선택한다.
3. run_id에는 원래 Release 실행의 숫자 ID만 입력한다. 이번 v1.1.1 실행은 `43147380805`다.
4. 복구 실행은 기존 Draft의 첨부 바이트를 확인한 뒤 **정식 공개**하고 README 동기화 PR을 처리한다.

CLI로 실행하려면 다음 명령을 사용한다.

```sh
gh workflow run release-recover.yml --repo mchany/comcigan-neo --ref main -f run_id=43147380805
```

복구는 공식 저장소 main의 원래 Release 실행과 성공한 sign 작업을 확인한 뒤 보존 중인 signed-release artifact를 사용한다. 원래 소스 SHA와 실행 URL을 유지하며 APK를 다시 빌드·서명하지 않는다. 나이스 키나 keystore는 복구 작업에 전달하지 않는다. 기존 release-publishing/release-readme 환경의 App 설정을 그대로 사용한다.

태그가 없거나 다른 SHA를 가리키면 중단한다. 태그를 생성·이동하지 않고 다른 바이트의 기존 첨부를 교체하지 않는다. artifact가 만료되었거나 더 최신 버전이 이미 공개된 경우도 중단한다. 기존 서명 artifact 보존 기간은 7일이다.

태그 조회 API는 공개 릴리즈를 찾는 용도로 사용하고, Draft 복구는 인증된 릴리즈 목록에서 유일한 태그를 찾는다. 업로드 후 첨부 검증은 릴리즈 ID로 다시 조회한다. 같은 태그의 여러 Draft가 있거나 업로드 도중 삭제되면 임의로 복구하지 않는다.

## 개발 검증

```sh
node --test tools/release.test.cjs tools/recovery.test.cjs
./gradlew :app:exportReleaseMetadata :app:testReleaseAutomation
actionlint -shellcheck= -pyflakes= .github/workflows/release.yml .github/workflows/release-check.yml .github/workflows/release-recover.yml
./gradlew :core:model:test :core:comcigan:test :core:neis:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

실제 키 등록·서명·릴리즈·README PR 생성은 운영 설정 후 첫 릴리즈에서 확인해야 한다. GitHub 처리는 모의 Octokit API로, Gradle APK 검증은 합성 키를 넣은 실제 빌드와 거부 시나리오로 검증한다. 이는 실제 배포 성공을 대신하지 않는다.
