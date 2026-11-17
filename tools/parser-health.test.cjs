'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {reportFailure} = require('./parser-health.cjs');
test('Node validates the same wire fixture asserted by Kotlin',()=>{
  const fixture=JSON.parse(fs.readFileSync(path.join(__dirname,'../core/comcigan/src/test/resources/health-report-v1.json'),'utf8'));
  assert.equal(require('./parser-health-result.cjs').validateResult(fixture).overall,'HEALTHY');
});

const context = {repo:{owner:'mchany',repo:'comcigan-neo'},eventName:'workflow_dispatch',
  ref:'refs/heads/main',sha:'a'.repeat(40),runId:123,serverUrl:'https://github.com',
  payload:{inputs:{source:'scheduler',scheduled_time:'2026-11-17T03:00:00Z'}}};

function result(status='HEALTHY', type=null, code=null) {
  const steps=['exact_search','empty_search','timetable_0','timetable_1'].map((id,i)=>{
    const last=i===3, s=last ? status : 'HEALTHY';
    const c=last ? (code || (s==='HEALTHY' ? 'TIMETABLE_OK' : s==='UNPUBLISHED' ? 'CATALOG_ABSENT' : 'RESPONSE_PARSE_FAILED')) :
      i===0 ? 'SEARCH_OK' : i===1 ? 'EMPTY_SEARCH_OK' : 'TIMETABLE_OK';
    return {id,status:s,errorType:last?type:null,code:c,referenceDate:'2026-11-17',week:i<2?null:i-2,
      expectedStart:i>=2 && s==='HEALTHY' ? (i===2?'2026-11-16':'2026-11-23'):null,
      actualStart:i>=2 && s==='HEALTHY' ? (i===2?'2026-11-16':'2026-11-23'):null,
      httpStatus:null,slotCount:i>=2 && s==='HEALTHY'?34:null,lessonCount:i>=2 && s==='HEALTHY'?18:null,
      attempts:[{number:1,status:s,errorType:last?type:null,code:c,dateRefetches:0,httpStatus:null}]};
  });
  return {schemaVersion:1,checkedAt:'2026-11-17T03:00:00Z',timezone:'Asia/Seoul',
    target:{school:'중일고등학교',grade:1,classNumber:1},overall:status,steps};
}
function fixture(issues=[]) {
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'health-diagnostics-'));
  const resultPath=path.join(directory,'result.json'),summaryPath=path.join(directory,'summary.md');
  const state={issues,writes:0,pages:0};
  const github={rest:{issues:{
    listForRepo:Symbol('listForRepo'),
    async create(params) { state.writes++;const issue={...params,state:'open',number:state.issues.length+1,user:{login:'github-actions[bot]'}};
      state.issues.push(issue);return {data:issue}; },
    async update(params) { state.writes++;Object.assign(state.issues.find(i=>i.number===params.issue_number),params); },
  }},async paginate(method,params) {
    assert.equal(method,github.rest.issues.listForRepo);assert.equal(params.state,'open');
    state.pages=Math.ceil(state.issues.length/params.per_page);
    return state.issues.filter(i=>i.state!=='closed');
  }};
  const f={state,github,context,resultPath,summaryPath,steps:{},attempt:'1',
    set(report) {
      fs.writeFileSync(resultPath,JSON.stringify(report));fs.writeFileSync(summaryPath,'RAW_SUMMARY_SECRET');
      f.steps=Object.fromEntries(['checkout','java','gradle','reporting-tests','regression','live','summary'].map(n=>[n,{outcome:'success'}]));
      if (!['HEALTHY','UNPUBLISHED'].includes(report.overall)) f.steps.live.outcome='failure';
      return f;
    },
    cleanup() { fs.rmSync(directory,{recursive:true,force:true}); },
  };
  return f.set(result('FAIL','PARSE_ERROR'));
}
async function withFixture(fn,issues) { const f=fixture(issues);try { await fn(f); } finally { f.cleanup(); } }

