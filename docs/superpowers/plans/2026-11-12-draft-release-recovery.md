# Draft 조회와 기존 서명 산출물 복구

목표: 태그 조회가 Draft에 404를 반환해 생기는 null.assets 오류를 해결하고 기존 서명 APK를 보존해 게시를 이어간다.

근거: Release 실행 43147380805의 job 130954318425, v1.1.1 Draft ID 442466188. 태그 조회 404와 ID 조회 성공을 실제 API로 확인했다. 기존 PR17은 이미 병합되었으므로 최신 main 기준 별도 수정 PR로 제공한다.

- [x] 실제 API 동작을 모사한 회귀 테스트에서 같은 TypeError를 재현한다.
- [x] Draft 목록 탐색·중복 거부·ID 기반 재조회와 기존 첨부 불변 검사를 구현한다.
- [x] 원본 main Release 실행·성공한 sign·기존 태그를 확인하는 수동 복구 Workflow를 추가한다.
- [x] 원래 SHA·실행 URL·서명 artifact를 유지하고 실제 재빌드·태그 생성 없이 복구한다.
- [x] Node 테스트22개와 actionlint, 실제 원본 산출물의 읽기 전용 바이트 비교를 확인한다.

검토 초점: 기존 태그·APK 교체 금지, 중복 Draft 임의 선택 금지, 원본 실패 실행 재시도 이력, 출처가 다른 artifact 거부, 오래된 코드 재실행 대신 수정 코드 적용.

검증: 기존 signed-release와 Draft의 APK·release-metadata.json·SHA256SUMS.txt가 복구 조립 결과와 정확히 일치했다. 독립 리뷰에서 추가 중요 문제 없음. 실제 복구 Workflow·게시·README 변경은 실행하지 않았다.
