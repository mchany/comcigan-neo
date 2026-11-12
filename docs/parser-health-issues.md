# Parser Health 실패 자동 이슈

기존 `.github/workflows/parser-health.yml`의 `Live parser check` job 안에서 처리한다. 별도 워크플로, PAT, 외부 알림 서비스는 사용하지 않는다.

## 동작

- `mchany/comcigan-neo`의 `main`에서 직접 또는 Cloudflare 예약으로 실행한 `workflow_dispatch`가 실패하면 이슈를 생성한다.
- PR·다른 브랜치·포크 저장소·성공 실행은 이슈를 생성하지 않는다.
- 실서버 검사 shell 내부에서 `GITHUB_STEP_SUMMARY`를 runner 임시 파일로 지정한다. `env`에 직접 지정하면 runner가 기본 경로로 덮어쓰므로 별도 `PARSER_SUMMARY` 변수에서 shell의 `export`로 설정한다. 성공과 실패 모두 후속 단계에서 같은 내용을 Actions 요약으로 게시하고, 실패 이슈에는 그 파일을 그대로 넣는다.
- 이슈에는 실행·재실행 링크, 커밋 SHA, 실행 출처·예약 시각, 단계별 결과를 함께 기록한다. 회귀 테스트나 JDK 설정이 먼저 실패해 검사 요약이 없으면 요약 부재와 단계 결과를 표시한다.
- 열린 이슈를 전체 페이지 조회하여 `github-actions[bot]`이 작성한 전용 마커의 이슈를 찾는다. 동일한 열린 이슈가 있으면 최신 실패로 본문을 갱신한다. 같은 실행을 다시 보고해도 본문이 같으면 쓰지 않는다.
- 자동 이슈를 닫으면 다음 실패 시 새 이슈를 생성한다. 성공 시 자동 종료하거나 과거 실패마다 댓글을 쌓지는 않는다.
- 기존 `concurrency: parser-health`가 조회·생성 작업을 직렬화한다. GitHub 검색 색인 지연에 의존하지 않는다.
- API 오류는 알림 단계 실패로 남기며 새 이슈 생성으로 우회하거나 쓰기를 자동 재시도하지 않는다. 원래 검사 실패도 그대로 유지한다.

## 권한과 한계

job에 `contents: read`, `issues: write`를 부여한다. GitHub Actions의 기본 `GITHUB_TOKEN`만 사용한다. 이슈 발행 조건은 워크플로와 스크립트 양쪽에서 검사한다. 권한은 job 단위이므로 같은 저장소 PR의 job에도 선언되며, 포크 PR 토큰에는 GitHub의 읽기 전용 정책이 적용된다. `pull_request_target`은 사용하지 않는다.

기존 Kotlin 검사가 제공하는 고정 오류 분류만 사용하고 원시 HTTP 본문·토큰·전체 빌드 로그를 이슈에 복사하지 않는다. 메타데이터는 JSON 코드 블록으로 이스케이프하고 요약·입력 크기를 제한한다.

러너가 종료되거나 job 자체가 취소·시간 초과되면 후속 단계 실행은 보장되지 않는다. checkout 실패로 저장소 스크립트 자체가 없으면 이슈 발행도 불가능하다.

## 로컬 검증

```powershell
node --test tools/parser-health.test.cjs tools/release.test.cjs
```

Node 테스트는 임시 요약 파일과 메모리 내 GitHub 클라이언트를 사용해 실패 발행·반복 실패·페이지 순회·수동 이슈 및 PR 보호·요약 누락·권한 오류·큰 입력을 재현한다. 별도 HTTP API 서버나 API 주소 변경 기능은 운영 코드에 없다.

실제 파서 실패는 `:core:comcigan:run`에 존재하지 않는 학교명을 전달해 재현한다. `GITHUB_STEP_SUMMARY`를 로컬 임시 파일로 지정하고, 종료 코드가 0이 아닌지와 요약의 `FAIL`을 확인한 뒤 같은 파일을 이슈 발행 함수에 전달한다. 이 과정에서 원격 테스트 이슈는 생성하지 않는다.

### 2026-11-12 검증 결과

- Node 테스트 28개 통과: 신규 알림 테스트 9개와 기존 릴리즈 테스트 19개. 실제 워크플로 shell을 Bash로 실행하여 runner 기본 환경 변수 주입 상태에서도 실패 요약 보존·게시를 확인한다.
- `:core:model:test :core:comcigan:test` JVM 테스트 59개 통과.
- 존재하지 않는 학교명을 전달한 실제 Kotlin 실행이 종료 코드 1과 `Service page, protocol and exact school search | FAIL` 요약을 생성함.
- 실제 실패 요약이 이슈 본문에 그대로 들어감, 신규 생성 1회, 동일 보고 추가 쓰기 0회, 재실행 번호 변경 시 갱신 1회를 로컬에서 확인함.
- actionlint 1.7.12로 워크플로 문법·표현식 검증 통과.
- Windows 한글 경로에서 발생한 Gradle 클래스 로딩 오류는 기존 도구의 ASCII 경로와 임시 ASCII 작업 경로를 사용해 해결함. 저장소의 빌드·파서 코드는 수정하지 않음.
- 실제 GitHub 이슈 생성은 실행하지 않음. 원격 쓰기 권한·발행의 최종 확인은 main 반영 후 실제 실패 실행에서 수행해야 함.

로컬 재현용 스크립트·요약·이슈 미리보기는 Git에서 제외된 `work/`에만 보관한다. 운영 코드에 localhost 주소, 임시 API 서버, 테스트 토큰, 실패 강제 입력을 추가하지 않았다.
