'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {renderReadme} = require('./release.cjs');
const merger = require('./readme-merge.cjs');
const SHA = 'a'.repeat(40), MAIN = 'b'.repeat(40), REPO = 'mchany/comcigan-neo';
const context = {repo:{owner:'mchany',repo:'comcigan-neo'},ref:'refs/heads/main',sha:MAIN,eventName:'workflow_dispatch'};
const metadata = {version:'1.1.1',summary:'검색 복구',repository:REPO};
const mainText = '앞\n<!-- release:start -->old<!-- release:end -->\n사용자 본문';
/** 역할: CI·릴리즈·PR API와 출력 기록을 모사한다. 입력: 상태 변경 옵션. 출력: 독립적인 검증 대역. */
function fixture(options = {}) {
  const outputs = {}, calls = [], pauses = []; let reads=0;
  const pull = {number:19,state:'open',merged:false,mergeable:true,mergeable_state:options.state || 'clean',head:{sha:SHA,ref:'codex/readme-release-sync',repo:{full_name:REPO}},base:{ref:'main',repo:{full_name:REPO}}};
  const checks = ['build','check','Secret Scan'].map(name=>({name,status:'completed',conclusion:'success',head_sha:SHA,app:{slug:'github-actions'}}));
  const core = {setOutput:(k,v)=>{outputs[k]=String(v);},summary:{addRaw(){return this;},async write(){}}};
  const github = {async request(route,args) {
    calls.push([route,args]);
    if(route === 'GET /repos/{owner}/{repo}/pulls') return {data:options.none ? [] : [pull]};
    if(route === 'GET /repos/{owner}/{repo}/pulls/{pull_number}') {
      const state=options.states?.[Math.min(reads++,options.states.length-1)] || pull.mergeable_state;
      return {data:options.changedHead ? {...pull,head:{...pull.head,sha:'c'.repeat(40)}} : {...pull,mergeable_state:state,mergeable:state === 'unknown' ? null : pull.mergeable}};
    }
    if(route === 'GET /repos/{owner}/{repo}/releases/latest') return {data:{id:10,tag_name:'v1.1.1',assets:[{id:1,name:'release-metadata.json'}]}};
    if(route.includes('/releases/assets/')) return {data:Buffer.from(JSON.stringify(metadata))};
    if(route.includes('/git/ref/')) return {data:{object:{sha:MAIN}}};
    if(route.includes('/contents/')) return {data:{type:'file',encoding:'base64',content:Buffer.from(args.ref===MAIN ? mainText : options.stale ? mainText : renderReadme(mainText,metadata)).toString('base64')}};
    if(route.endsWith('/files')) return {data:options.extraFile ? [{filename:'README.md',status:'modified'},{filename:'app/build.gradle.kts',status:'modified'}] : [{filename:'README.md',status:'modified'}]};
    if(route.endsWith('/check-runs')) return {data:{check_runs:options.checks || checks}};
    if(route.endsWith('/status')) return {data:options.legacy || {state:'pending',total_count:0}};
    if(route.startsWith('PUT ')) {pull.merged=true;return {data:{merged:true}};}
    throw new Error(route);
  }};
  return {github,context,core,outputs,calls,pull,checks,pauses,pause:async(ms)=>{pauses.push(ms);}};
}
// 역할: 완료된 정확한 SHA의 CI만 병합 허용한다. 입력: 세 필수 검사 성공과 빈 legacy status. 출력: 준비 완료.
test('successful CI allows merge even when empty legacy status says pending',async()=>{
  const f=fixture();await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'true');assert.equal(f.outputs.sha,SHA);assert.equal(f.outputs.release_id,'10');
});
// 역할: 관찰된 병합 계산·CI 실행 상태를 오류 대신 대기로 둔다. 입력: unknown·unstable. 출력: 준비되지 않음.
test('unknown or unstable mergeability waits without merging',async()=>{
  for(const state of ['unknown','unstable']){const f=fixture({state});await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'false');assert.ok(f.calls.every(([r])=>!r.startsWith('PUT ')));}
});
// 역할: 실행 중·실패·미등록 검사와 commit status를 구분한다. 입력: 다양한 CI 상태. 출력: 병합 대기.
test('failed, missing or pending CI cannot merge',async()=>{
  const base=fixture().checks;
  for(const checks of [base.slice(0,2),base.map(c=>({...c,status:'in_progress',conclusion:null})),base.map(c=>({...c,conclusion:'failure'})),base.map(c=>({...c,head_sha:MAIN}))]){
    const f=fixture({checks});await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'false');
  }
  const f=fixture({legacy:{state:'pending',total_count:1}});await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'false');
});
// 역할: 오래된 완료 이벤트가 새 HEAD를 처리하지 않는다. 입력: 다른 예상 SHA. 출력: 준비되지 않음.
test('old completion event wakes current head validation rather than losing the latest signal',async()=>{
  const f=fixture();await merger.checkReadmeMerge({...f,expectedHead:MAIN});assert.equal(f.outputs.ready,'true');assert.equal(f.outputs.sha,SHA);assert.ok(f.calls.filter(([r])=>r.endsWith('/check-runs')).every(([,a])=>a.ref===SHA));
  const pending=fixture({checks:fixture().checks.map(c=>({...c,status:'in_progress',conclusion:null}))});await merger.checkReadmeMerge({...pending,expectedHead:MAIN});assert.equal(pending.outputs.ready,'false');assert.ok(pending.calls.filter(([r])=>r.endsWith('/check-runs')).every(([,a])=>a.ref===SHA));
});
// 역할: 마지막 CI 완료 직후의 병합 가능성 계산 지연을 제한적으로 재조회한다. 입력: unknown 뒤 clean. 출력: 같은 HEAD 준비 완료.
test('successful CI permits bounded same-head mergeability refresh',async()=>{
  const f=fixture({states:['unknown','unknown','clean']});await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'true');assert.equal(f.pauses.length,2);assert.ok(f.pauses.every(ms=>ms===1000));
  const unknown=fixture({state:'unknown'});await merger.checkReadmeMerge(unknown);assert.equal(unknown.outputs.ready,'false');assert.equal(unknown.pauses.length,4);
});
// 역할: 실패·실행 중인 CI와 보호 규칙 차단에는 재조회 대기를 사용하지 않는다. 입력: 미완료 CI 또는 blocked. 출력: 즉시 대기.
test('CI failures and blocked states do not poll mergeability',async()=>{
  const f=fixture({state:'unknown',checks:fixture().checks.map(c=>({...c,status:'in_progress',conclusion:null}))});await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'false');assert.equal(f.pauses.length,0);
  const blocked=fixture({state:'blocked'});await merger.checkReadmeMerge(blocked);assert.equal(blocked.outputs.ready,'false');assert.equal(blocked.pauses.length,0);
});
// 역할: 관리 영역 이외 변경과 다른 저장소 PR을 거부한다. 입력: 변경 파일·본문·저장소 변조. 출력: 검사 오류.
test('extra files, stale README or foreign head cannot be approved',async()=>{
  for(const options of [{extraFile:true},{stale:true}]) await assert.rejects(()=>merger.checkReadmeMerge(fixture(options)),/README|최신/);
  const f=fixture();f.pull.head.repo.full_name='other/repo';await assert.rejects(()=>merger.checkReadmeMerge(f),/전용|저장소/);
});
// 역할: 읽기 검사 이후에도 HEAD와 최신 릴리즈를 재검증한다. 입력: 정상·변경 HEAD. 출력: SHA 조건부 병합 또는 거부.
test('write step merges only the checked head using conditional REST merge',async()=>{
  const f=fixture();await merger.mergeReadme({...f,number:'19',sha:SHA,releaseId:'10'});const request=f.calls.find(([r])=>r.startsWith('PUT '));assert.equal(request[1].sha,SHA);assert.equal(f.outputs.readme_status,'synced');
  const changed=fixture({changedHead:true});await assert.rejects(()=>merger.mergeReadme({...changed,number:'19',sha:SHA,releaseId:'10'}),/HEAD/);assert.ok(!changed.calls.some(([r])=>r.startsWith('PUT ')));
});
// 역할: 이후 병합 차단과 릴리즈 교체를 우회하지 않는다. 입력: 차단 상태·이전 release ID. 출력: 병합하지 않음.
test('write step does not bypass protected or outdated release state',async()=>{
  const f=fixture({state:'blocked'});await merger.mergeReadme({...f,number:19,sha:SHA,releaseId:10});assert.equal(f.outputs.readme_status,'pending');assert.ok(!f.calls.some(([r])=>r.startsWith('PUT ')));
  await assert.rejects(()=>merger.mergeReadme({...fixture(),number:19,sha:SHA,releaseId:9}),/릴리즈/);
});
// 역할: 이미 처리된 PR은 새 커밋이나 PR을 만들지 않는다. 입력: 열린 동기화 PR 없음. 출력: noop.
test('no open synchronization PR is a no-op',async()=>{const f=fixture({none:true});await merger.checkReadmeMerge(f);assert.equal(f.outputs.ready,'false');assert.ok(f.calls.every(([r])=>r.startsWith('GET ')));});
