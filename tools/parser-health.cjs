'use strict';
const {IDS,TYPES,CODES,isInstant,readResult,renderResult} = require('./parser-health-result.cjs');
const MARKER='<!-- comcigan-neo:parser-health-failure -->';
const STATE_PREFIX='<!-- parser-health-state:';
const DECIMAL=/^[1-9][0-9]{0,19}$/;
const FENCE=String.fromCharCode(96).repeat(3);
function identity(runId,attempt) {
  const value={runId:String(runId),attempt:String(attempt)};
  if(!DECIMAL.test(value.runId) || !/^[1-9][0-9]{0,8}$/.test(value.attempt)) throw new Error('Invalid Actions execution identity');
  return value;
}
function compare(a,b) {
  for(const k of ['runId','attempt']) {
    if(BigInt(a[k])!==BigInt(b[k])) return BigInt(a[k])>BigInt(b[k])?1:-1;
  }
  return 0;
}
function runUrl(observation) {
  return 'https://github.com/mchany/comcigan-neo/actions/runs/'+observation.runId+'/attempts/'+observation.attempt;
}
function safeObservation(o) {
  if(!o || !DECIMAL.test(o.runId) || !/^[1-9][0-9]{0,8}$/.test(o.attempt)) return null;
  if(o.sha!==null && !(typeof o.sha==='string' && /^[a-f0-9]{40}$/.test(o.sha))) return null;
  if(o.checkedAt!==null && !isInstant(o.checkedAt)) return null;
  if(!['FAIL','INDETERMINATE','HEALTHY','UNPUBLISHED'].includes(o.overall) || !Array.isArray(o.errors) || o.errors.length>4) return null;
  if(!o.errors.every(e=>e && [...IDS,'pipeline'].includes(e.stage) && TYPES.includes(e.errorType) && CODES.includes(e.code))) return null;
  return {runId:o.runId,attempt:o.attempt,sha:o.sha,checkedAt:o.checkedAt,overall:o.overall,
    errors:o.errors.map(e=>({stage:e.stage,errorType:e.errorType,code:e.code}))};
}
function previousState(body) {
  const match=body?.match(/<!-- parser-health-state:([^\r\n]{1,12000}) -->/);
  if(match) {
    try {
      const s=JSON.parse(match[1]),latest=safeObservation(s.latest);
      const first=s.firstFailure===null?null:safeObservation(s.firstFailure);
      const last=s.lastFailure===null?null:safeObservation(s.lastFailure);
      if(latest && (s.firstFailure===null || first) && (s.lastFailure===null || last))
        return {latest,firstFailure:first,lastFailure:last};
    } catch {}
  }
  const legacy=body?.match(/https:\/\/github\.com\/mchany\/comcigan-neo\/actions\/runs\/([1-9][0-9]{0,19})\/attempts\/([1-9][0-9]{0,8})(?![0-9])/);
  if(!legacy) return null;
  const observation={runId:legacy[1],attempt:legacy[2],sha:null,checkedAt:null,overall:'FAIL',errors:[]};
  return {latest:observation,firstFailure:observation,lastFailure:observation};
}
function observation(report,execution,sha) {
  return {...execution,sha:/^[a-f0-9]{40}$/.test(sha||'')?sha:null,checkedAt:report.checkedAt,overall:report.overall,
    errors:report.steps.filter(s=>s.errorType).map(s=>({stage:s.id,errorType:s.errorType,code:s.code}))};
}
function failureSection(title,o) {
  if(!o) return '### '+title+'\n\n이전 실패의 검증 가능한 실행 정보가 없습니다.';
  return '### '+title+'\n\n[실행 기록]('+runUrl(o)+')\n\n'+
    (o.errors.map(e=>'- '+e.stage+': '+e.errorType+' / '+e.code).join('\n')||'레거시 이슈: 오류 분류 기록 없음');
}
function escapedJson(value) {
  return JSON.stringify(value,null,2).replace(/</g,'\\u003c').replace(/\x60/g,'\\u0060').replace(/@/g,'\\u0040');
}

/** One open bot issue, manual closure; only complete HEALTHY evidence confirms recovery. */
async function reportFailure({github,context,steps,resultPath,attempt='1'}) {
  if(context.repo.owner!=='mchany' || context.repo.repo!=='comcigan-neo' ||
    context.eventName!=='workflow_dispatch' || context.ref!=='refs/heads/main' ||
    Object.values(steps).some(s=>s.outcome==='cancelled')) return null;

  const execution=identity(context.runId,attempt),report=readResult(resultPath,steps);
  const current=observation(report,execution,context.sha);
  const failed=!['HEALTHY','UNPUBLISHED'].includes(report.overall);
  const issues=await github.paginate(github.rest.issues.listForRepo,{...context.repo,state:'open',per_page:100});
  const existing=issues.find(i=>!i.pull_request && i.user?.login==='github-actions[bot]' && i.body?.startsWith(MARKER));
  if(!existing && !failed) return null;
  const previous=previousState(existing?.body);
  if(previous && compare(execution,previous.latest)<0) return existing.number;

  const state={firstFailure:previous?.firstFailure || (failed?current:null),
    lastFailure:failed?current:previous?.lastFailure || previous?.firstFailure || null,latest:current};
  const recovery=report.overall==='HEALTHY';
  const types=[...new Set(current.errors.map(e=>e.errorType))].join(', ');
  const title=recovery?'[Parser Health] 복구 확인 — 수동 종료 대기':
    report.overall==='UNPUBLISHED'?'[Parser Health] 정상 미게시 관측 — 복구 미확인':
    '[Parser Health]['+types+'] '+(report.overall==='INDETERMINATE'?'판단 불가':'상태 검사 실패');
  const status=recovery?'모든 필수 검사가 HEALTHY로 복구 확인됨. 이슈 종료는 운영자가 수동으로 결정합니다.':
    report.overall==='UNPUBLISHED'?'정상 미게시를 확인했습니다. 시간표 파싱 복구 미확인 상태이며 이슈를 자동 종료하지 않습니다.':
    report.overall==='INDETERMINATE'?'정상 여부를 확정할 수 없습니다. 파서 장애로 단정하지 말고 아래 오류 유형과 단계를 확인하세요.':
    '검사에 실패했습니다. 네트워크·서버·프로토콜·파싱·검증 오류를 구분하여 확인하세요.';
  const inputs=context.payload?.inputs || {};
  const metadata={sha:current.sha,source:String(inputs.source||'manual').slice(0,100),
    scheduled_time:inputs.scheduled_time?String(inputs.scheduled_time).slice(0,200):null,
    steps:Object.fromEntries(['checkout','java','gradle','reporting-tests','regression','live','summary'].map(id=>
      [id,['success','failure','skipped','cancelled'].includes(steps[id]?.outcome)?steps[id].outcome:'unknown']))};
  const body=[MARKER,STATE_PREFIX+JSON.stringify(state)+' -->','## Parser Health 상태','',status,'',
    '[최신 실행 및 요약]('+runUrl(current)+')','',failureSection('첫 실패',state.firstFailure),'',
    failureSection('최근 실패',state.lastFailure),'','### 실행 정보','',FENCE+'json',escapedJson(metadata),FENCE,'',
    renderResult(report),'','동일한 열린 자동 이슈를 갱신합니다. 자동 종료·재열기는 하지 않습니다.'].join('\n');
  if(existing) {
    if(existing.body!==body) await github.rest.issues.update({...context.repo,issue_number:existing.number,title,body});
    return existing.number;
  }
  const {data}=await github.rest.issues.create({...context.repo,title,body});
  return data.number;
}
module.exports={reportFailure};
