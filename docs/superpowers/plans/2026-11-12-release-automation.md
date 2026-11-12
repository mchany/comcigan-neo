# TOML 릴리즈 자동화 구현 계획

목표: release.toml을 단일 버전 기준으로 사용하고 명시적 릴리즈 커밋으로 검증·서명·게시·README 동기화를 수행한다.

설계 근거: 2026-11-12 사용자 승인 구상안. 기존 서명/NEIS 키는 코드에 넣지 않고 등록 절차만 문서화한다. 실제 릴리즈는 실행하지 않는다.

- [x] tools/release.py와 unittest: TOML/트리거/이전 릴리즈 비교/설명/README/API 게시를 구현하고 실패·재실행·충돌 테스트.
- [x] release.toml 1.1.0/code16 및 Gradle TOML 연동, README 마커 추가. 기존 최적화·난독화 정책 유지.
- [x] 전용 release workflow: no-secret 검사, NEIS 빌드, 격리 서명, 검증된 산출물 게시, README PR 자동 병합. 별도 CI로 Python/Workflow 회귀 검증.
- [x] 설정·운영·복구 문서와 독립 코드 리뷰. 통합은 사용자 요청대로 별도 브랜치 푸시와 PR로 진행.

검토 초점: 기존 공개 APK 덮어쓰기 금지, 잘못된 버전/서명 차단, 키의 캐시 유출 방지, 미수행 검증 주장 금지, README 충돌·버전 역행 방어.

정책: 보호된 main의 README는 GitHub App 토큰으로 PR 생성/필수검사 후 자동 병합한다. 사람 리뷰가 필수라면 대기 상태로 보고한다. PR 생성만으로 동기화 성공이라 하지 않는다.

검증: Python 19개 통과, JVM 71개 통과, Release Lint 오류0/경고34, 최적화 unsigned APK 빌드와 1.1.0/code16 메타데이터 확인. 실제 서명·게시·기기 테스트는 미수행.

설계 조정: actionlint 1.7.12와 호환되는 기본 concurrency를 사용한다. 릴리즈 요청은 한 건씩 처리하며 pending 슬롯 취소 제약을 운영 문서에 명시했다.

리뷰: 이전 README PR 자동 병합 경합과 metadata 누락 시 versionCode 추측을 회귀 테스트로 재현하고 수정했다.

추가 검증: Debug Lint 및 격리 UI 계측 테스트 소스 컴파일 통과. 기기 실행은 미수행.
