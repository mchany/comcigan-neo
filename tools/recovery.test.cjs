'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const recovery = require('./recovery.cjs');
const SHA = 'a'.repeat(40);
const context = {repo: {owner: 'mchany', repo: 'comcigan-neo'}, ref: 'refs/heads/main', eventName: 'workflow_dispatch'};
const source = {id: 123, status: 'completed', conclusion: 'failure', event: 'push', head_branch: 'main', path: '.github/workflows/release.yml', head_sha: SHA, repository: {full_name: 'mchany/comcigan-neo'}, head_repository: {full_name: 'mchany/comcigan-neo'}, html_url: 'https://github.com/mchany/comcigan-neo/actions/runs/123'};
/** 역할: 복구 출처 검증용 API를 모사한다. 입력: 실행 정보와 작업 목록 제공자. 출력: Octokit 대역과 출력 기록. */
function fixture(run = source, jobs = () => [{name: 'sign', status: 'completed', conclusion: 'success'}]) {
  const outputs = {};
  return {context, runId: '123', core: {setOutput: (key, value) => { outputs[key] = value; }}, outputs,
    github: {async request(route, args) { return {data: route.endsWith('/jobs') ? {jobs: jobs(args.page)} : run}; }}};
}
// 역할: 이전 재시도의 성공한 서명 작업도 복구 근거로 인정한다. 입력: 여러 페이지의 작업. 출력: 원래 SHA와 실행 URL.
test('source validation accepts a successful sign job from an earlier attempt', async () => {
  const args = fixture(source, page => page === 1 ? Array.from({length: 100}, () => ({name: 'publish', status: 'completed', conclusion: 'failure'})) : [{name: 'sign', status: 'completed', conclusion: 'success'}]);
  const result = await recovery.getRecoverySource(args);
  assert.deepEqual(result, {run_id: '123', sha: SHA, run_url: source.html_url});
  assert.equal(args.outputs.sha, SHA);
});
// 역할: 임의 저장소·브랜치·워크플로와 미완료 실행을 거부한다. 입력: 변조 실행 정보. 출력: 복구 중단.
test('untrusted recovery sources are rejected', async () => {
  for (const patch of [{id: 124}, {repository: {full_name: 'other/repo'}}, {head_repository: {full_name: 'fork/comcigan-neo'}}, {event: 'pull_request'}, {head_branch: 'feature'}, {path: '.github/workflows/other.yml'}, {status: 'in_progress'}, {conclusion: 'cancelled'}, {head_sha: 'bad'}, {html_url: 'https://example.com'}]) {
    await assert.rejects(() => recovery.getRecoverySource(fixture({...source, ...patch})), /복구|원본/);
  }
  await assert.rejects(() => recovery.getRecoverySource({...fixture(), runId: '123;echo x'}), /실행 번호/);
  await assert.rejects(() => recovery.getRecoverySource({...fixture(), context: {...context, ref: 'refs/heads/feature'}}), /main/);
});
// 역할: 성공한 서명 산출물 출처가 없으면 중단한다. 입력: 실패 서명 작업과 조회 한도 초과. 출력: 오류.
test('missing successful signing and excessive job history fail closed', async () => {
  await assert.rejects(() => recovery.getRecoverySource(fixture(source, () => [{name: 'sign', conclusion: 'failure', status: 'completed'}])), /서명/);
  await assert.rejects(() => recovery.getRecoverySource(fixture(source, () => Array.from({length: 100}, () => ({name: 'build'})))), /한도/);
});
// 역할: 재사용할 태그가 원래 소스 커밋을 가리킬 때만 허용한다. 입력: 정상·변경·주석 태그. 출력: 정상 또는 거부.
test('recovery requires the original lightweight tag without changing it', async () => {
  const metadata = {version: '1.1.1', version_code: 17, summary: '수정', changes: ['수정']};
  for (const object of [{type: 'commit', sha: SHA}, {type: 'commit', sha: 'b'.repeat(40)}, {type: 'tag', sha: SHA}]) {
    const args = {context, metadata, sha: SHA, github: {async request() { return {data: {object}}; }}};
    if (object.type === 'commit' && object.sha === SHA) await recovery.verifyRecoveryTag(args);
    else await assert.rejects(() => recovery.verifyRecoveryTag(args), /태그/);
  }
  await assert.rejects(() => recovery.verifyRecoveryTag({context, metadata, sha: SHA, github: {async request() { throw new Error('404'); }}}), /태그/);
});