test('persistent parser failure reports its typed stage, date and attempts without raw summary',()=>withFixture(async f=>{
  await reportFailure(f);const body=f.state.issues[0].body;
  for(const text of ['PARSE_ERROR','timetable_1','2026-11-17','2026-11-17T03:00:00Z','attempts/1','a'.repeat(40)]) assert.ok(body.includes(text),text);
  assert.ok(!body.includes('RAW_SUMMARY_SECRET'));
}));
test('healthy and confirmed unpublished runs never create a new issue',()=>withFixture(async f=>{
  f.set(result());await reportFailure(f);f.set(result('UNPUBLISHED'));await reportFailure(f);
  assert.equal(f.state.writes,0);
}));
test('repeated failures update one bot issue across all pages and identical reports do not write',()=>withFixture(async f=>{
  await reportFailure(f);await reportFailure(f);
  await reportFailure({...f,context:{...context,runId:124},attempt:'2'});
  assert.equal(f.state.issues.length,106);assert.equal(f.state.writes,2);assert.equal(f.state.pages,2);
},Array.from({length:105},(_,i)=>({number:i+1,body:'unrelated'}))));
test('fully healthy run records recovery without closing and preserves the first failure',()=>withFixture(async f=>{
  await reportFailure(f);f.set(result());await reportFailure({...f,context:{...context,runId:124}});
  const i=f.state.issues[0];assert.equal(i.state,'open');assert.match(i.body,/복구 확인/);
  assert.match(i.body,/runs\/123\/attempts\/1/);assert.match(i.body,/runs\/124\/attempts\/1/);
  assert.match(i.body,/PARSE_ERROR/);assert.equal(f.state.issues.length,1);
}));
test('unpublished observation never claims recovery or closes an issue',()=>withFixture(async f=>{
  await reportFailure(f);f.set(result('UNPUBLISHED'));await reportFailure({...f,context:{...context,runId:124}});
  assert.equal(f.state.issues[0].state,'open');assert.match(f.state.issues[0].body,/복구 미확인/);
  assert.ok(!f.state.issues[0].body.includes('복구 확인됨'));
}));
test('indeterminate data creates a diagnostic issue instead of claiming parser failure or recovery',()=>withFixture(async f=>{
  f.set(result('INDETERMINATE','UNKNOWN_ERROR','AMBIGUOUS_UNPUBLISHED'));await reportFailure(f);
  assert.match(f.state.issues[0].title,/판단 불가/);assert.match(f.state.issues[0].body,/INDETERMINATE/);
}));
test('new failures after observed recovery update the same still-open issue',()=>withFixture(async f=>{
  await reportFailure(f);f.set(result());await reportFailure({...f,context:{...context,runId:124}});
  f.set(result('FAIL','NETWORK_ERROR','NETWORK_ERROR'));await reportFailure({...f,context:{...context,runId:125}});
  assert.equal(f.state.issues.length,1);assert.match(f.state.issues[0].title,/NETWORK_ERROR/);
  assert.match(f.state.issues[0].body,/runs\/123\/attempts\/1/);
}));
test('older runs and older attempts cannot overwrite newer observations',()=>withFixture(async f=>{
  await reportFailure({...f,context:{...context,runId:125},attempt:'2'});
  const saved=f.state.issues[0].body;f.set(result());
  await reportFailure({...f,context:{...context,runId:124},attempt:'3'});
  await reportFailure({...f,context:{...context,runId:125},attempt:'1'});
  assert.equal(f.state.writes,1);assert.equal(f.state.issues[0].body,saved);
}));
test('a user-closed issue is left closed and a later failure creates a new one',()=>withFixture(async f=>{
  await reportFailure(f);f.state.issues[0].state='closed';
  await reportFailure({...f,context:{...context,runId:124}});
  assert.equal(f.state.issues.length,2);assert.equal(f.state.issues[0].state,'closed');
}));
test('manual issues and pull requests are never modified',()=>withFixture(async f=>{
  await reportFailure(f);const bot=f.state.issues[0];
  f.state.issues[0]={...bot,user:{login:'human'}};
  f.state.issues.push({...bot,number:2,pull_request:{}});
  await reportFailure({...f,context:{...context,runId:124}});
  assert.equal(f.state.issues.length,3);
}));
test('legacy automatic issues retain their first failure link on migration',()=>withFixture(async f=>{
  await reportFailure(f);assert.equal(f.state.issues.length,1);
  assert.match(f.state.issues[0].body,/runs\/100\/attempts\/1/);
},[{number:1,user:{login:'github-actions[bot]'},body:'<!-- comcigan-neo:parser-health-failure -->\n[old](https://github.com/mchany/comcigan-neo/actions/runs/100/attempts/1)'}]));
test('PRs, forks, non-main and cancelled executions never write issues',()=>withFixture(async f=>{
  for(const patch of [{eventName:'pull_request'},{ref:'refs/heads/test'},{repo:{owner:'fork',repo:'comcigan-neo'}},{eventName:'push'}]) {
    await reportFailure({...f,context:{...context,...patch}});
  }
  await reportFailure({...f,steps:{...f.steps,live:{outcome:'cancelled'}}});
  assert.equal(f.state.writes,0);
}));
test('missing result is environment failure even when the live command reports success',()=>withFixture(async f=>{
  f.set(result());fs.unlinkSync(f.resultPath);await reportFailure(f);
  assert.match(f.state.issues[0].title,/ENVIRONMENT_ERROR/);
  assert.match(f.state.issues[0].body,/RESULT_MISSING/);
}));
test('pipeline failure is classified separately and cannot mark recovery',()=>withFixture(async f=>{
  f.set(result());f.steps.regression.outcome='failure';f.steps.live.outcome='skipped';await reportFailure(f);
  assert.match(f.state.issues[0].body,/ENVIRONMENT_ERROR/);assert.match(f.state.issues[0].body,/REGRESSION_FAILED/);
}));
test('failed live command with a healthy file is not recovery',()=>withFixture(async f=>{
  f.set(result());f.steps.live.outcome='failure';await reportFailure(f);
  assert.match(f.state.issues[0].body,/LIVE_RESULT_MISMATCH/);
}));
test('unknown schema, truncation and oversized files fail closed',async()=>{
  for(const text of ['{',JSON.stringify({...result(),schemaVersion:2}),'x'.repeat(200000)]) await withFixture(async f=>{
    fs.writeFileSync(f.resultPath,text);await reportFailure(f);
    assert.match(f.state.issues[0].body,/RESULT_INVALID/);assert.match(f.state.issues[0].body,/ENVIRONMENT_ERROR/);
  });
});
test('forged successful states, missing checks, bad dates, retry histories and unknown codes are rejected',async()=>{
  const mutations=[
    r=>{r.steps.pop();},r=>{r.overall='HEALTHY';r.steps[3].status='FAIL';},
    r=>{r.checkedAt='not a date';},r=>{r.steps[3].referenceDate='2026-02-30';},
    r=>{r.steps[3].code='token-secret';},r=>{r.steps[3].attempts=[];},
    r=>{r.steps[3].attempts[0].status='FAIL';},
    r=>{r.steps[3].status='UNPUBLISHED';r.steps[3].code='CATALOG_ABSENT';r.overall='UNPUBLISHED';},
    r=>{r.steps[0].status='UNPUBLISHED';r.overall='UNPUBLISHED';},
    r=>{r.steps[3].actualStart='2026-11-30';},
  ];
  for(const mutate of mutations) await withFixture(async f=>{
    const r=result();mutate(r);f.set(r);await reportFailure(f);
    assert.match(f.state.issues[0].body,/RESULT_INVALID/);
    assert.ok(!f.state.issues[0].body.includes('token-secret'));
  });
});
test('a transient failed attempt followed by healthy retry is not a new issue',()=>withFixture(async f=>{
  const r=result();const s=r.steps[3];
  s.attempts=[{number:1,status:'FAIL',errorType:'NETWORK_ERROR',code:'NETWORK_ERROR',dateRefetches:0,httpStatus:null},
    {...s.attempts[0],number:2}];f.set(r);await reportFailure(f);assert.equal(f.state.writes,0);
}));
test('different failure classes still use the same issue',()=>withFixture(async f=>{
  await reportFailure(f);
  const r=result('FAIL','SERVER_ERROR','HTTP_ERROR');r.steps[3].httpStatus=503;r.steps[3].attempts[0].httpStatus=503;
  f.set(r);await reportFailure({...f,context:{...context,runId:124}});
  assert.equal(f.state.issues.length,1);assert.match(f.state.issues[0].body,/503/);
}));
test('API errors propagate without fallback creation or retries',async()=>{
  for(const operation of ['list','create','update']) await withFixture(async f=>{
    if(operation==='update') await reportFailure(f);
    const before=f.state.writes;let calls=0;
    const fail=async()=>{calls++;throw new Error('HTTP 403');};
    if(operation==='list') f.github.paginate=fail;else f.github.rest.issues[operation]=fail;
    await assert.rejects(reportFailure({...f,attempt:'2'}),/HTTP 403/);
    assert.equal(calls,1);assert.equal(f.state.writes,before);
  });
});
test('metadata is escaped and untrusted extra result fields are never copied',()=>withFixture(async f=>{
  const r=result('FAIL','PARSE_ERROR');r.rawResponse='token-secret';f.set(r);
  await reportFailure({...f,context:{...context,payload:{inputs:{source:'\x60\x60\x60\n@someone <script>',scheduled_time:'x'.repeat(70000)}}}});
  const body=f.state.issues[0].body;
  assert.ok(body.length<65536);for(const secret of ['token-secret','RAW_SUMMARY_SECRET','@someone','<script>']) assert.ok(!body.includes(secret));
}));
test('malformed issue metadata is not used to inject logs or recovery state',()=>withFixture(async f=>{
  await reportFailure(f);
  assert.ok(!f.state.issues[0].body.includes('token-secret'));assert.equal(f.state.issues.length,1);
},[{number:1,user:{login:'github-actions[bot]'},body:'<!-- comcigan-neo:parser-health-failure -->\n<!-- parser-health-state:{"runId":"token-secret"} -->'}]));

