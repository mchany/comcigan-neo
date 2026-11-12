'use strict';
const {validateMetadata} = require('./release.cjs');
const REPO = 'mchany/comcigan-neo';
const SHA = /^[0-9a-f]{40}$/;

/** 역할: 신뢰하는 원본 릴리즈와 성공한 서명 작업을 확인한다. 입력: Octokit·수동 실행 context·원본 run ID. 출력: 원래 SHA·실행 번호·기록 URL. */
async function getRecoverySource({github, context, core, runId}) {
  if (`${context?.repo?.owner}/${context?.repo?.repo}` !== REPO || context.ref !== 'refs/heads/main' || context.eventName !== 'workflow_dispatch') throw new Error('복구는 공식 저장소 main의 수동 실행만 허용합니다.');
  if (typeof runId !== 'string' || !/^[1-9][0-9]*$/.test(runId) || !Number.isSafeInteger(Number(runId))) throw new Error('유효한 원본 실행 번호가 필요합니다.');
  const args = {...context.repo, run_id: Number(runId), request: {timeout: 60000}};
  const {data: run} = await github.request('GET /repos/{owner}/{repo}/actions/runs/{run_id}', args);
  const runUrl = `https://github.com/${REPO}/actions/runs/${runId}`;
  if (run?.id !== Number(runId) || run.repository?.full_name !== REPO || run.head_repository?.full_name !== REPO || run.status !== 'completed' || !['failure', 'success'].includes(run.conclusion) || !['push', 'workflow_dispatch'].includes(run.event) || run.head_branch !== 'main' || run.path !== '.github/workflows/release.yml' || !SHA.test(run.head_sha) || run.html_url !== runUrl) throw new Error('원본 실행이 신뢰할 수 있는 릴리즈가 아닙니다.');
  for (let page = 1; page <= 10; page++) {
    const {data} = await github.request('GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs', {...args, filter: 'all', per_page: 100, page});
    if (!Array.isArray(data?.jobs)) throw new Error('원본 서명 작업 응답이 잘못되었습니다.');
    if (data.jobs.some(job => job.name === 'sign' && job.status === 'completed' && job.conclusion === 'success')) {
      const result = {run_id: String(runId), sha: run.head_sha, run_url: runUrl};
      for (const [key, value] of Object.entries(result)) core.setOutput(key, value);
      return result;
    }
    if (data.jobs.length < 100) throw new Error('원본 실행에 성공한 서명 작업이 없습니다.');
  }
  throw new Error('원본 작업 조회 한도를 초과했습니다.');
}

/** 역할: 복구에서 태그 생성·이동 없이 원래 소스를 재사용하도록 검사한다. 입력: API·버전·원본 SHA. 출력: 정상 또는 복구 오류. */
async function verifyRecoveryTag({github, context, metadata, sha}) {
  validateMetadata(metadata);
  if (`${context?.repo?.owner}/${context?.repo?.repo}` !== REPO) throw new Error('복구 대상 저장소가 다릅니다.');
  if (!SHA.test(sha)) throw new Error('원본 태그 SHA가 잘못되었습니다.');
  let tag;
  try {
    tag = (await github.request('GET /repos/{owner}/{repo}/git/ref/{ref}', {...context.repo, ref: `tags/v${metadata.version}`, request: {timeout: 60000}})).data;
  } catch {
    throw new Error('복구 대상의 기존 태그를 찾을 수 없습니다.');
  }
  if (tag?.object?.type !== 'commit' || tag.object.sha !== sha) throw new Error('기존 태그가 원본 소스와 다릅니다. 태그를 변경하지 않습니다.');
}

module.exports = {getRecoverySource, verifyRecoveryTag};
