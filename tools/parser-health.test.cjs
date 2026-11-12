'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const context = {repo: {owner: 'mchany', repo: 'comcigan-neo'}, eventName: 'workflow_dispatch',
  ref: 'refs/heads/main', sha: 'a'.repeat(40), runId: 123, serverUrl: 'https://github.com',
  payload: {inputs: {source: 'scheduler', scheduled_time: '2026-11-12T03:00:00Z'}}};
const summary = '## Parser Health\n\n| Check | Result | Detail |\n|---|---|---|\n| Timetable week=0 | FAIL | 서비스 프로토콜 또는 응답 형식 변경 |\n';

/**
 * 역할: 외부 통신 없이 이슈 저장 상태와 페이지 순회를 재현한다.
 * 입력: 초기 열린 이슈 목록.
 * 출력: 실제 알림 함수를 실행할 인자와 저장 결과.
 */
function fixture(issues = []) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'parser-health-'));
  const summaryPath = path.join(directory, 'summary.md');
  fs.writeFileSync(summaryPath, summary);
  const state = {issues, writes: 0, pages: 0};
  const github = {
    rest: {issues: {
      listForRepo: Symbol('listForRepo'),
      async create(params) {
        state.writes++;
        const issue = {...params, number: state.issues.length + 1, user: {login: 'github-actions[bot]'}};
        state.issues.push(issue);
        return {data: issue};
      },
      async update(params) {
        state.writes++;
        Object.assign(state.issues.find(issue => issue.number === params.issue_number), params);
      },
    }},
    async paginate(method, params) {
      assert.equal(method, github.rest.issues.listForRepo);
      assert.equal(params.state, 'open');
      // 실제 REST 페이지 크기를 넘는 데이터도 모두 반환한다.
      state.pages = Math.ceil(state.issues.length / params.per_page);
      return state.issues;
    },
  };
  return {state, github, context, summaryPath,
    steps: {regression: {outcome: 'success'}, live: {outcome: 'failure'}},
    cleanup() { fs.rmSync(directory, {recursive: true, force: true}); }};
}

// 역할: 실패 요약을 자동 이슈로 전달한다. 입력: FAIL 요약. 출력: 요약·실행 정보가 포함된 이슈 한 개.
test('failed live summary creates an issue with run metadata', async () => {
  const f = fixture();
  try {
    await require('./parser-health.cjs').reportFailure(f);
    assert.equal(f.state.issues.length, 1);
    const issue = f.state.issues[0];
    assert.match(issue.body, /Timetable week=0 \| FAIL/);
    assert.match(issue.body, /서비스 프로토콜 또는 응답 형식 변경/);
    assert.match(issue.body, /actions\/runs\/123\/attempts\/1/);
    assert.match(issue.body, /2026-11-12T03:00:00Z/);
    assert.match(issue.body, /live.*failure/);
    assert.match(issue.body, /a{40}/);
  } finally { f.cleanup(); }
});

// 역할: 반복 실패와 재실행의 중복 발행을 방지한다. 입력: 같은 실패 및 다른 실행. 출력: 기존 이슈의 최신 본문 갱신.
test('repeated failures update the open bot issue across all pages', async () => {
  const f = fixture(Array.from({length: 105}, (_, i) => ({number: i + 1, body: 'unrelated'})));
  try {
    const reporter = require('./parser-health.cjs');
    await reporter.reportFailure(f);
    await reporter.reportFailure(f);
    await reporter.reportFailure({...f, context: {...context, runId: 124}, attempt: '2'});
    assert.equal(f.state.issues.length, 106);
    assert.equal(f.state.writes, 2);
    assert.equal(f.state.pages, 2);
    assert.match(f.state.issues[105].body, /runs\/124\/attempts\/2/);
  } finally { f.cleanup(); }
});

// 역할: 사용자 이슈·PR을 자동 이슈로 오인하지 않는다. 입력: 같은 마커를 가진 수동 이슈와 PR. 출력: 별도 자동 이슈 생성.
test('manual issues and pull requests are not overwritten', async () => {
  const f = fixture();
  try {
    const reporter = require('./parser-health.cjs');
    await reporter.reportFailure(f);
    const original = {...f.state.issues[0], user: {login: 'human'}};
    f.state.issues[0] = original;
    f.state.issues.push({...original, number: 2, user: {login: 'github-actions[bot]'}, pull_request: {}});
    await reporter.reportFailure(f);
    assert.equal(f.state.issues.length, 3);
  } finally { f.cleanup(); }
});

// 역할: 요약 생성 전 실패를 보고한다. 입력: 누락·빈 요약과 회귀 테스트 실패. 출력: 실패 단계 및 요약 부재 안내.
test('missing or empty summary reports the failed earlier step', async () => {
  for (const missing of [true, false]) {
    const f = fixture();
    try {
      if (missing) fs.unlinkSync(f.summaryPath); else fs.writeFileSync(f.summaryPath, '');
      await require('./parser-health.cjs').reportFailure({...f, steps: {regression: {outcome: 'failure'}, live: {outcome: 'skipped'}}});
      assert.match(f.state.issues[0].body, /요약.*생성되지/);
      assert.match(f.state.issues[0].body, /regression.*failure/);
    } finally { f.cleanup(); }
  }
});