test('workflow retains worker inputs and publishes captured summary and structured result',()=>{
  const f=fixture();
  try {
    const workflow=fs.readFileSync(path.join(__dirname,'../.github/workflows/parser-health.yml'),'utf8').replace(/\r\n/g,'\n');
    assert.match(workflow,/PARSER_HEALTH_RESULT: \$\{\{ runner.temp \}\}\/parser-health-result.json/);
    assert.match(workflow,/source:\n/);assert.match(workflow,/scheduled_time:\n/);
    assert.match(workflow,/concurrency:\n  group: parser-health\n  cancel-in-progress: false/);
    const live=workflow.split('      - name: Check live server responses\n')[1].split('\n      - name:')[0];
    const publish=workflow.split('      - name: Publish live parser check summary\n')[1].split('\n      - name:')[0];
    const script=block=>block.split('        run: |\n')[1].replace(/^          /gm,'');
    const directory=path.dirname(f.summaryPath);
    fs.writeFileSync(path.join(directory,'gradlew'),'#!/usr/bin/env bash\nprintf "parser FAIL summary\\n" >> "$GITHUB_STEP_SUMMARY"\nprintf "{}" > "$PARSER_HEALTH_RESULT"\nexit 1\n',{mode:0o755});
    const bash=process.platform==='win32'?path.join(process.env.ProgramFiles||'C:/Program Files','Git/bin/bash.exe'):'bash';
    const options={cwd:directory,encoding:'utf8',env:{...process.env,GITHUB_STEP_SUMMARY:'runner-summary.md',
      PARSER_SUMMARY:'captured-summary.md',PARSER_HEALTH_RESULT:'captured-result.json'}};
    const execution=spawnSync(bash,['--noprofile','--norc','-e','-o','pipefail','-c',script(live)],options);
    assert.ifError(execution.error);assert.equal(execution.status,1,execution.stderr);
    const published=spawnSync(bash,['--noprofile','--norc','-e','-o','pipefail','-c',script(publish)],options);
    assert.ifError(published.error);assert.equal(published.status,0,published.stderr);
    assert.equal(fs.readFileSync(path.join(directory,'runner-summary.md'),'utf8'),'parser FAIL summary\n');
    assert.equal(fs.readFileSync(path.join(directory,'captured-result.json'),'utf8'),'{}');
    assert.match(workflow,/!cancelled\(\).*steps.checkout.outcome == 'success'/s);
    assert.ok(!workflow.includes('failure() && github.repository'));
  } finally { f.cleanup(); }
});
