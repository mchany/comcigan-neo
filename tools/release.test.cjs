'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const release = require('./release.cjs');
const SHA = 'a'.repeat(40);
const context = {repo: {owner: 'mchany', repo: 'comcigan-neo'}, sha: SHA, ref: 'refs/heads/main', eventName: 'push', payload: {head_commit: {message: 'chore: release 1.1.1'}, before: '0'.repeat(40)}};
const metadata = {version: '1.1.1', version_code: 17, summary: '검색 오류 수정', changes: ['학교 검색 복구']};
/** 역할: 합성 Actions 출력 수집기를 만든다. 입력: 없음. 출력: 출력·경고와 core 대역. */
function coreFixture() {
  const outputs = {}, warnings = [];
  return {outputs, warnings, setOutput: (k, v) => { outputs[k] = v; }, warning: (v) => warnings.push(v), summary: {addRaw() { return this; }, async write() {}}};
}
/** 역할: 합성 API의 호출을 수집한다. 입력: 라우트 처리 함수. 출력: Octokit 대역과 호출 기록. */
function apiFixture(handler) {
  const calls = [];
  return {calls, async request(route, args) { calls.push([route, args]); return {data: await handler(route, args)}; }, async graphql(query, args) { calls.push([query, args]); return {}; }};
}
/** 역할: 임시 산출물 디렉터리를 제공한다. 입력: 테스트 실행 함수. 출력: 실행 결과이며 파일은 정리한다. */
async function temporary(run) {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'neo-release-'));
  try { return await run(folder); } finally { fs.rmSync(folder, {recursive: true, force: true}); }
}

