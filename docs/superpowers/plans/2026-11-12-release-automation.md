# TOML 릴리즈 자동화 구현 계획

목표: release.toml을 단일 버전 기준으로 사용하고 명시적 릴리즈 커밋으로 검증·서명·게시·README 동기화를 수행한다.

설계 근거: 2026-11-12 사용자 승인 구상안과 후속 수정 지시. Gradle이 버전·빌드·APK를 검증하고 github-script가 게시·README를 관리한다. 실제 릴리즈 실행과 키 등록은 하지 않는다.

- [x] Gradle exportReleaseMetadata/verifyReleaseApk: TOML을 엄격히 검사하고 JSON·APK·보고서 근거를 생성한다.
- [x] tools/release.cjs + Node 내장 테스트: 트리거·버전 전진·설명·Octokit 게시·README 동기화를 구현한다.
- [x] Workflow에서 별도 인터프리터 설치를 제거하고 Gradle + github-script를 연결한다. 문서와 PR 설명도 갱신한다.
- [x] 실제 APK의 합성 키 검증과 키 누락/잘못된 키 거부, JVM·Lint·Workflow·회귀 테스트를 확인하고 기존 PR17에 푸시한다.

검토 초점: 기존 공개 APK 덮어쓰기 금지, 잘못된 버전/서명 차단, 키의 캐시 유출 방지, 미수행 검증 주장 금지, README 충돌·버전 역행 방어.

정책: main 보호를 우회하지 않고 GitHub App 기반 README PR을 만든다. PR 생성·자동 병합 예약은 동기화 완료가 아니다. 기본 concurrency의 대기 슬롯 제약은 운영 문서에 명시한다.

검증: Node 회귀 13개, Gradle 합성 fixture, JVM 71개, Debug·Release Lint와 UI 테스트 소스 컴파일 통과. 합성 키를 넣은 최적화 APK의 verifyReleaseApk 통과 및 다른 키·누락 키 거부 확인. 실제 배포 키·서명·게시·기기 실행은 미수행.

리뷰: clean README PR은 예상 SHA 조건부 병합으로 처리하고, TOML 배열은 사용 중단된 containsStrings 대신 원소별 타입을 확인한다. 회귀 테스트와 실제 Gradle 실행으로 수정 검증.
