'use strict';
const fs = require('node:fs');
const IDS = ['exact_search','empty_search','timetable_0','timetable_1'];
const TYPES = ['NETWORK_ERROR','SERVER_ERROR','PROTOCOL_ERROR','PARSE_ERROR','VALIDATION_ERROR','UNKNOWN_ERROR','ENVIRONMENT_ERROR'];
const VALIDATION_CODES = ['VALIDATION_FAILED','PERIOD_CHANGED','DATE_BOUNDARY_UNSTABLE','EXPECTED_PERIOD_MISSING',
  'PERIOD_MISMATCH','INVALID_START','SELECTION_MISMATCH','INVALID_CLASS_COUNTS','INVALID_SLOT','BLANK_SUBJECT',
  'EXACT_MATCH_COUNT','INVALID_SCHOOL_CODE','NONEMPTY_SEARCH_RESULT'];
const ENV_CODES = ['RESULT_MISSING','RESULT_INVALID','RESULT_UNREADABLE','LIVE_RESULT_MISMATCH',
  'CHECKOUT_FAILED','JAVA_FAILED','GRADLE_FAILED','REPORTING_TESTS_FAILED','REGRESSION_FAILED','LIVE_NOT_RUN','SUMMARY_FAILED'];
const CODES = ['SEARCH_OK','EMPTY_SEARCH_OK','TIMETABLE_OK','CATALOG_ABSENT','AMBIGUOUS_UNPUBLISHED','NO_SLOTS','NO_LESSONS',
  'HTTP_ERROR','NETWORK_ERROR','PROTOCOL_CHANGED','RESPONSE_PARSE_FAILED','UNKNOWN_FAILURE','DEPENDENCY_FAILED',
  ...VALIDATION_CODES,...ENV_CODES];
function ensure(condition) { if(!condition) throw new Error('Invalid structured health result'); }
function isDate(v) {
  return typeof v==='string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v)) &&
    new Date(v).toISOString().slice(0,10)===v;
}
function isInstant(v) {
  return typeof v==='string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v));
}
function validOutcome(s,id) {
  ensure(s && typeof s==='object' && CODES.includes(s.code));
  const timetable=id.startsWith('timetable_');
  if(s.status==='HEALTHY') {
    ensure(s.errorType===null && s.httpStatus===null);
    ensure(s.code===(id==='exact_search'?'SEARCH_OK':id==='empty_search'?'EMPTY_SEARCH_OK':'TIMETABLE_OK'));
  } else if(s.status==='UNPUBLISHED') {
    ensure(timetable && s.code==='CATALOG_ABSENT' && s.errorType===null && s.httpStatus===null);
  } else if(s.status==='INDETERMINATE') {
    ensure(timetable && s.httpStatus===null);
    ensure((s.code==='AMBIGUOUS_UNPUBLISHED' && s.errorType==='UNKNOWN_ERROR') ||
      (['NO_SLOTS','NO_LESSONS'].includes(s.code) && s.errorType==='VALIDATION_ERROR'));
  } else if(s.status==='FAIL') {
    ensure(TYPES.includes(s.errorType) && s.errorType!=='ENVIRONMENT_ERROR');
    const pairs={NETWORK_ERROR:['NETWORK_ERROR'],SERVER_ERROR:['HTTP_ERROR'],PROTOCOL_ERROR:['PROTOCOL_CHANGED'],
      PARSE_ERROR:['RESPONSE_PARSE_FAILED'],VALIDATION_ERROR:VALIDATION_CODES,UNKNOWN_ERROR:['UNKNOWN_FAILURE']};
    ensure(pairs[s.errorType].includes(s.code));
    if(s.errorType==='SERVER_ERROR') ensure(Number.isInteger(s.httpStatus) && s.httpStatus>=100 && s.httpStatus<=599 &&
      (s.httpStatus<200 || s.httpStatus>=300));
    else ensure(s.httpStatus===null);
  } else if(s.status==='SKIPPED') {
    ensure(timetable && s.code==='DEPENDENCY_FAILED' && s.errorType===null && s.httpStatus===null);
  } else ensure(false);
}

