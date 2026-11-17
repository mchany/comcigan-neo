# Parser Health 상태·자동 Issue 운영

기존 Parser Health job, 기본 GITHUB_TOKEN, 단일 열린 자동 Issue를 사용한다. 별도 상태 저장소·PAT·외부 의존성을 추가하지 않는다.

## 검사와 판정

정확 학교 검색, 존재하지 않는 학교 검색, 첫 게시 기간, 두 번째 게시 기간을 검사한다. 첫 실패와 무관한 단계는 계속 검사하며, 학교를 특정하지 못한 경우에만 두 시간표 단계를 SKIPPED로 기록한다.

날짜는 Asia/Seoul 기준이다. PR #27의 publishedWeekStarts(date)와 실제 요청 번호 선택을 유지한다. 서버가 일요일에 다음 월요일 자료를 미리 게시해도 달력상의 이번 주와 비교하지 않는다. 조회 전후 날짜가 바뀌면 한 번만 다시 조회하며, 반복 날짜 변경은 VALIDATION_ERROR / DATE_BOUNDARY_UNSTABLE로 남긴다. 미게시·판단 불가 응답도 날짜 경계를 확인한다.

| 단계 상태 | 의미 | 전체 성공에 포함 |
|---|---|---|
| HEALTHY | 조회와 의미 검증 성공 | 예 |
| UNPUBLISHED | 검증된 게시 목록에 요청 대상 기간이 없음 | 예 |
| INDETERMINATE | 정상 미게시와 이상 응답을 구분할 근거 부족 | 아니요 |
| FAIL | 분류할 수 있는 명확한 오류 | 아니요 |
| SKIPPED | 선행 학교 검색 실패로 조회 불가 | 아니요 |

빈 객체·시작일 누락은 기존 앱 예외 타입을 유지하되 Health에서는 INDETERMINATE로 처리한다. 학급·기간이 정상적으로 파싱됐어도 교시나 수업이 없으면 방학이라고 추측하지 않고 INDETERMINATE로 남긴다. 인증 실패·잘못된 JSON·필수 필드 오류를 미게시로 바꾸지 않는다.

전체 결과는 FAIL, INDETERMINATE, UNPUBLISHED, HEALTHY 우선순위로 집계한다. 필수 네 단계 모두 HEALTHY/UNPUBLISHED인 경우에만 CLI 종료 코드가 0이다.

## 오류 유형과 재시도

| 오류 유형 | 판단 근거 | Health 계층 재시도 |
|---|---|---|
| NETWORK_ERROR | DNS·연결·타임아웃 등 IOException | 1초 후 최대 1회 |
| SERVER_ERROR | 비정상 HTTP 상태 코드 | 500/502/503/504만 1초 후 최대 1회 |
| PROTOCOL_ERROR | 서비스 페이지 경로·키 표현·토큰 등의 구조 | 없음 |
| PARSE_ERROR | 응답 인증·디코딩·JSON·자료 해석 | 없음 |
| VALIDATION_ERROR | 기간·학급·교시·과목 검증 | 없음 |
| UNKNOWN_ERROR | 안전하게 분류할 수 없는 예외·모호한 미게시 | 없음 |
| ENVIRONMENT_ERROR | 준비·회귀 검사 실패, 결과 파일 누락·비정상, 단계 결과 불일치 | 없음 |

403/429에는 Health 계층의 재시도를 추가하지 않는다. 기존 클라이언트의 403/구조/기간 변경에 대한 서비스 정보 갱신 정책은 유지한다. 취소는 전파하며 정상 결과로 바꾸지 않는다.

attempts는 Health 단계의 시도 횟수다. 각 시도의 결과·오류 유형·고정 코드·HTTP 상태와 dateRefetches를 기록한다. 클라이언트 내부 갱신이나 실제 HTTP 요청 횟수를 뜻하지 않는다. 날짜 경계 재조회는 단계 시도마다 최대 1회다. 첫 시도의 일시적 오류가 두 번째 시도에서 회복되면 정상으로 처리하고 새 이슈를 생성하지 않는다.

## JSON과 Actions Summary

Kotlin CLI는 기존 Markdown Summary와 schemaVersion=1 JSON을 만든다.

