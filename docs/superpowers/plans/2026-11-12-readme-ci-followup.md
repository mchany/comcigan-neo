# README CI 대기와 후속 병합

목표: README PR의 CI 등록·실행·병합 상태 계산을 정상 대기로 처리하고 완료 후 같은 HEAD만 병합한다.

근거: Recover Release 실행 43150838054의 readme 단계. main 보호 규칙 없음, allow_auto_merge=true, PR19 unknown/unstable 및 CI 진행 상태를 실제 API로 확인했다.

- [x] 정상 대기가 예외가 되고 clean PR이 검사 전에 직접 병합되는 동작을 테스트에서 재현한다.
- [x] release.readme는 예약 병합 불가 시 pending을 기록하고 후속 검증을 안내한다.
- [x] 신뢰된 main 코드만 사용하는 Finish README sync를 완료 이벤트·수동 실행으로 연결한다.
- [x] 읽기 GITHUB_TOKEN으로 현재 HEAD의 전체 CI·README 단독 변경·최신 본문을 확인한다.
- [x] 준비된 경우에만 App 토큰으로 HEAD·본문·최신 릴리즈·clean을 재확인하고 SHA 조건부 병합한다.
- [x] 현재 CI만 신뢰하는 이벤트 병합, 제한된 상태 재조회, 빈 legacy status, 실패·경합·외부 변경을 검증한다.

검증: Node32개·actionlint·구문 검사 통과. 실제 PR19에 최종 읽기 검사를 적용해 ready=true와 동일 HEAD fd9bee2e3b0e211a4787f30ab4dd31512f6f00ba를 확인했다. 독립 코드 리뷰에서 중요 문제 없음. 실제 PR 병합·릴리즈·권한·보호 설정은 변경하지 않았다.

트레이드오프: CI 작업에 비밀값을 전달하지 않기 위해 읽기와 App 쓰기를 분리한다. 완료 이벤트는 상태 변경 명령이 아니라 현재 상태 재검사의 신호로 처리해 중복·늦은 이벤트에도 수렴한다.
