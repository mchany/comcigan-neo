'use strict';
const fs = require('node:fs');

const MARKER = '<!-- comcigan-neo:parser-health-failure -->';
const TITLE = '[Parser Health] main 파서 상태 검사 실패';

/**
 * 역할: main 상태 검사 실패를 요약 기반의 자동 이슈로 발행하거나 기존 열린 이슈를 갱신한다.
 * 입력: Actions의 GitHub 클라이언트·실행 문맥·단계 결과·요약 파일 경로·재실행 번호.
 * 출력: 신규 또는 갱신한 이슈 번호이며 대상 외 실행은 null, 파일·API 오류는 호출자에게 전달한다.
 */
async function reportFailure({github, context, steps, summaryPath, attempt = '1'}) {
  if (context.repo.owner !== 'mchany' || context.repo.repo !== 'comcigan-neo' ||
      context.eventName !== 'workflow_dispatch' || context.ref !== 'refs/heads/main' ||
      !Object.values(steps).some(step => step.outcome === 'failure')) return null;

  let summary = '';
  try {
    summary = fs.readFileSync(summaryPath, 'utf8').trim();
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const repo = context.repo;
  const runUrl = `${context.serverUrl || 'https://github.com'}/${repo.owner}/${repo.repo}/actions/runs/${context.runId}/attempts/${attempt}`;
  // 외부 입력은 코드로 실행하지 않으며 메타데이터의 표·멘션 삽입을 막는다.
  const inputs = context.payload.inputs || {};
  const metadata = JSON.stringify({
    sha: context.sha, source: String(inputs.source || 'manual').slice(0, 100),
    scheduled_time: inputs.scheduled_time ? String(inputs.scheduled_time).slice(0, 200) : null,
    steps: Object.fromEntries(Object.entries(steps).map(([name, step]) => [name, step.outcome])),
  }, null, 2).replace(/</g, '\\u003c').replace(/`/g, '\\u0060').replace(/@/g, '\\u0040');
  const body = [MARKER, '## Parser Health 실패', '',
    'main의 Parser Health 검사에 실패했습니다. 아래 내용은 가장 최근에 보고된 실패입니다.', '',
    `[Live parser check 실행 및 요약](${runUrl})`, '',
    '### 실행 정보', '', '```json', metadata, '```', '',
    '### Live parser check summary', '',
    summary.slice(0, 40000) || '검사 요약이 생성되지 않았습니다. 위 단계 결과와 실행 로그를 확인하세요.', '',
    '동일한 열린 자동 이슈가 있으면 최신 실패로 본문을 갱신합니다. 해결 후 닫으면 다음 실패 때 새 이슈를 생성합니다.',
  ].join('\n');

  // Search API의 색인 지연 없이 모든 열린 이슈를 조회한다. 워크플로 concurrency가 쓰기를 직렬화한다.
  const issues = await github.paginate(github.rest.issues.listForRepo, {...repo, state: 'open', per_page: 100});
  const existing = issues.find(issue => !issue.pull_request &&
    issue.user?.login === 'github-actions[bot]' && issue.body?.startsWith(MARKER));
  if (existing) {
    if (existing.body !== body) {
      await github.rest.issues.update({...repo, issue_number: existing.number, title: TITLE, body});
    }
    return existing.number;
  }
  const {data} = await github.rest.issues.create({...repo, title: TITLE, body});
  return data.number;
}

module.exports = {reportFailure};