- PARSER_HEALTH_RESULT: 원자적으로 교체하는 JSON 파일 경로.
- GITHUB_STEP_SUMMARY: 기존처럼 Markdown을 추가하는 경로.
- 워크플로는 shell에서 GITHUB_STEP_SUMMARY를 PARSER_SUMMARY 임시 경로로 export하고, 성공·실패 모두 후속 단계에서 Actions Summary에 게시한다.
- JSON은 작업 중 Node 보고기가 소비한다. 원시 HTTP 본문이나 전체 빌드 로그를 이슈로 복사하지 않는다.

JSON에는 checkedAt, timezone, target, overall 및 네 단계의 id/status/errorType/code/referenceDate/week/expectedStart/actualStart/httpStatus/교시·수업 수/attempts가 포함된다. 유효하지 않은 파일, 누락된 단계, 잘못된 날짜·상태 조합, 지원하지 않는 코드는 정상·복구 증거로 사용하지 않는다. 모든 실행(PR 포함)에서 별도 Node 단계로 실제 JSON과 실서버 프로세스의 종료 결과를 대조하며, 불일치하면 Actions와 배지도 실패한다.

오류 메시지 문자열로 원인을 추측하지 않는다. 예외 출처와 게시 부재 근거를 발생 지점에서 기록하며, 토큰·키·원본 응답·임의 예외 메시지/cause는 출력하지 않는다. Node는 검증한 필드만 사용해 이슈 표를 만들며 원래 Summary 파일의 임의 내용을 그대로 복사하지 않는다.

## 자동 Issue 상태 전환

main의 workflow_dispatch(직접 실행 또는 Cloudflare 예약 실행)만 원격 이슈를 쓴다. PR·다른 브랜치·포크는 검증만 실행한다.

- 지속 실패·판단 불가·환경 오류는 오류 유형을 제목에 표시해 새 이슈를 만들거나 기존 열린 자동 이슈를 갱신한다.
- 정상 미게시·정상 조회는 새 이슈를 생성하지 않는다.
- 열린 이슈가 있으면 최신 관측을 갱신하고 첫 실패·최근 실패의 실행 링크와 진단 코드를 보존한다.
- **모든 필수 단계가 HEALTHY인 완전한 성공만 복구 확인으로 기록한다.**
- UNPUBLISHED를 포함한 성공 실행은 정상 미게시 관측이며 시간표 파싱의 복구 확인이 아니다.
- INDETERMINATE·환경 실패·취소·생략은 복구 증거가 아니다.
- **이슈 종료는 수동이다. 자동 종료·재열기를 하지 않는다.** 사용자가 닫은 뒤 새 실패가 생기면 새 이슈를 만든다.
- 이슈 전체 페이지에서 전용 마커와 github-actions[bot] 작성자를 확인한다. 사용자 이슈·PR은 덮어쓰지 않는다.
- 동일 결과의 중복 처리는 추가 쓰기를 하지 않는다. 오래된 runId/attempt는 최신 관측을 덮지 않는다. 신규 발행 전 닫힌 봇 이슈의 마지막 관측과 완료된 main 예약/직접 실행 기록도 확인해 수동 종료 후 중복 보고나 과거 실행의 새 이슈 생성을 차단한다.
- 레거시 자동 이슈는 검증 가능한 기존 실행 링크만 이관하며 임의 본문은 재사용하지 않는다.
- concurrency: parser-health와 cancel-in-progress: false를 유지한다. API 오류는 실패로 전파하고 우회 생성·자동 쓰기 재시도를 하지 않는다.

성공 실행에서도 열린 이슈의 복구 상태 확인을 위해 GitHub API를 읽는다. 이슈 API 자체의 오류로 workflow가 실패할 수 있으므로 Actions 배지는 파서만의 가용성 지표가 아니다.

## 운영 시 확인 순서