// 역할: 명시적 커밋과 버전 입력을 검사한다. 입력: 정상·변조 문자열. 출력: 정확한 허용·거부 결과.
test('strict trigger and metadata validation', () => {
  assert.equal(release.triggerVersion('chore: release 1.1.1 (#20)\nbody'), '1.1.1');
  for (const value of ['chore: dependencies', 'chore: realese 1.1.1', 'fix: chore: release 1.1.1']) assert.equal(release.triggerVersion(value), null);
  for (const value of ['chore: release 01.1.1', 'chore: release 1.1.1; echo x']) assert.throws(() => release.triggerVersion(value));
  assert.deepEqual(release.validateMetadata(metadata), metadata);
  for (const patch of [{version_code: true}, {version: '01.1.1'}, {summary: 'a\nb'}, {changes: []}]) assert.throws(() => release.validateMetadata({...metadata, ...patch}));
});
// 역할: 일반 커밋을 배포에서 제외한다. 입력: 일반 push. 출력: trigger=false.
test('ordinary commit does not release', async () => {
  const core = coreFixture();
  const github = apiFixture(() => { throw new Error('unexpected API'); });
  await release.gate({github, core, context: {...context, payload: {head_commit: {message: 'ci: release automation'}}}});
  assert.equal(core.outputs.trigger, 'false');
});
// 역할: 버전 역행과 배포 정보 누락을 차단한다. 입력: 과거·새 릴리즈 이력. 출력: 검증 오류.
test('prepare validates commit and legacy baseline', async () => {
  const core = coreFixture();
  const github = apiFixture((route) => {
    if (route.includes('/git/ref/')) { const e = new Error(); e.status = 404; throw e; }
    if (route === 'GET /repos/{owner}/{repo}/releases') return [];
    throw new Error(route);
  });
  await release.prepare({github, core, context, metadata});
  assert.equal(core.outputs.version, '1.1.1');
  await assert.rejects(() => release.prepare({github, core, context, metadata: {...metadata, version_code: 16}}), /versionCode|version_code|버전/);
  await assert.rejects(() => release.prepare({github, core, context, metadata: {...metadata, version: '1.1.2'}}), /커밋|버전/);
});
// 역할: README의 사용자 본문을 보존하고 모호한 영역을 거부한다. 입력: 마커 포함·누락 본문. 출력: 영역만 갱신한 본문 또는 오류.
test('README managed region preserves other content', () => {
  const text = '앞\n<!-- release:start -->\nold\n<!-- release:end -->\n사용자 수정';
  const result = release.renderReadme(text, {...metadata, repository: 'mchany/comcigan-neo'});
  assert.match(result, /1\.1\.1/); assert.ok(result.endsWith('사용자 수정'));
  assert.match(result, /download\/v1\.1\.1\/SHA256SUMS.txt/);
  for (const invalid of ['없음', text + '<!-- release:start -->', '<!-- release:end --><!-- release:start -->']) assert.throws(() => release.renderReadme(invalid, metadata), /마커/);
});
// 역할: 해당 실행의 증거만 릴리즈 설명에 반영한다. 입력: 합성 APK·인증서·검사 결과. 출력: 해시와 사실에 맞는 설명.
test('notes include actual evidence and no historic device claims', async () => temporary(async (folder) => {
  const apk = path.join(folder, 'input.apk'); fs.writeFileSync(apk, 'synthetic-apk');
  const meta = release.makeNotes({metadata, apk, evidence: {checks: ['JVM 3개 통과'], certificate_sha256: 'f'.repeat(64)}, output: path.join(folder, 'out'), sha: SHA, repo: 'mchany/comcigan-neo', runUrl: 'https://github.com/mchany/comcigan-neo/actions/runs/1'});
  assert.equal(meta.source_sha, SHA); assert.equal(meta.apk_sha256.length, 64);
  const notes = fs.readFileSync(path.join(folder, 'out/release-notes.md'), 'utf8');
  assert.match(notes, /JVM 3개 통과/); assert.match(notes, /실기기.*미수행/); assert.doesNotMatch(notes, /A33/);
}));
// 역할: 과거 v1.1.0 이외의 메타데이터 누락을 거부한다. 입력: 새 공개 릴리즈. 출력: 배포 중단.
test('latest release without metadata cannot use legacy versionCode', async () => temporary(async (folder) => {
  const apk = path.join(folder, 'input.apk'); fs.writeFileSync(apk, 'apk');
  release.makeNotes({metadata, apk, evidence: {checks: ['검증'], certificate_sha256: 'f'.repeat(64)}, output: folder, sha: SHA, repo: 'mchany/comcigan-neo'});
  const github = apiFixture((route) => {
    if (route.includes('/git/ref/')) return {object: {type: 'commit', sha: SHA}};
    if (route.includes('/releases/latest')) return {tag_name: 'v1.1.2', assets: []};
    throw new Error(route);
  });
  await assert.rejects(() => release.publish({github, context, core: coreFixture(), metadata, directory: folder}), /메타데이터/);
}));
// 역할: 동일 산출물의 재게시에서 기존 파일을 바꾸지 않는다. 입력: 공개된 동일 릴리즈. 출력: 쓰기 API 호출 없음.
test('published release is immutable and identical rerun is a no-op', async () => temporary(async (folder) => {
  const apk = path.join(folder, 'input.apk'); fs.writeFileSync(apk, 'apk');
  const meta = release.makeNotes({metadata, apk, evidence: {checks: ['검증'], certificate_sha256: 'f'.repeat(64)}, output: folder, sha: SHA, repo: 'mchany/comcigan-neo'});
  const names = [meta.apk_name, 'SHA256SUMS.txt', 'release-metadata.json'];
  const item = {id: 1, tag_name: 'v1.1.1', draft: false, assets: names.map((name, id) => ({name, id})), html_url: 'https://github.com/mchany/comcigan-neo/releases/tag/v1.1.1'};
  const github = apiFixture((route, args) => {
    if (route.includes('/git/ref/')) return {object: {type: 'commit', sha: SHA}};
    if (route.includes('/releases/latest') || route.includes('/releases/tags/')) return item;
    if (route.includes('/releases/assets/')) return fs.readFileSync(path.join(folder, names[args.asset_id]));
    throw new Error(route);
  });
  await release.publish({github, context, core: coreFixture(), metadata, directory: folder});
  assert.ok(github.calls.every(([route]) => route.startsWith('GET ')));
  fs.writeFileSync(path.join(folder, meta.apk_name), 'changed');
  await assert.rejects(() => release.publish({github, context, core: coreFixture(), metadata, directory: folder}), /무결성/);
}));
// 역할: 새 공개 전에 이전 PR의 자동 병합을 멈춘다. 입력: 자동 병합 대기 PR. 출력: 해제 mutation.
test('pause uses GraphQL to disable existing README auto merge', async () => {
  const github = apiFixture(() => [{number: 25, node_id: 'PR_node', auto_merge: {enabled_by: {}}}]);
  await release.pauseReadme({github, context});
  assert.ok(github.calls.some(([route]) => route.includes('disablePullRequestAutoMerge')));
});
// 역할: Draft 업로드와 원격 검증을 거친 후에만 공개한다. 입력: 합성 Draft API. 출력: 올바른 업로드 호스트와 마지막 공개 요청.
test('draft assets upload to uploads host and are checked before publication', async () => temporary(async (folder) => {
  const apk = path.join(folder, 'input.apk'); fs.writeFileSync(apk, 'apk');
  release.makeNotes({metadata, apk, evidence: {checks: ['검증'], certificate_sha256: 'f'.repeat(64)}, output: folder, sha: SHA, repo: 'mchany/comcigan-neo'});
  let target = null, item = null;
  const bytes = new Map();
  const github = apiFixture((route, args) => {
    if (route.includes('/git/ref/')) { if (target) return {object: {type: 'commit', sha: target}}; const e = new Error(); e.status = 404; throw e; }
    if (route === 'POST /repos/{owner}/{repo}/git/refs') { target = args.sha; return {}; }
    if (route.includes('/releases/latest')) return {tag_name: 'v1.1.0', assets: []};
    if (route.includes('/releases/tags/')) { if (item) return item; const e = new Error(); e.status = 404; throw e; }
    if (route === 'POST /repos/{owner}/{repo}/releases') return (item = {id: 1, draft: true, tag_name: args.tag_name, assets: [], html_url: 'release-url'});
    if (route === 'POST https://uploads.github.com/repos/{owner}/{repo}/releases/{release_id}/assets{?name}') { const id = bytes.size; bytes.set(id, args.data); item.assets.push({id, name: args.name}); return {}; }
    if (route === 'GET /repos/{owner}/{repo}/releases/assets/{asset_id}') return Uint8Array.from(bytes.get(args.asset_id)).buffer;
    if (route === 'PATCH /repos/{owner}/{repo}/releases/{release_id}') { assert.equal(bytes.size, 3); item.draft = false; return item; }
    throw new Error(route);
  });
  const core = coreFixture();
  await release.publish({github, context, core, metadata, directory: folder});
  assert.equal(core.outputs.release_url, 'release-url');
  assert.equal(github.calls.at(-1)[0], 'PATCH /repos/{owner}/{repo}/releases/{release_id}');
}));
// 역할: 최신 공개 버전과 다른 README PR을 거부한다. 입력: 최신 메타데이터와 과거 본문. 출력: 오류 또는 정상 검사.
test('README PR check compares latest public release', async () => {
  const meta = {...metadata, repository: 'mchany/comcigan-neo'};
  const github = apiFixture((route) => route.includes('/releases/latest') ? {id: 1, tag_name: 'v1.1.1', assets: [{id: 1, name: 'release-metadata.json'}]} : Buffer.from(JSON.stringify(meta)));
  const text = '<!-- release:start -->old<!-- release:end -->';
  await assert.rejects(() => release.checkReadme({github, context, text}), /최신 공개/);
  await release.checkReadme({github, context, text: release.renderReadme(text, meta)});
});
// 역할: main 경합 시 최신 사용자 본문을 보존하고 실제 PR 병합 대기를 알린다. 입력: 바뀌는 main과 미병합 PR. 출력: pending.
test('README updates preserve changed main and report pending rather than completion', async () => {
  const meta = {...metadata, repository: 'mchany/comcigan-neo'};
  let mainReads = 0;
  const trees = [];
  const core = coreFixture();
  const github = apiFixture((route, args) => {
    if (route.includes('/releases/latest')) return {id: 1, tag_name: 'v1.1.1', assets: [{id: 1, name: 'release-metadata.json'}]};
    if (route.includes('/releases/assets/')) return Buffer.from(JSON.stringify(meta));
    if (route === 'GET /repos/{owner}/{repo}/git/ref/{ref}') return {object: {sha: args.ref === 'heads/main' ? (++mainReads === 1 ? 'a'.repeat(40) : 'b'.repeat(40)) : 'c'.repeat(40)}};
    if (route.includes('/contents/')) return {content: Buffer.from('앞<!-- release:start -->old<!-- release:end -->동시 수정 보존').toString('base64')};
    if (route === 'GET /repos/{owner}/{repo}/git/commits/{commit_sha}') return {tree: {sha: 'tree'}};
    if (route === 'POST /repos/{owner}/{repo}/git/trees') { trees.push(args.tree); return {sha: 'tree2'}; }
    if (route === 'POST /repos/{owner}/{repo}/git/commits') return {sha: 'd'.repeat(40)};
    if (route.startsWith('PATCH /repos/{owner}/{repo}/git/refs/')) return {};
    if (route === 'GET /repos/{owner}/{repo}/pulls') return [];
    if (route === 'POST /repos/{owner}/{repo}/pulls') return {number: 25, node_id: 'PR_node', html_url: 'pr-url'};
    if (route === 'GET /repos/{owner}/{repo}/pulls/{pull_number}') return {number: 25, node_id: 'PR_node', merged: false, head: {sha: 'd'.repeat(40)}};
    throw new Error(route);
  });
  await release.readme({github, context, core});
  assert.equal(trees.length, 2);
  assert.ok(trees.at(-1)[0].content.endsWith('동시 수정 보존'));
  assert.equal(core.outputs.readme_status, 'pending'); assert.equal(core.warnings.length, 1);
});
/** 역할: 자동 병합 거부 후 직접 병합 경계를 모사한다. 입력: PR 병합 가능 상태. 출력: API 대역과 core. */
function readmeFallbackFixture(mergeableState) {
  const meta = {...metadata, repository: 'mchany/comcigan-neo'};
  const core = coreFixture();
  const github = apiFixture((route, args) => {
    if (route.includes('/releases/latest')) return {id: 1, tag_name: 'v1.1.1', assets: [{id: 1, name: 'release-metadata.json'}]};
    if (route.includes('/releases/assets/')) return Buffer.from(JSON.stringify(meta));
    if (route === 'GET /repos/{owner}/{repo}/git/ref/{ref}') return {object: {sha: 'b'.repeat(40)}};
    if (route.includes('/contents/')) return {content: Buffer.from('<!-- release:start -->old<!-- release:end -->').toString('base64')};
    if (route === 'GET /repos/{owner}/{repo}/git/commits/{commit_sha}') return {tree: {sha: 'tree'}};
    if (route === 'POST /repos/{owner}/{repo}/git/trees') return {sha: 'tree2'};
    if (route === 'POST /repos/{owner}/{repo}/git/commits') return {sha: 'd'.repeat(40)};
    if (route.startsWith('PATCH /repos/{owner}/{repo}/git/refs/')) return {};
    if (route === 'GET /repos/{owner}/{repo}/pulls') return [];
    if (route === 'POST /repos/{owner}/{repo}/pulls') return {number: 25, node_id: 'PR_node', html_url: 'pr-url'};
    if (route === 'GET /repos/{owner}/{repo}/pulls/{pull_number}') return {number: 25, node_id: 'PR_node', merged: false, mergeable_state: mergeableState, head: {sha: 'd'.repeat(40)}};
    if (route === 'PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge') return {merged: true};
    throw new Error(route);
  });
  // 역할: 예약 자동 병합 API의 거부를 재현한다. 입력: mutation. 출력: 민감한 원문을 포함하지 않는 합성 오류.
  github.graphql = async () => { throw new Error('automatic merge unavailable'); };
  return {github, core};
}
// 역할: 검사 완료 PR에서만 정확한 HEAD로 직접 병합한다. 입력: clean PR과 자동 병합 거부. 출력: SHA 조건부 squash 병합과 synced.
test('clean README PR falls back to SHA-bound merge if auto merge is unavailable', async () => {
  const {github, core} = readmeFallbackFixture('clean');
  await release.readme({github, context, core});
  const request = github.calls.find(([route]) => route === 'PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge');
  assert.equal(request[1].sha, 'd'.repeat(40)); assert.equal(request[1].merge_method, 'squash');
  assert.equal(core.outputs.readme_status, 'synced');
});
// 역할: 차단되거나 검사 중인 PR을 직접 병합하지 않는다. 입력: blocked PR과 자동 병합 거부. 출력: 실패이며 merge 호출 없음.
test('blocked README PR does not bypass protections after auto merge failure', async () => {
  const {github, core} = readmeFallbackFixture('blocked');
  await assert.rejects(() => release.readme({github, context, core}), /자동 병합|차단/);
  assert.equal(github.calls.some(([route]) => route.startsWith('PUT ')), false);
  assert.equal(core.outputs.readme_status, undefined);
});