/** Fail closed on partial/contradictory results and copy only the documented, bounded fields. */
function validateResult(r) {
  ensure(r && r.schemaVersion===1 && isInstant(r.checkedAt) && r.timezone==='Asia/Seoul');
  ensure(r.target && typeof r.target.school==='string' && r.target.school.length>=1 && r.target.school.length<=200 &&
    r.target.grade===1 && r.target.classNumber===1);
  ensure(Array.isArray(r.steps) && r.steps.length===4);
  const steps=r.steps.map((s,i)=>{
    ensure(s.id===IDS[i] && s.week===(i<2?null:i-2) && isDate(s.referenceDate));
    validOutcome(s,s.id);
    for(const v of [s.expectedStart,s.actualStart]) ensure(v===null || isDate(v));
    ensure(Array.isArray(s.attempts) && s.attempts.length<3);
    const attempts=s.attempts.map((a,n)=>{
      ensure(a.number===n+1 && (a.dateRefetches===0 || a.dateRefetches===1));
      validOutcome(a,s.id);ensure(a.status!=='SKIPPED');
      return {number:a.number,status:a.status,errorType:a.errorType,code:a.code,dateRefetches:a.dateRefetches,httpStatus:a.httpStatus};
    });
    if(s.status==='SKIPPED') ensure(attempts.length===0 && r.steps[0].status!=='HEALTHY');
    else {
      ensure(attempts.length>=1);
      const last=attempts.at(-1);
      ensure(['status','errorType','code','httpStatus'].every(k=>last[k]===s[k]));
      if(attempts.length===2) {
        const first=attempts[0];
        ensure(first.status==='FAIL' && (first.errorType==='NETWORK_ERROR' ||
          (first.errorType==='SERVER_ERROR' && [500,502,503,504].includes(first.httpStatus))));
      }
    }
    const hasSnapshot=s.status==='HEALTHY' && i>=2 || ['NO_SLOTS','NO_LESSONS'].includes(s.code);
    if(hasSnapshot) {
      ensure(s.expectedStart!==null && s.expectedStart===s.actualStart);
      ensure(new Date(s.actualStart).getUTCDay()===1);
      ensure(Number.isInteger(s.slotCount) && Number.isInteger(s.lessonCount) &&
        s.slotCount>=0 && s.slotCount<=448 && s.lessonCount>=0 && s.lessonCount<=s.slotCount);
      if(s.status==='HEALTHY') ensure(s.lessonCount>0);
      if(s.code==='NO_SLOTS') ensure(s.slotCount===0 && s.lessonCount===0);
      if(s.code==='NO_LESSONS') ensure(s.slotCount>0 && s.lessonCount===0);
    } else ensure(s.slotCount===null && s.lessonCount===null);
    if(i<2 || s.status==='UNPUBLISHED' || s.status==='SKIPPED' || s.code==='AMBIGUOUS_UNPUBLISHED') {
      ensure(s.expectedStart===null && s.actualStart===null);
    }
    if(s.code==='PERIOD_MISMATCH') ensure(s.expectedStart!==null && s.actualStart!==null && s.expectedStart!==s.actualStart);
    if(s.code==='EXPECTED_PERIOD_MISSING') ensure(s.expectedStart===null && s.actualStart!==null);
    return {id:s.id,status:s.status,errorType:s.errorType,code:s.code,referenceDate:s.referenceDate,week:s.week,
      expectedStart:s.expectedStart,actualStart:s.actualStart,httpStatus:s.httpStatus,slotCount:s.slotCount,lessonCount:s.lessonCount,attempts};
  });
  const success=steps.every(s=>['HEALTHY','UNPUBLISHED'].includes(s.status));
  const overall=steps.some(s=>s.status==='FAIL')?'FAIL':!success?'INDETERMINATE':
    steps.some(s=>s.status==='UNPUBLISHED')?'UNPUBLISHED':'HEALTHY';
  ensure(r.overall===overall);
  return {schemaVersion:1,checkedAt:r.checkedAt,timezone:r.timezone,
    target:{school:r.target.school,grade:1,classNumber:1},overall,steps};
}
function environmentResult(code) {
  return {checkedAt:null,timezone:'Asia/Seoul',target:null,overall:'INDETERMINATE',
    steps:[{id:'pipeline',status:'FAIL',errorType:'ENVIRONMENT_ERROR',code,referenceDate:null,week:null,attempts:[]}]};
}
function readResult(resultPath,steps) {
  for(const [id,code] of [['checkout','CHECKOUT_FAILED'],['java','JAVA_FAILED'],['gradle','GRADLE_FAILED'],
    ['reporting-tests','REPORTING_TESTS_FAILED'],['regression','REGRESSION_FAILED'],['summary','SUMMARY_FAILED']]) {
    if(steps[id]?.outcome!=='success') return environmentResult(code);
  }
  if(!['success','failure'].includes(steps.live?.outcome)) return environmentResult('LIVE_NOT_RUN');
  let report;
  try {
    if(!resultPath || !fs.existsSync(resultPath)) return environmentResult('RESULT_MISSING');
    const stat=fs.statSync(resultPath);ensure(stat.isFile() && stat.size>0 && stat.size<=65536);
    report=validateResult(JSON.parse(fs.readFileSync(resultPath,'utf8')));
  } catch(error) {
    return environmentResult(error.code==='EACCES' || error.code==='EPERM'?'RESULT_UNREADABLE':'RESULT_INVALID');
  }
  const success=['HEALTHY','UNPUBLISHED'].includes(report.overall);
  if((steps.live.outcome==='success')!==success) return environmentResult('LIVE_RESULT_MISMATCH');
  return report;
}
function renderResult(report) {
  const lines=['## 최신 검사 결과','','Overall: '+report.overall,
    '검사 시각: '+(report.checkedAt||'실서버 검사 시각 미확인')+' / Asia/Seoul','',
    '| 단계 | 결과 | 오류 유형 | 기준 날짜 / 주차 | 진단 | 시도 결과 (자정 재조회) |','|---|---|---|---|---|---|'];
  for(const s of report.steps) {
    const detail=[s.code,s.httpStatus?'HTTP '+s.httpStatus:null,s.expectedStart?'expected='+s.expectedStart:null,
      s.actualStart?'actual='+s.actualStart:null].filter(Boolean).join(' ');
    const attempts=s.attempts.map(a=>a.number+':'+a.status+'/'+a.code+(a.httpStatus?'/HTTP'+a.httpStatus:'')+'('+a.dateRefetches+')').join(' → ');
    lines.push('| '+s.id+' | '+s.status+' | '+(s.errorType||'-')+' | '+(s.referenceDate||'-')+' / '+(s.week??'-')+' | '+detail+' | '+attempts+' |');
  }
  lines.push('','시도 횟수는 Health 단계 기준이며 개별 HTTP 요청 수와 다릅니다.');
  return lines.join('\n');
}
module.exports={IDS,TYPES,CODES,ENV_CODES,isInstant,readResult,validateResult,renderResult};