1. 실패 실행의 Actions Summary에서 단계·오류 유형·기준 날짜·주차·재시도 결과를 확인한다.
2. NETWORK_ERROR/SERVER_ERROR이면 일시적 장애 재확인 결과와 HTTP 상태를 본다.
3. PROTOCOL_ERROR/PARSE_ERROR이면 같은 커밋의 오프라인 회귀 테스트와 서비스 변경을 확인한다.
4. VALIDATION_ERROR/INDETERMINATE이면 게시 기간·응답 시작일·수업 수와 고정 코드를 확인한다. 미게시라고 임의 결론 내리지 않는다.
5. ENVIRONMENT_ERROR이면 준비/회귀/결과 파일/프로세스 종료 상태를 먼저 확인한다.
6. 이슈의 최신 관측과 최근 실패를 비교하고, 복구 확인 후 운영자가 수동으로 닫는다.

## Workers·배지·앱과의 호환성

workflow_dispatch 입력 source(manual/scheduler)와 scheduled_time, 워크플로 파일명·job명·배지 주소를 유지한다. Workers 소스와 설정은 저장소에 없으며 배포·일정·인증 설정을 변경하지 않는다. 저장소에서 검증 가능한 기존 dispatch 입력 계약은 shell/워크플로 회귀 테스트로 확인한다. 실제 외부 Worker 배포 상태는 이 변경의 로컬 테스트로 입증하지 않는다. 이슈 신규 발행의 실행 순서 확인을 위해 job에 읽기 전용 actions: read 권한을 추가하며, 조회 오류 시 새 이슈 생성으로 우회하지 않는다.

README 배지는 마지막 GitHub Actions 실행 결과다. 정상 미게시도 passing이며 판단 불가·네트워크·CI 환경·보고 API 오류도 failing이 될 수 있다. 한 학교·1학년 1반 샘플 검사이며 모든 학교나 실시간 서비스 상태를 보장하지 않는다.

앱 UI·조회 결과·달력/캐시·알림/급식·앱 버전은 변경하지 않는다. 기존 예외 타입/catch와 클라이언트 내부 재시도 정책을 유지한다.

## 로컬 검증

외부 통신 없는 검증:

    ./gradlew :core:model:test :core:comcigan:test --console=plain
    node --test tools/parser-health.test.cjs tools/release.test.cjs
    actionlint .github/workflows/parser-health.yml

JVM 테스트는 주입한 시계·클라이언트 함수와 로컬 HTTP 서버를 사용한다. Node는 임시 결과 파일과 메모리 GitHub 클라이언트를 사용하므로 실제 이슈를 만들거나 닫지 않는다. Health JSON 공통 fixture는 Kotlin 출력과 Node 검증기의 호환성을 양쪽에서 검사한다.

별도 실서버 검증:

    ./gradlew :core:comcigan:run --args="중일고등학교" --console=plain

외부 서비스의 상태에 따라 실패할 수 있으며 오프라인 회귀 실패와 구분한다. PARSER_HEALTH_RESULT와 GITHUB_STEP_SUMMARY를 지정하면 로컬에서 같은 결과 파일을 확인할 수 있다. 실제 실패를 재현하더라도 원격 테스트 이슈를 생성하지 않는다.

## 한계

checkout 실패로 스크립트가 없거나 runner 종료·job 취소·시간 초과·보고기 자체 실행 실패가 발생하면 자동 보고는 보장되지 않는다. GitHub API 쓰기 권한과 실제 예약 Worker 연동의 최종 확인은 main 반영 후 운영 환경에서 해야 한다.

판단 불가를 실패로 남기기 때문에 방학 등의 정상 상황에서도 진단 이슈가 남을 수 있다. 근거 없는 정상 처리로 실제 파서 장애를 은폐하는 것보다 보수적인 정책을 택했으며, 이슈 제목과 상태에서 확정 파서 장애와 구분한다.

## 2026-11-17 구현 검증

- 모델·파서 JVM 테스트 102개, Node 테스트 49개 및 actionlint 통과.
- 실서버 정확 학교 검색·빈 검색·11월 16일/23일 시작 시간표 네 단계 HEALTHY 확인(각 18개/34개 수업).
- 실서버 Kotlin 출력 JSON을 Node 검증기로 재검증.
- 원격 장애 이슈 발행·갱신과 실제 Worker 배포 검증은 수행하지 않음. 모의 GitHub API와 기존 dispatch 계약으로 검증.
