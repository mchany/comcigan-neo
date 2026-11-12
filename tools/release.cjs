'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');

const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const SHA = /^[0-9a-f]{40}$/;
const REPO = 'mchany/comcigan-neo';
const BRANCH = 'codex/readme-release-sync';
const MARKERS = ['<!-- release:start -->', '<!-- release:end -->'];

/** 역할: 정식 버전을 검증한다. 입력: 버전 문자열. 출력: 숫자 세 쌍 또는 오류. */
function versionParts(value) {
  if (typeof value !== 'string' || !VERSION.test(value)) throw new Error('정식 버전 X.Y.Z가 필요합니다.');
  const parts = value.split('.').map(Number);
  if (!parts.every(Number.isSafeInteger)) throw new Error('버전 숫자 범위를 초과했습니다.');
  return parts;
}
/** 역할: 정식 버전의 선후를 비교한다. 입력: 두 버전 문자열. 출력: 비교 정수. */
function compareVersions(left, right) {
  const a = versionParts(left), b = versionParts(right);
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return 0;
}
/** 역할: 릴리즈 커밋 첫 줄을 검사한다. 입력: 커밋 메시지. 출력: 요청 버전 또는 null. */
function triggerVersion(message) {
  const title = (message || '').split(/\r?\n/)[0];
  if (!title.startsWith('chore: release ')) return null;
  const match = /^chore: release (\S+?)(?: \(#[0-9]+\))?$/.exec(title);
  if (!match) throw new Error('릴리즈 커밋 형식이 잘못되었습니다.');
  versionParts(match[1]);
  return match[1];
}
/** 역할: Gradle에서 내보낸 릴리즈 정보를 검사한다. 입력: JSON 객체. 출력: 검증된 정보. */
function validateMetadata(data) {
  versionParts(data?.version);
  if (!Number.isInteger(data.version_code) || data.version_code < 1 || data.version_code > 2100000000) throw new Error('유효한 version_code가 필요합니다.');
  if (!Array.isArray(data.changes) || data.changes.length === 0) throw new Error('변경 사항이 필요합니다.');
  for (const value of [data.summary, ...data.changes]) {
    if (typeof value !== 'string' || !value.trim() || /[\r\n\0]/.test(value)) throw new Error('설명은 비어 있지 않은 한 줄이어야 합니다.');
  }
  return data;
}
/** 역할: 버전과 Android 코드의 역행을 차단한다. 입력: 새 정보와 이전 기준. 출력: 정상 또는 오류. */
function validateProgress(data, version, code) {
  if (!Number.isInteger(code) || code < 1 || compareVersions(data.version, version) <= 0 || data.version_code <= code) throw new Error('버전과 versionCode는 기존 배포보다 커야 합니다.');
}
/** 역할: 공식 저장소와 고정 SHA를 검사한다. 입력: Actions context. 출력: API 저장소 식별자. */
function identity(context) {
  const repo = context.repo;
  if (`${repo?.owner}/${repo?.repo}` !== REPO || !SHA.test(context.sha)) throw new Error('공식 저장소와 정확한 소스 SHA가 필요합니다.');
  return repo;
}
/** 역할: 타임아웃과 제한된 오류 출력을 적용해 API를 호출한다. 입력: Octokit·context·라우트·값. 출력: 응답 또는 허용된 404의 null. */
async function call(github, context, route, args = {}, missing = false) {
  try {
    return (await github.request(route, {...identity(context), ...args, request: {timeout: 60000}})).data;
  } catch (error) {
    if (missing && error.status === 404) return null;
    throw new Error(`GitHub API 요청 실패: ${Number.isInteger(error.status) ? error.status : 'network'}`);
  }
}
/** 역할: 커밋 메시지를 읽는다. 입력: API와 Actions context. 출력: 고정 SHA의 메시지. */
async function commitMessage(github, context) {
  if (context.payload?.head_commit?.message) return context.payload.head_commit.message;
  const commit = await call(github, context, 'GET /repos/{owner}/{repo}/commits/{ref}', {ref: context.sha});
  return commit.commit.message;
}
/** 역할: 배포 요청만 후속 빌드로 전달한다. 입력: Actions 도구. 출력: trigger·requested·sha. */
async function gate({github, context, core}) {
  identity(context);
  if (context.ref !== 'refs/heads/main') throw new Error('main에서만 릴리즈할 수 있습니다.');
  const requested = triggerVersion(await commitMessage(github, context));
  const before = context.payload?.before;
  if (context.eventName === 'push' && SHA.test(before || '') && before !== '0'.repeat(40)) {
    const messages = execFileSync('git', ['log', '--format=%s', `${before}..${context.sha}`], {encoding: 'utf8', timeout: 30000, maxBuffer: 1024 * 1024}).trim().split(/\r?\n/);
    const requests = messages.map(triggerVersion).filter(Boolean);
    if (requests.length > 1 || (requests.length && !requested)) throw new Error('릴리즈 요청은 push의 마지막 커밋 한 개여야 합니다.');
  }
  const triggered = Boolean(requested) || context.eventName === 'workflow_dispatch';
  core.setOutput('trigger', String(triggered));
  core.setOutput('requested', requested || '');
  core.setOutput('sha', context.sha);
  return triggered;
}
/** 역할: 경량·주석 태그를 커밋으로 해석한다. 입력: 태그. 출력: 커밋 SHA 또는 null. */
async function tagSha(github, context, tag) {
  const ref = await call(github, context, 'GET /repos/{owner}/{repo}/git/ref/{ref}', {ref: `tags/${tag}`}, true);
  if (!ref) return null;
  let object = ref.object;
  for (let i = 0; i < 5; i++) {
    if (object.type === 'commit' && SHA.test(object.sha)) return object.sha;
    if (object.type !== 'tag') break;
    object = (await call(github, context, 'GET /repos/{owner}/{repo}/git/tags/{tag_sha}', {tag_sha: object.sha})).object;
  }
  throw new Error('태그가 정상 커밋을 가리키지 않습니다.');
}
/** 역할: 중복 이름을 거부하고 인증된 API로 첨부를 읽는다. 입력: 릴리즈와 이름. 출력: 바이트 또는 null. */
async function asset(github, context, release, name) {
  const matches = release.assets.filter(item => item.name === name);
  if (matches.length > 1) throw new Error('릴리즈 첨부 이름이 중복되었습니다.');
  if (!matches.length) return null;
  // Octokit의 fetch는 다른 origin으로 리다이렉트될 때 Authorization을 전달하지 않는다.
  const bytes = await call(github, context, 'GET /repos/{owner}/{repo}/releases/assets/{asset_id}', {asset_id: matches[0].id, headers: {accept: 'application/octet-stream'}});
  return Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
}
/** 역할: 태그로 릴리즈를 조회한다. 입력: 태그. 출력: 릴리즈 또는 null. */
async function getRelease(github, context, tag) {
  return call(github, context, 'GET /repos/{owner}/{repo}/releases/tags/{tag}', {tag}, true);
}
/** 역할: 공개 메타데이터의 코드와 태그를 확인한다. 입력: 릴리즈와 첨부. 출력: Android 코드 또는 오류. */
function previousCode(release, raw) {
  if (raw === null) {
    if (release.tag_name === 'v1.1.0') return 16;
    throw new Error('기존 릴리즈의 versionCode 메타데이터가 없습니다.');
  }
  const meta = JSON.parse(raw.toString('utf8'));
  if (`v${meta.version}` !== release.tag_name || !Number.isInteger(meta.version_code) || meta.version_code < 1 || meta.version_code > 2100000000) throw new Error('기존 릴리즈 메타데이터가 일치하지 않습니다.');
  return meta.version_code;
}
/** 역할: 게시 직전 최신 버전과 비교한다. 입력: API·새 버전. 출력: 정상 또는 오류. */
async function validateLatest(github, context, metadata) {
  const latest = await call(github, context, 'GET /repos/{owner}/{repo}/releases/latest', {}, true);
  if (latest && latest.tag_name !== `v${metadata.version}`) validateProgress(metadata, latest.tag_name.replace(/^v/, ''), previousCode(latest, await asset(github, context, latest, 'release-metadata.json')));
}
/** 역할: TOML 내보내기 결과와 배포 이력을 검증한다. 입력: Actions 도구·JSON 정보. 출력: 검증된 릴리즈 출력. */
async function prepare({github, context, core, metadata}) {
  identity(context); validateMetadata(metadata);
  const requested = triggerVersion(await commitMessage(github, context));
  if (context.eventName !== 'workflow_dispatch' && !requested) throw new Error('명시적인 릴리즈 요청이 없습니다.');
  if (requested && requested !== metadata.version) throw new Error('커밋 버전과 TOML 버전이 다릅니다.');
  const tag = `v${metadata.version}`;
  const existingSha = await tagSha(github, context, tag);
  if (existingSha && existingSha !== context.sha) throw new Error('태그가 다른 SHA를 가리킵니다.');
  let priorVersion = '1.1.0', priorCode = 16;
  for (let page = 1; page <= 100; page++) {
    const releases = await call(github, context, 'GET /repos/{owner}/{repo}/releases', {per_page: 100, page});
    for (const item of releases) {
      if (item.draft || item.prerelease || !VERSION.test(item.tag_name.replace(/^v/, '')) || !item.tag_name.startsWith('v')) continue;
      if (item.tag_name === tag && existingSha === context.sha) continue;
      const version = item.tag_name.slice(1);
      if (compareVersions(version, priorVersion) > 0) priorVersion = version;
      const raw = await asset(github, context, item, 'release-metadata.json');
      if (raw || compareVersions(version, '1.1.0') >= 0) priorCode = Math.max(priorCode, previousCode(item, raw));
    }
    if (releases.length < 100) break;
    if (page === 100) throw new Error('릴리즈 이력이 조회 한도를 초과했습니다.');
  }
  validateProgress(metadata, priorVersion, priorCode);
  for (const [key, value] of Object.entries({trigger: 'true', version: metadata.version, code: metadata.version_code, tag, name: `comcigan-neo-${metadata.version}.apk`, sha: context.sha})) core.setOutput(key, String(value));
}
/** 역할: 파일 무결성 값을 계산한다. 입력: 파일 경로. 출력: SHA-256. */
function digest(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
/** 역할: 실제 검사 근거로 설명과 산출물을 만든다. 입력: 버전·APK·근거·고정 소스. 출력: 공개 메타데이터. */
function makeNotes({metadata, apk, evidence, output, sha, repo, runUrl}) {
  validateMetadata(metadata);
  if (repo !== REPO || !SHA.test(sha)) throw new Error('공식 저장소와 소스 SHA가 필요합니다.');
  const checks = evidence?.checks;
  const certificate = (evidence?.certificate_sha256 || '').replace(/:/g, '').toLowerCase();
  if (!Array.isArray(checks) || !checks.length || !checks.every(value => typeof value === 'string' && value.trim() && !/[\r\n\0]/.test(value)) || !/^[0-9a-f]{64}$/.test(certificate)) throw new Error('실제 검사 근거와 인증서 지문이 필요합니다.');
  fs.mkdirSync(output, {recursive: true});
  const name = `comcigan-neo-${metadata.version}.apk`, target = path.join(output, name);
  if (path.resolve(apk) !== path.resolve(target)) fs.copyFileSync(apk, target);
  const meta = {version: metadata.version, version_code: metadata.version_code, source_sha: sha, repository: repo, summary: metadata.summary, apk_name: name, apk_sha256: digest(target), certificate_sha256: certificate, checks, run_url: runUrl || `https://github.com/${repo}/actions`};
  fs.writeFileSync(path.join(output, 'release-metadata.json'), `${JSON.stringify(meta, null, 2)}\n`);
  fs.writeFileSync(path.join(output, 'SHA256SUMS.txt'), `${meta.apk_sha256}  ${name}\n`);
  const notes = `## 컴시간neo ${metadata.version}\n\nAndroid 8.0(API 26) 이상을 지원하는 정식 APK입니다. ${metadata.summary}\n\n### 수정 및 개선 사항\n\n${metadata.changes.map(value => `- ${value}`).join('\n')}\n\n### 설치 및 업데이트\n\n\`${name}\`를 내려받아 설치하세요. 기존 배포 인증서와 일치하는 서명을 확인했습니다.\n\n- 앱 ID: \`io.github.mchany.comciganneo\`\n- 버전: \`${metadata.version}\` / versionCode \`${metadata.version_code}\`\n- 지원 환경: Android 8.0(API 26) 이상\n- 소스: [릴리즈 빌드 기준 커밋](https://github.com/${repo}/commit/${sha})\n\n### 검증\n\n${checks.map(value => `- ${value}`).join('\n')}\n\n[자동 검증 실행 기록](${meta.run_url})\n\n실기기 업데이트·설정 보존 검증은 이번 자동화에서 미수행했습니다.\nAPK의 SHA-256은 첨부된 \`SHA256SUMS.txt\`에서 확인할 수 있습니다.\n`;
  fs.writeFileSync(path.join(output, 'release-notes.md'), notes);
  return meta;
}
/** 역할: Draft 첨부의 원격 바이트를 검증한 뒤 공개한다. 입력: Actions 도구·버전·산출물 폴더. 출력: 공개 URL. */
async function publish({github, context, core, metadata, directory}) {
  identity(context); validateMetadata(metadata);
  const meta = JSON.parse(fs.readFileSync(path.join(directory, 'release-metadata.json'), 'utf8'));
  if (meta.source_sha !== context.sha || meta.repository !== REPO || meta.version !== metadata.version || meta.version_code !== metadata.version_code) throw new Error('산출물과 배포 입력이 다릅니다.');
  const name = `comcigan-neo-${metadata.version}.apk`;
  if (meta.apk_name !== name || digest(path.join(directory, name)) !== meta.apk_sha256 || fs.readFileSync(path.join(directory, 'SHA256SUMS.txt'), 'utf8') !== `${meta.apk_sha256}  ${name}\n`) throw new Error('APK 무결성 검증에 실패했습니다.');
  const tag = `v${metadata.version}`, target = await tagSha(github, context, tag);
  if (target && target !== context.sha) throw new Error('다른 소스의 기존 태그는 변경하지 않습니다.');
  await validateLatest(github, context, metadata);
  if (!target) await call(github, context, 'POST /repos/{owner}/{repo}/git/refs', {ref: `refs/tags/${tag}`, sha: context.sha});
  let item = await getRelease(github, context, tag);
  if (!item) item = await call(github, context, 'POST /repos/{owner}/{repo}/releases', {tag_name: tag, target_commitish: context.sha, name: `컴시간neo ${metadata.version}`, draft: true, prerelease: false});
  const names = [name, 'SHA256SUMS.txt', 'release-metadata.json'];
  for (const filename of names) {
    const bytes = fs.readFileSync(path.join(directory, filename));
    const prior = await asset(github, context, item, filename);
    if (prior !== null) {
      if (!prior.equals(bytes)) throw new Error('기존 릴리즈 첨부는 덮어쓰지 않습니다.');
    } else {
      if (!item.draft) throw new Error('기존 공개 릴리즈의 첨부가 누락되었습니다.');
      await call(github, context, 'POST https://uploads.github.com/repos/{owner}/{repo}/releases/{release_id}/assets{?name}', {release_id: item.id, name: filename, data: bytes, headers: {'content-type': 'application/octet-stream', 'content-length': bytes.length}});
    }
  }
  item = await getRelease(github, context, tag);
  for (const filename of names) {
    const remote = await asset(github, context, item, filename);
    if (!remote?.equals(fs.readFileSync(path.join(directory, filename)))) throw new Error('원격 첨부 검증에 실패했습니다.');
  }
  if (item.draft) {
    await validateLatest(github, context, metadata);
    if (await tagSha(github, context, tag) !== context.sha) throw new Error('게시 직전 태그 대상이 변경되었습니다.');
    item = await call(github, context, 'PATCH /repos/{owner}/{repo}/releases/{release_id}', {release_id: item.id, body: fs.readFileSync(path.join(directory, 'release-notes.md'), 'utf8'), draft: false, make_latest: 'true'});
  }
  core.setOutput('release_url', item.html_url);
  return item.html_url;
}
/** 역할: README 배포 안내 영역만 갱신한다. 입력: 본문과 공개 정보. 출력: 사용자 본문을 보존한 README. */
function renderReadme(text, meta) {
  if (MARKERS.some(marker => text.split(marker).length !== 2) || text.indexOf(MARKERS[0]) >= text.indexOf(MARKERS[1])) throw new Error('README 관리 영역 마커가 누락되거나 중복되었습니다.');
  versionParts(meta.version);
  if (meta.repository !== REPO || typeof meta.summary !== 'string' || !meta.summary.trim() || /[\r\n\0]/.test(meta.summary)) throw new Error('README 릴리즈 정보가 잘못되었습니다.');
  const v = meta.version, base = `https://github.com/${REPO}/releases`;
  const block = `\n현재 배포 버전: **${v} — ${meta.summary}** Android 8.0(API 26) 이상에서 설치할 수 있습니다.\n\n[![APK 다운로드 · ${v}](https://img.shields.io/badge/APK_다운로드-${v}-3DDC84?style=for-the-badge&logo=android&logoColor=white)](${base}/download/v${v}/comcigan-neo-${v}.apk)\n[![릴리즈 노트 · ${v}](https://img.shields.io/badge/릴리즈_노트-${v}-181717?style=for-the-badge&logo=github&logoColor=white)](${base}/tag/v${v})\n\n기존 배포 인증서와 동일한 인증서로 서명한 APK입니다.\nAPK의 SHA-256은 릴리즈에 첨부된 [SHA256SUMS.txt](${base}/download/v${v}/SHA256SUMS.txt)에서 확인할 수 있습니다.\n`;
  return text.slice(0, text.indexOf(MARKERS[0]) + MARKERS[0].length) + block + text.slice(text.indexOf(MARKERS[1]));
}
/** 역할: 최신 공개 릴리즈의 메타데이터를 검증해 읽는다. 입력: API·context. 출력: 릴리즈와 메타데이터. */
async function latestMetadata(github, context) {
  const latest = await call(github, context, 'GET /repos/{owner}/{repo}/releases/latest');
  const raw = await asset(github, context, latest, 'release-metadata.json');
  if (!raw) throw new Error('최신 공개 릴리즈의 메타데이터가 없습니다.');
  const metadata = JSON.parse(raw.toString('utf8'));
  if (metadata.repository !== REPO || latest.tag_name !== `v${metadata.version}`) throw new Error('최신 릴리즈 메타데이터가 다릅니다.');
  return {latest, metadata};
}
/** 역할: 오래된 README 동기화 PR을 차단한다. 입력: API 도구와 PR 본문 파일 내용. 출력: 정상 또는 오류. */
async function checkReadme({github, context, core, text = fs.readFileSync('README.md', 'utf8')}) {
  const {metadata} = await latestMetadata(github, context);
  if (text !== renderReadme(text, metadata)) throw new Error('README가 최신 공개 릴리즈와 다릅니다.');
  core?.setOutput('readme_status', 'current');
}
/** 역할: 동기화 전용 열린 PR 목록을 읽는다. 입력: API·context. 출력: PR 배열. */
async function syncPulls(github, context) {
  return call(github, context, 'GET /repos/{owner}/{repo}/pulls', {state: 'open', base: 'main', head: `${context.repo.owner}:${BRANCH}`});
}
/** 역할: 새 공개 전에 이전 README 자동 병합을 해제한다. 입력: App Octokit과 context. 출력: 해제 완료. */
async function pauseReadme({github, context}) {
  for (const pull of await syncPulls(github, context)) {
    if (pull.auto_merge) await github.graphql('mutation($id:ID!){disablePullRequestAutoMerge(input:{pullRequestId:$id}){pullRequest{id}}}', {id: pull.node_id});
  }
}
/** 역할: 최신 main 본문을 보존하며 README PR을 갱신한다. 입력: App Octokit·context·core. 출력: 실제 병합 또는 대기 상태. */
async function readme({github, context, core}) {
  const {latest, metadata} = await latestMetadata(github, context);
  for (let attempt = 0; attempt < 3; attempt++) {
    const main = (await call(github, context, 'GET /repos/{owner}/{repo}/git/ref/{ref}', {ref: 'heads/main'})).object.sha;
    const original = await call(github, context, 'GET /repos/{owner}/{repo}/contents/{path}', {path: 'README.md', ref: main});
    const text = Buffer.from(original.content, 'base64').toString('utf8'), updated = renderReadme(text, metadata);
    if (updated === text) { core.setOutput('readme_status', 'synced'); return; }
    if ((await call(github, context, 'GET /repos/{owner}/{repo}/releases/latest')).id !== latest.id) throw new Error('새 릴리즈가 공개되었습니다. README 단계를 다시 실행하세요.');
    const branch = await call(github, context, 'GET /repos/{owner}/{repo}/git/ref/{ref}', {ref: `heads/${BRANCH}`}, true);
    if (!branch) await call(github, context, 'POST /repos/{owner}/{repo}/git/refs', {ref: `refs/heads/${BRANCH}`, sha: main});
    const treeSha = (await call(github, context, 'GET /repos/{owner}/{repo}/git/commits/{commit_sha}', {commit_sha: main})).tree.sha;
    const tree = await call(github, context, 'POST /repos/{owner}/{repo}/git/trees', {base_tree: treeSha, tree: [{path: 'README.md', mode: '100644', type: 'blob', content: updated}]});
    const parents = [...new Set([main, branch?.object.sha || main])];
    const commit = await call(github, context, 'POST /repos/{owner}/{repo}/git/commits', {message: `docs: sync README for v${metadata.version}`, tree: tree.sha, parents});
    if ((await call(github, context, 'GET /repos/{owner}/{repo}/git/ref/{ref}', {ref: 'heads/main'})).object.sha !== main) continue;
    await call(github, context, 'PATCH /repos/{owner}/{repo}/git/refs/{ref}', {ref: `heads/${BRANCH}`, sha: commit.sha, force: false});
    const pulls = await syncPulls(github, context);
    const body = `## 문제•개선점\n\n공개된 ${latest.tag_name}의 배포 안내를 README에 반영합니다.\n\n## 변경 사항\n\n- 현재 버전·요약·APK·릴리즈 노트·체크섬 링크 갱신\n\n## 검증\n\n- [x] 공개 릴리즈 메타데이터 및 관리 영역 확인\n`;
    const pull = pulls[0] || await call(github, context, 'POST /repos/{owner}/{repo}/pulls', {title: 'docs: sync README release', head: BRANCH, base: 'main', body});
    if (pulls.length) await call(github, context, 'PATCH /repos/{owner}/{repo}/pulls/{pull_number}', {pull_number: pull.number, body});
    const current = await call(github, context, 'GET /repos/{owner}/{repo}/pulls/{pull_number}', {pull_number: pull.number});
    if (current.head.sha !== commit.sha) throw new Error('README PR의 HEAD가 동시 변경되었습니다.');
    let state;
    try {
      await github.graphql('mutation($id:ID!){enablePullRequestAutoMerge(input:{pullRequestId:$id,mergeMethod:SQUASH}){pullRequest{id}}}', {id: current.node_id});
      state = await call(github, context, 'GET /repos/{owner}/{repo}/pulls/{pull_number}', {pull_number: pull.number});
    } catch {
      const fresh = await call(github, context, 'GET /repos/{owner}/{repo}/pulls/{pull_number}', {pull_number: pull.number});
      if (fresh.merged) {
        state = fresh;
      } else if (fresh.mergeable_state === 'clean' && fresh.head.sha === commit.sha) {
        // 보호 규칙을 통과한 경우만 조건부 REST 병합을 시도하며 관리자 우회 권한은 사용하지 않는다.
        const merged = await call(github, context, 'PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge', {pull_number: pull.number, sha: commit.sha, merge_method: 'squash'});
        if (merged.merged !== true) throw new Error('README PR의 조건부 병합이 완료되지 않았습니다.');
        state = {...fresh, merged: true};
      } else {
        throw new Error('README 자동 병합을 설정하지 못했습니다. PR 보호 규칙과 권한을 확인하세요.');
      }
    }
    core.setOutput('readme_status', state.merged ? 'synced' : 'pending'); core.setOutput('readme_pr', pull.html_url);
    if (!state.merged) {
      core.warning('README PR은 필수 검사·보호 규칙 통과 후 병합됩니다. 아직 동기화가 완료되지 않았습니다.');
      await core.summary.addRaw(`릴리즈 공개 완료 / README 동기화 대기: [PR](${pull.html_url})\n\n필수 검사·보호 규칙을 확인하세요. PR 병합 전에는 동기화가 완료되지 않았습니다.\n`).write();
    }
    return;
  }
  throw new Error('main이 계속 변경되어 README 동기화를 완료하지 못했습니다.');
}

module.exports = {gate, prepare, makeNotes, publish, pauseReadme, readme, checkReadme, validateMetadata, renderReadme, triggerVersion};
