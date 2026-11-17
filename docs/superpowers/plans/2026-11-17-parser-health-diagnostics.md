# Parser Health Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 정상 미게시·판단 불가·오류를 분리하고 일시적 오류 재확인 및 수동 종료 이슈의 복구 추적을 추가한다.
**Architecture:** 기존 예외에 출처와 근거를 보존하고 Health 전용 실행/결과 모델을 추가한다. Kotlin은 JSON+Markdown을 출력하고 Node 보고기는 검증된 JSON과 Actions 단계로 단일 봇 이슈를 갱신한다.
**Tech Stack:** Kotlin/JVM 17, kotlinx serialization JSON/coroutines, OkHttp, Node 내장 test/fs, GitHub Actions.
**Spec:** ../specs/2026-11-17-parser-health-diagnostics-design.md (2026-11-17 사용자 승인)

## Global Constraints
- 기준 main b1524f0; PR #27 게시 기간·한국 날짜·1회 자정 재조회 유지.
- 외부 의존성/상태 저장소 추가 없음.
- NETWORK_ERROR 및 HTTP 500/502/503/504만 Health 계층 최대 1회, 1초 후 재시도.
- 확정 미게시만 UNPUBLISHED; 모호함은 INDETERMINATE로 비정상 종료.
- 모든 필수 단계 HEALTHY만 이슈 복구 확인. 이슈 종료는 수동.
- 앱/UI/모델/캐시/알림/급식/버전/Worker 변경 및 로컬 APK 빌드 없음.
- 별도 parser-health-diagnostics 브랜치 push+PR; merge 없음.
- 원문 응답·토큰·키·임의 예외 메시지 출력 없음.

## Review Focus
- 정상 미게시 예외가 잘못된 데이터에서 발급돼 파싱 장애를 은폐하지 않는지: Task 1의 모호한 본문과 게시 목록 근거 테스트.
- 실패한 첫 기간 뒤 두 번째 기간, 학교 검색 실패 뒤 독립 빈 검색의 실행 여부: Task 2의 단계 독립성과 SKIPPED 테스트.
- 취소·비정상 결과 파일이 정상/복구로 둔갑하지 않는지: Tasks 2/3의 취소·파일/단계 불일치 테스트.
- 예전 실행과 재실행 attempt가 최신 장애/복구 상태를 덮지 않는지: Task 3의 실행 순서 테스트.
- 이슈 본문·Summary·JSON에 민감한 메시지나 Markdown 제어문자 삽입 여부: Tasks 2/3의 마스킹·출력 테스트.

## Task 1: 분류에 필요한 오류 출처와 미게시 근거
**Files:** ComciganParser.kt, ComciganClient.kt, ComciganResponseDecoder.kt, ComciganPublishedWeeks.kt (core/comcigan/src/main/kotlin/io/github/mchany/comciganneo/comcigan/); 같은 test 경로의 HealthFailureTest.kt.
**Interfaces:**
- Produces: StructureChanged의 기본 PROTOCOL/선택 PARSE 출처; 기존 catch 호환.
- Produces: TimetableUnavailable의 기본 AMBIGUOUS/선택 CATALOG_ABSENT 근거; 기본 생성자 호환.
- Produces: 상태 코드를 가진 IOException 하위 ComciganHttpException, 본문 한계 오류 ComciganResponseException.
- Consumes: 기존 parser/client API와 PublishedWeeks 검증.

- [x] 기존 API를 호출하는 테스트로 잘못된 JSON, 암호문, 자료 필드, 빈/시작일 누락 응답, 검증된 게시 목록 부재, HTTP 5xx/403/429를 재현하고 타입/근거를 assert한다.
- [x] 새 필드/타입 사용 전 컴파일 가능한 기존 응답 동작 테스트를 먼저 실행해 알려진 오분류를 확인한다. 타입을 추가한 뒤 구체적인 assert로 GREEN을 확인한다.
- [x] 기존 예외 계층/메시지/갱신 정책을 보존하고 발생 지점만 분류한다. ComciganPublishedWeeks의 응답 구조 오류도 PARSE로 구분한다.
- [x] Run: 로컬 JDK17로 :core:comcigan:test. Expected: 기존+신규 테스트 실패 0.
- [x] git diff --check 뒤 변경된 오류/테스트만 커밋.

