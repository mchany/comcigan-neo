'use strict';
const {renderReadme} = require('./release.cjs');
const REPO = 'mchany/comcigan-neo', BRANCH = 'codex/readme-release-sync';
const SHA = /^[0-9a-f]{40}$/;
const REQUIRED = ['build','check','Secret Scan'];

/** 역할: 신뢰된 main 코드 실행만 허용한다. 입력: Actions context. 출력: 공식 저장소 인수. */
function identity(context) {
  if(`${context.repo?.owner}/${context.repo?.repo}` !== REPO || context.ref !== 'refs/heads/main' || !SHA.test(context.sha || '') || !['workflow_dispatch','workflow_run'].includes(context.eventName)) throw new Error('공식 저장소 main의 README 후속 실행만 허용합니다.');
  return context.repo;
}
/** 역할: 제한 시간과 비밀값 없는 오류로 API를 호출한다. 입력: API·context·경로·인수. 출력: 응답 데이터. */
async function call(github,context,route,args={}) {
  try { return (await github.request(route,{...identity(context),...args,request:{timeout:60000}})).data; }
  catch(error) { throw new Error(`README API 요청 실패: ${Number.isInteger(error.status) ? error.status : 'network'}`); }
}
/** 역할: 정상 대기와 실제 동기화를 구분해 기록한다. 입력: core·상태·이유. 출력: Actions 출력과 요약. */
async function report(core,status,reason) {
  core.setOutput('ready','false'); core.setOutput('readme_status',status);
  await core.summary.addRaw(`README 상태: ${status}\n\n${reason}\n`).write();
}
/** 역할: 동기화 전용 PR과 예상 HEAD를 검사한다. 입력: PR 정보·예상 SHA. 출력: 정상 또는 오류. */
function validatePull(pull,sha) {
  if(!pull || pull.head?.repo?.full_name !== REPO || pull.base?.repo?.full_name !== REPO || pull.head?.ref !== BRANCH || pull.base?.ref !== 'main') throw new Error('README 전용 저장소·브랜치 PR이 아닙니다.');
  if(!SHA.test(pull.head.sha || '') || (sha && pull.head.sha !== sha)) throw new Error('README PR의 HEAD가 변경되었습니다.');
}
/** 역할: 파일 API의 UTF-8 README를 읽는다. 입력: API·context·커밋 SHA. 출력: 원문 문자열. */
async function textAt(github,context,sha) {
  const file=await call(github,context,'GET /repos/{owner}/{repo}/contents/{path}',{path:'README.md',ref:sha});
  if(file?.type !== 'file' || file.encoding !== 'base64' || typeof file.content !== 'string') throw new Error('README 파일 응답이 잘못되었습니다.');
  return new TextDecoder('utf-8',{fatal:true}).decode(Buffer.from(file.content,'base64'));
}
/** 역할: 최신 릴리즈와 main 본문에 맞는 README 변경만 허용한다. 입력: API·PR. 출력: 검증된 공개 릴리즈 ID. */
async function validateReadme(github,context,pull) {
  const latest=await call(github,context,'GET /repos/{owner}/{repo}/releases/latest');
  const assets=latest?.assets?.filter(asset=>asset.name === 'release-metadata.json');
  if(!Number.isSafeInteger(latest?.id) || assets?.length !== 1) throw new Error('최신 릴리즈 메타데이터를 확인할 수 없습니다.');
  const raw=await call(github,context,'GET /repos/{owner}/{repo}/releases/assets/{asset_id}',{asset_id:assets[0].id,headers:{accept:'application/octet-stream'}});
  const metadata=JSON.parse(Buffer.from(raw).toString('utf8'));
  if(metadata.repository !== REPO || `v${metadata.version}` !== latest.tag_name) throw new Error('최신 릴리즈 메타데이터가 일치하지 않습니다.');
  const files=await call(github,context,'GET /repos/{owner}/{repo}/pulls/{pull_number}/files',{pull_number:pull.number,per_page:100,page:1});
  if(!Array.isArray(files) || files.length !== 1 || files[0].filename !== 'README.md' || files[0].status !== 'modified') throw new Error('README 이외의 변경은 자동 병합하지 않습니다.');
  const main=(await call(github,context,'GET /repos/{owner}/{repo}/git/ref/{ref}',{ref:'heads/main'})).object?.sha;
  if(!SHA.test(main || '')) throw new Error('main SHA를 확인할 수 없습니다.');
  const expected=renderReadme(await textAt(github,context,main),metadata);
  if(await textAt(github,context,pull.head.sha) !== expected) throw new Error('README가 최신 릴리즈·main 본문과 다릅니다.');
  return latest.id;
}
/** 역할: 필수 CI와 legacy status의 성공 여부를 확인한다. 입력: 읽기 API·정확한 PR HEAD. 출력: 모두 성공했을 때만 true. */
async function checksPassed(github,context,sha) {
  const checks=[];
  for(let page=1;page<=10;page++) {
    const data=await call(github,context,'GET /repos/{owner}/{repo}/commits/{ref}/check-runs',{ref:sha,filter:'latest',per_page:100,page});
    if(!Array.isArray(data?.check_runs)) throw new Error('CI 검사 응답이 잘못되었습니다.');
    checks.push(...data.check_runs);
    if(data.check_runs.length < 100) break;
    if(page === 10) throw new Error('CI 검사 조회 한도를 초과했습니다.');
  }
  if(!checks.length || checks.some(check=>check.head_sha !== sha || check.status !== 'completed' || !['success','neutral','skipped'].includes(check.conclusion))) return false;
  if(REQUIRED.some(name=>!checks.some(check=>check.name === name && check.app?.slug === 'github-actions' && check.conclusion === 'success'))) return false;
  const status=await call(github,context,'GET /repos/{owner}/{repo}/commits/{ref}/status',{ref:sha});
  if(!Number.isInteger(status?.total_count) || status.total_count < 0) throw new Error('Commit status 응답이 잘못되었습니다.');
  return status.total_count === 0 || status.state === 'success';
}
/** 역할: GitHub의 비동기 병합 상태 계산을 짧게 기다린다. 입력: 대기 밀리초. 출력: 대기 완료 Promise. */
async function pauseMergeState(milliseconds) { await new Promise(resolve=>setTimeout(resolve,milliseconds)); }
/** 역할: 충돌·보호 규칙·닫힌 PR을 재시도 대상에서 제외한다. 입력: PR 상태. 출력: 즉시 대기해야 하면 true. */
function blocked(pull) { return pull.state !== 'open' || pull.draft || ['blocked','dirty','behind','draft'].includes(pull.mergeable_state); }
/** 역할: GitHub가 실제 병합 가능하다고 계산한 상태를 확인한다. 입력: PR 상태. 출력: clean이고 충돌이 없을 때만 true. */
function clean(pull) { return pull.state === 'open' && !pull.draft && pull.mergeable === true && pull.mergeable_state === 'clean'; }
/** 역할: 완료 이벤트를 깨우기 신호로 사용해 현재 HEAD의 준비 여부를 계산한다. 입력: 읽기 도구·이벤트 HEAD·대기 함수. 출력: ready·PR 번호·현재 SHA·릴리즈 ID. */
async function checkReadmeMerge({github,context,core,expectedHead=null,pause=pauseMergeState}) {
  identity(context);
  const pulls=await call(github,context,'GET /repos/{owner}/{repo}/pulls',{state:'open',base:'main',head:`${context.repo.owner}:${BRANCH}`,per_page:100});
  if(!Array.isArray(pulls) || pulls.length > 1) throw new Error('동기화 전용 PR 목록이 모호합니다.');
  if(!pulls.length) return report(core,'noop','열린 README 동기화 PR이 없습니다.');
  const pull=await call(github,context,'GET /repos/{owner}/{repo}/pulls/{pull_number}',{pull_number:pulls[0].number});
  validatePull(pull);
  if(expectedHead && expectedHead !== pull.head.sha) core.info?.('이전 HEAD의 완료 이벤트입니다. 이 이벤트의 결과 대신 현재 README HEAD와 전체 CI를 새로 검사합니다.');
  const releaseId=await validateReadme(github,context,pull);
  if(pull.merged) return report(core,'synced','이미 병합된 README PR입니다.');
  if(blocked(pull) || !await checksPassed(github,context,pull.head.sha)) return report(core,'pending','CI 등록·완료 또는 보호 규칙 충족을 기다립니다. 실패한 검사가 있으면 해당 PR에서 확인하세요.');
  let computed=pull;
  for(let attempt=0;attempt<4 && !clean(computed);attempt++) {
    if(blocked(computed)) return report(core,'pending','충돌 또는 보호 규칙 때문에 병합을 보류합니다.');
    await pause(1000);
    computed=await call(github,context,'GET /repos/{owner}/{repo}/pulls/{pull_number}',{pull_number:pull.number});
    validatePull(computed,pull.head.sha);
    if(computed.merged) return report(core,'synced','이미 병합된 README PR입니다.');
  }
  if(!clean(computed)) return report(core,'pending','CI는 성공했지만 병합 상태 계산이 아직 완료되지 않았습니다. 후속 완료 이벤트나 수동 실행에서 다시 확인합니다.');
  const fresh=await call(github,context,'GET /repos/{owner}/{repo}/pulls/{pull_number}',{pull_number:pull.number});
  validatePull(fresh,pull.head.sha);
  if(!clean(fresh) || !await checksPassed(github,context,pull.head.sha)) return report(core,'pending','검사 중 병합 상태나 CI가 변경되어 이번 병합을 보류합니다.');
  const latest=await call(github,context,'GET /repos/{owner}/{repo}/releases/latest');
  if(latest.id !== releaseId) return report(core,'pending','최신 릴리즈가 변경되어 이번 병합을 보류합니다.');
  core.setOutput('ready','true'); core.setOutput('pr_number',String(pull.number)); core.setOutput('sha',pull.head.sha); core.setOutput('release_id',String(releaseId));
}
/** 역할: 읽기 검사 후 App 토큰으로 동일 HEAD만 조건부 병합한다. 입력: App API·검증된 번호·SHA·릴리즈 ID. 출력: 실제 synced 또는 pending. */
async function mergeReadme({github,context,core,number,sha,releaseId}) {
  identity(context);
  if(!Number.isSafeInteger(Number(number)) || Number(number)<1 || !SHA.test(sha || '') || !Number.isSafeInteger(Number(releaseId)) || Number(releaseId)<1) throw new Error('README 병합 입력이 잘못되었습니다.');
  const args={pull_number:Number(number)};
  const pull=await call(github,context,'GET /repos/{owner}/{repo}/pulls/{pull_number}',args);
  validatePull(pull,sha);
  if(await validateReadme(github,context,pull) !== Number(releaseId)) throw new Error('최신 릴리즈가 변경되었습니다.');
  if(pull.merged) return report(core,'synced','이미 병합된 README PR입니다.');
  if(pull.state !== 'open' || pull.draft || pull.mergeable !== true || pull.mergeable_state !== 'clean') return report(core,'pending','병합 가능 상태가 변경되어 보호 규칙을 따릅니다.');
  const latest=await call(github,context,'GET /repos/{owner}/{repo}/releases/latest');
  if(latest.id !== Number(releaseId)) throw new Error('병합 직전 최신 릴리즈가 변경되었습니다.');
  const result=await call(github,context,'PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge',{...args,sha,merge_method:'squash'});
  if(result?.merged !== true) throw new Error('README PR 병합이 완료되지 않았습니다.');
  await report(core,'synced',`README PR #${number}의 검증된 HEAD를 병합했습니다.`);
}

module.exports={checkReadmeMerge,mergeReadme};