// 역할: 알림 범위를 main의 실패 실행으로 제한한다. 입력: PR·다른 브랜치·다른 저장소·성공. 출력: API 쓰기 없음.
test('PRs, non-main branches, forks and successful checks do not create issues', async () => {
  for (const patch of [{eventName: 'pull_request'}, {ref: 'refs/heads/test'}, {repo: {owner: 'fork', repo: 'comcigan-neo'}}, {eventName: 'push'}]) {
    const f = fixture();
    try {
      await require('./parser-health.cjs').reportFailure({...f, context: {...context, ...patch}});
      assert.equal(f.state.writes, 0);
    } finally { f.cleanup(); }
  }
  const f = fixture();
  try {
    await require('./parser-health.cjs').reportFailure({...f, steps: {live: {outcome: 'success'}}});
    assert.equal(f.state.writes, 0);
  } finally { f.cleanup(); }
});

// 역할: API 실패를 숨기거나 중복 생성하지 않는다. 입력: 조회·쓰기 권한 오류. 출력: 오류 전달 및 우회 발행 없음.
test('API errors propagate without a fallback create', async () => {
  for (const operation of ['list', 'create']) {
    const f = fixture();
    try {
      if (operation === 'list') f.github.paginate = async () => { throw new Error('HTTP 403'); };
      else f.github.rest.issues.create = async () => { throw new Error('HTTP 403'); };
      await assert.rejects(require('./parser-health.cjs').reportFailure(f), /HTTP 403/);
      assert.equal(f.state.issues.length, 0);
    } finally { f.cleanup(); }
  }
});

// 역할: 이슈 수정 권한 오류의 우회 발행을 막는다. 입력: 기존 자동 이슈와 HTTP 403. 출력: 기존 이슈 보존 및 오류 전달.
test('update errors do not create another issue', async () => {
  const f = fixture();
  try {
    const reporter = require('./parser-health.cjs');
    await reporter.reportFailure(f);
    f.github.rest.issues.update = async () => { throw new Error('HTTP 403'); };
    await assert.rejects(reporter.reportFailure({...f, attempt: '2'}), /HTTP 403/);
    assert.equal(f.state.issues.length, 1);
    assert.equal(f.state.writes, 1);
  } finally { f.cleanup(); }
});

// 역할: 큰 요약과 조작된 메타데이터를 안전하게 담는다. 입력: 긴 요약 및 코드·멘션 문자열. 출력: API 본문 길이 제한 준수와 메타데이터 이스케이프.
test('large summaries are bounded and input metadata cannot break the code fence', async () => {
  const f = fixture();
  try {
    fs.writeFileSync(f.summaryPath, summary + '가'.repeat(70000));
    await require('./parser-health.cjs').reportFailure({...f,
      context: {...context, payload: {inputs: {source: '```\n@someone <script>', scheduled_time: 'x'.repeat(70000)}}}});
    const body = f.state.issues[0].body;
    assert.ok(body.length < 65536);
    assert.ok(!body.includes('@someone'));
    assert.ok(!body.includes('<script>'));
    assert.equal(body.match(/```/g).length, 2);
  } finally { f.cleanup(); }
});

// 역할: runner의 기본 GITHUB_STEP_SUMMARY가 주입돼도 실제 workflow shell이 실패 요약을 보존하는지 검사한다. 입력: 실패하는 Gradle 대역. 출력: 실패 종료·임시 요약·게시 요약 일치.
test('workflow shell preserves and publishes summary despite runner environment injection', () => {
  const f = fixture();
  try {
    const workflow = fs.readFileSync(path.join(__dirname, '../.github/workflows/parser-health.yml'), 'utf8').replace(/\r\n/g, '\n');
    const liveStep = workflow.split('      - name: Check live server responses\n')[1].split('\n      - name:')[0];
    const publishStep = workflow.split('      - name: Publish live parser check summary\n')[1].split('\n      - name:')[0];
    const liveScript = liveStep.split('        run: |\n')[1].replace(/^          /gm, '');
    const publishScript = publishStep.split('        run: |\n')[1].replace(/^          /gm, '');
    const directory = path.dirname(f.summaryPath);
    fs.writeFileSync(path.join(directory, 'gradlew'), '#!/usr/bin/env bash\nprintf "parser FAIL summary\\n" >> "$GITHUB_STEP_SUMMARY"\nexit 1\n', {mode: 0o755});
    const bash = process.platform === 'win32' ? path.join(process.env.ProgramFiles || 'C:/Program Files', 'Git/bin/bash.exe') : 'bash';
    const options = {cwd: directory, encoding: 'utf8', env: {...process.env,
      GITHUB_STEP_SUMMARY: 'runner-summary.md', PARSER_SUMMARY: 'captured-summary.md'}};
    const live = spawnSync(bash, ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', liveScript], options);
    assert.ifError(live.error);
    assert.equal(live.status, 1, live.stderr);
    assert.equal(fs.readFileSync(path.join(directory, 'captured-summary.md'), 'utf8'), 'parser FAIL summary\n');
    const publish = spawnSync(bash, ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', publishScript], options);
    assert.ifError(publish.error);
    assert.equal(publish.status, 0, publish.stderr);
    assert.equal(fs.readFileSync(path.join(directory, 'runner-summary.md'), 'utf8'), 'parser FAIL summary\n');
  } finally { f.cleanup(); }
});