## Task 2: 구조화 Health 실행기와 제한 재확인
**Files:** LiveCheck.kt 수정; ParserHealth.kt 신규 (모델·실행·분류), ParserHealthReport.kt 신규 (JSON·Markdown·파일); HealthRunnerTest.kt 신규; LiveCheckTest.kt 기존 17개 보존.
**Interfaces:**
- Consumes: Task 1의 타입과 근거.
- Produces: runParserHealth(schoolName, search, fetch, today, now, pause): HealthReport (suspend; 람다로 테스트 주입).
- Produces: HealthReport.toJson(), toMarkdown(), isSuccess; writeHealthResult(Path, HealthReport).
- HealthReport 필드: schemaVersion=1, checkedAt, timezone, target, overall, steps.
- Step 필드: id(exact_search/empty_search/timetable_0/timetable_1), status, errorType, code, referenceDate, week, expectedStart, actualStart, httpStatus, attempts.
- Attempt 필드: number, status, errorType, code, dateRefetches; Task 3은 이 계약을 소비한다.

- [x] 테스트부터 작성: 전체 정상, 확정 미게시 week0/1, 모호한 미게시, 수업 없음, 잘못된 기간/학급/교시/과목, 단계 독립성, 학교 실패 SKIPPED.
- [x] 실패 확인 후 실행기 구현. HealthValidationFailure를 검증 오류로 쓰고 기존 날짜 함수에 선택적 관찰 콜백만 추가하여 기본 호출을 보존.
- [x] 네트워크/5xx 재시도, 403/429/구조/파싱/검증 비재시도, 취소 전파, 첫 실패 기록, 자정 1회·연속 날짜 변경 테스트 RED→GREEN.
- [x] JSON 필드·네 단계·상태 집계·민감문자 마스킹·Markdown·원자 파일 기록 테스트 RED→GREEN.
- [x] LiveCheck.main은 production client를 주입하고 Summary/JSON을 기록한 뒤 성공 여부로 종료.
- [x] Run: :core:model:test :core:comcigan:test. Expected: 전체 실패 0, PR #27 17개 포함.
- [x] git diff --check 뒤 Health 실행기/테스트 커밋.

## Task 3: 구조화 보고·복구 추적·워크플로
**Files:** tools/parser-health.cjs, tools/parser-health.test.cjs, .github/workflows/parser-health.yml.
**Interfaces:**
- Consumes: Task 2 JSON 계약과 Actions steps.
- Produces: reportFailure({github,context,steps,summaryPath,resultPath,attempt}) 기존 export 유지; 성공 실행도 처리.
- Issue 마커 동일; 메타데이터로 첫 실패/최신 runId+attempt를 보존하고 비교. 과거 상태/레거시 이슈는 보수적으로 갱신한다.

- [x] 기존 메모리 API fixture를 확장하여 결과 JSON 테스트를 작성하고 기존 보고기의 잘못된 정상/복구 처리를 RED로 확인.
- [x] JSON 크기/스키마/단계/상태/파일 및 Actions 성공과 결과의 일관성을 검증. 누락/비정상은 ENVIRONMENT_ERROR. 회귀 실패와 실행환경 실패도 분리된 코드로 표기.
- [x] 단일 이슈 신규/갱신·중복 방지·첫 실패 보존·수동 종료·전체 HEALTHY 복구·UNPUBLISHED 비복구·역순 실행/attempt·API 실패 테스트 RED→GREEN.
- [x] workflow에 PARSER_HEALTH_RESULT 경로를 전달하고 !cancelled 성공 실행 보고도 허용. 기존 입력/파일명/직렬화/PR 안전 제한 유지.
- [x] 실제 shell 대역 테스트로 Summary+JSON 파일 전달 및 실패 종료 유지 검증.
- [x] Run: node --test tools/parser-health.test.cjs tools/release.test.cjs, actionlint. Expected: 모두 통과.
- [x] git diff --check 뒤 보고기/워크플로/테스트 커밋.

## Task 4: 문서·최종 검증·PR
**Files:** README.md Parser Health 절, docs/parser-health-issues.md, 이 설계/계획 문서.
**Interfaces:** Tasks 1–3의 실제 동작을 문서화. Worker는 기존 dispatch 계약만 검증하며 실제 배포 수정 없음.

- [ ] 상태·오류 유형·판단 불가·재시도·수동 종료/복구·배지 한계·검사 실패 추적 명령을 문서화.
- [ ] 전체 Kotlin/Node 테스트, actionlint, diff 범위 검사. Expected: 실패 0 및 금지 영역 변경 없음.
- [ ] 별도 실서버 실행 :core:comcigan:run --args=중일고등학교. Expected: 네 단계 JSON/Summary 및 실서버 상태를 사실대로 기록. 실패 시 원인과 미검증 범위를 분리.
- [ ] 새 컨텍스트 전체 브랜치 검토 후 Important/Critical은 한 차례 RED→GREEN 수정, 전체 테스트 재실행. 사소한 보류는 기록.
- [ ] 검증된 문서/구현 커밋, origin 별도 브랜치 push, main 대상 PR 생성. 기존 Actions는 정상 실행.
- [ ] 사용자 지정 다섯 보고 항목, 미검증 사항, 판단·보류, PR 및 복사 가능한 PR 설명 제공.
