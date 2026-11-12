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
  const embedded = /<!-- release-metadata:([A-Za-z0-9+/=]+) -->/.exec(notes);
  assert.deepEqual(JSON.parse(Buffer.from(embedded[1], 'base64').toString('utf8')), meta);
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
    if (route.includes('/releases/latest') || route.includes('/releases/tags/') || route === 'GET /repos/{owner}/{repo}/releases/{release_id}') return item;
    if (route.includes('/releases/assets/')) return fs.readFileSync(path.join(folder, names[args.asset_id]));
    throw new Error(route);
  });
  await release.publish({github, context, core: coreFixture(), metadata, directory: folder});
  assert.ok(github.calls.every(([route]) => route.startsWith('GET ')));
  fs.writeFileSync(path.join(folder, meta.apk_name), 'changed');
  await assert.rejects(() => release.publish({github, context, core: coreFixture(), metadata, directory: folder}), /무결성/);
}));
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
    if (route.includes('/releases/tags/')) { const e = new Error(); e.status = 404; throw e; }
    if (route === 'GET /repos/{owner}/{repo}/releases') return item ? [item] : [];
    if (route === 'GET /repos/{owner}/{repo}/releases/{release_id}') return item;
    if (route === 'POST /repos/{owner}/{repo}/releases') return (item = {id: 1, draft: true, tag_name: args.tag_name, assets: [], html_url: 'release-url'});
    if (route === 'POST https://uploads.github.com/repos/{owner}/{repo}/releases/{release_id}/assets{?name}') { const id = bytes.size; bytes.set(id, args.data); item.assets.push({id, name: args.name}); return {}; }
    if (route === 'GET /repos/{owner}/{repo}/releases/assets/{asset_id}') return Uint8Array.from(bytes.get(args.asset_id)).buffer;
    if (route === 'PATCH /repos/{owner}/{repo}/releases/{release_id}') { assert.equal(bytes.size, 2); assert.deepEqual(item.assets.map(a => a.name), ['comcigan-neo-1.1.1.apk', 'SHA256SUMS.txt']); item.draft = false; return item; }
    throw new Error(route);
  });
  const core = coreFixture();
  await release.publish({github, context, core, metadata, directory: folder});
  assert.equal(core.outputs.release_url, 'release-url');
  assert.equal(github.calls.at(-1)[0], 'PATCH /repos/{owner}/{repo}/releases/{release_id}');
}));
/** 역할: main README 직접 반영 API를 모사한다. 입력: 충돌·릴리즈 교체 옵션. 출력: API와 출력 수집기. */
function directReadmeFixture(options = {}) {
  const meta = {...metadata, repository: 'mchany/comcigan-neo'};
  const original = '사용자 본문\n<!-- release:start -->old<!-- release:end -->\n끝';
  const core = coreFixture();
  let reads = 0, writes = 0, latestReads = 0;
  const github = apiFixture((route, args) => {
    if (route.endsWith('/releases/latest')) return {id: options.newRelease && ++latestReads > 1 ? 2 : 1, tag_name: 'v1.1.1', body: '<!-- release-metadata:' + Buffer.from(JSON.stringify(meta)).toString('base64') + ' -->', assets: []};
    if (route.includes('/releases/assets/')) throw new Error('JSON attachment must not be required');
    if (route === 'GET /repos/{owner}/{repo}/contents/{path}') {
      assert.equal(args.ref, 'main'); assert.equal(args.path, 'README.md');
      reads++;
      const text = options.synced ? release.renderReadme(original, meta) : original + (reads > 1 ? '동시 수정' : '');
      return {type: 'file', sha: String(reads).repeat(40), encoding: 'base64', content: Buffer.from(text).toString('base64')};
    }
    if (route === 'PUT /repos/{owner}/{repo}/contents/{path}') {
      writes++;
      assert.equal(args.branch, 'main'); assert.equal(args.path, 'README.md');
      assert.equal(args.sha, String(reads).repeat(40));
      assert.match(args.message, /^docs: sync README/);
      if (options.status && (options.alwaysFail || writes === 1)) { const error = new Error('private API details'); error.status = options.status; throw error; }
      return {commit: {sha: 'b'.repeat(40)}};
    }
    throw new Error('Unexpected request: ' + route);
  });
  return {github, core};
}
// 역할: PR·브랜치 없이 README 한 파일만 main에 반영한다. 입력: 최신 릴리즈. 출력: 직접 커밋 및 synced.
test('README commits directly to main without PR or branch APIs', async () => {
  const f = directReadmeFixture(); await release.readme({...f, context});
  const writes = f.github.calls.filter(([route]) => !route.startsWith('GET '));
  assert.equal(writes.length, 1); assert.equal(writes[0][0], 'PUT /repos/{owner}/{repo}/contents/{path}');
  assert.match(Buffer.from(writes[0][1].content, 'base64').toString('utf8'), /사용자 본문/);
  assert.equal(f.core.outputs.readme_status, 'synced');
});
// 역할: 같은 파일 충돌 시 사용자 변경을 보존한다. 입력: 최초 쓰기의 409. 출력: 새 파일 SHA로 재시도.
test('README conflict retries with latest main content and file SHA', async () => {
  const f = directReadmeFixture({status: 409}); await release.readme({...f, context});
  const writes = f.github.calls.filter(([route]) => route.startsWith('PUT '));
  assert.equal(writes.length, 2);
  assert.ok(Buffer.from(writes[1][1].content, 'base64').toString('utf8').endsWith('동시 수정'));
  assert.equal(f.core.outputs.readme_status, 'synced');
});
// 역할: 이미 반영된 README에는 중복 커밋을 만들지 않는다. 입력: 최신 본문. 출력: 쓰기 없음.
test('README rerun is a no-op when main already matches', async () => {
  const f = directReadmeFixture({synced: true}); await release.readme({...f, context});
  assert.ok(f.github.calls.every(([route]) => route.startsWith('GET ')));
  assert.equal(f.core.outputs.readme_status, 'synced');
});
// 역할: 보호 규칙과 권한 실패를 숨기거나 우회하지 않는다. 입력: 403·422 응답. 출력: 재시도 없는 실패.
test('README rejects permission or protection errors without retrying', async () => {
  for (const status of [403, 422]) {
    const f = directReadmeFixture({status}); await assert.rejects(() => release.readme({...f, context}), new RegExp(String(status)));
    assert.equal(f.github.calls.filter(([r]) => r.startsWith('PUT ')).length, 1);
    assert.notEqual(f.core.outputs.readme_status, 'synced');
  }
});
// 역할: 지속 충돌과 릴리즈 교체를 명시적으로 중단한다. 입력: 경합·신규 릴리즈. 출력: 제한된 실패.
test('README bounds conflict retries and refuses a superseded release', async () => {
  const conflict = directReadmeFixture({status: 409, alwaysFail: true});
  await assert.rejects(() => release.readme({...conflict, context}), /계속 변경/);
  assert.equal(conflict.github.calls.filter(([r]) => r.startsWith('PUT ')).length, 3);
  const stale = directReadmeFixture({newRelease: true});
  await assert.rejects(() => release.readme({...stale, context}), /새 릴리즈/);
  assert.ok(stale.github.calls.every(([r]) => r.startsWith('GET ')));
});
// 역할: PR과 다른 브랜치에서 main 쓰기를 막는다. 입력: PR context. 출력: API 호출 전 거부.
test('README direct writes require official main context', async () => {
  const f = directReadmeFixture();
  await assert.rejects(() => release.readme({...f, context: {...context, ref: 'refs/pull/22/merge'}}), /main/);
  assert.equal(f.github.calls.length, 0);
});
/** 역할: 태그 조회가 Draft를 숨기는 API를 모사한다. 입력: 산출물·변조 옵션. 출력: 기존 Draft와 API 호출 기록. */
function existingDraftFixture(folder, options = {}) {
  const names = ['comcigan-neo-1.1.1.apk', 'SHA256SUMS.txt', 'release-metadata.json'];
  const item = {id: 42, tag_name: 'v1.1.1', draft: true, assets: names.map((name, id) => ({name, id})), html_url: 'draft-url'};
  const github = apiFixture((route, args) => {
    if (route.includes('/git/ref/')) return {object: {type: 'commit', sha: SHA}};
    if (route.includes('/releases/latest')) return {tag_name: 'v1.1.0', assets: []};
    if (route.includes('/releases/tags/')) { const error = new Error(); error.status = 404; throw error; }
    if (route === 'GET /repos/{owner}/{repo}/releases') {
      if (args.page === 1) return Array.from({length: 100}, (_, id) => ({id, tag_name: 'v1.0.5', draft: false}));
      return options.duplicate ? [item, {...item, id: 43}] : [item];
    }
    if (route === 'GET /repos/{owner}/{repo}/releases/{release_id}') {
      assert.equal(args.release_id, 42);
      if (options.deleted) { const error = new Error(); error.status = 404; throw error; }
      return item;
    }
    if (route.includes('/releases/assets/')) return options.corrupt && args.asset_id === 0 ? Buffer.from('wrong APK') : fs.readFileSync(path.join(folder, names[args.asset_id]));
    if (route === 'PATCH /repos/{owner}/{repo}/releases/{release_id}') { item.draft = false; return item; }
    throw new Error('Unexpected write or request: ' + route);
  });
  return {github, item};
}
/** 역할: 합성 릴리즈 산출물을 생성한다. 입력: 임시 폴더. 출력: 검증된 파일 세트. */
function draftFiles(folder) {
  const apk = path.join(folder, 'input.apk'); fs.writeFileSync(apk, 'synthetic APK');
  release.makeNotes({metadata, apk, evidence: {checks: ['검증'], certificate_sha256: 'f'.repeat(64)}, output: folder, sha: SHA, repo: 'mchany/comcigan-neo'});
}
// 역할: 목록의 다음 페이지에 있는 기존 Draft를 복구한다. 입력: 태그 404와 기존 첨부. 출력: 재생성·재업로드 없는 공개.
test('existing draft is resumed by ID without recreating or replacing assets', async () => temporary(async (folder) => {
  draftFiles(folder);
  const {github, item} = existingDraftFixture(folder);
  await release.publish({github, context, core: coreFixture(), metadata, directory: folder});
  assert.equal(item.draft, false);
  assert.ok(github.calls.some(([route, args]) => route === 'GET /repos/{owner}/{repo}/releases' && args.page === 2));
  assert.ok(github.calls.every(([route]) => !route.startsWith('POST ')));
}));
// 역할: 기존 Draft APK의 바이트가 다르면 덮어쓰지 않는다. 입력: 변조한 원격 첨부. 출력: 공개·쓰기 없이 거부.
test('existing draft with different APK bytes fails without writes', async () => temporary(async (folder) => {
  draftFiles(folder);
  const {github} = existingDraftFixture(folder, {corrupt: true});
  await assert.rejects(() => release.publish({github, context, core: coreFixture(), metadata, directory: folder}), /덮어쓰지/);
  assert.ok(github.calls.every(([route]) => route.startsWith('GET ')));
}));
// 역할: 같은 태그의 여러 Draft 중 임의로 하나를 선택하지 않는다. 입력: 중복 Draft. 출력: 쓰기 없이 거부.
test('duplicate drafts fail without selecting an arbitrary release', async () => temporary(async (folder) => {
  draftFiles(folder);
  const {github} = existingDraftFixture(folder, {duplicate: true});
  await assert.rejects(() => release.publish({github, context, core: coreFixture(), metadata, directory: folder}), /여러 개/);
  assert.ok(github.calls.every(([route]) => route.startsWith('GET ')));
}));
// 역할: 업로드 후 Draft 삭제를 null 역참조 대신 명시적 오류로 처리한다. 입력: ID 조회 404. 출력: 공개하지 않고 중단.
test('draft deleted before verification fails explicitly without publication', async () => temporary(async (folder) => {
  draftFiles(folder);
  const {github} = existingDraftFixture(folder, {deleted: true});
  await assert.rejects(() => release.publish({github, context, core: coreFixture(), metadata, directory: folder}), /404/);
  assert.ok(github.calls.every(([route]) => route.startsWith('GET ')));
}));

// 역할: 첨부 없는 릴리즈에서도 이전 versionCode 역행을 막는다. 입력: 본문 메타데이터만 있는 기존 릴리즈. 출력: 이전 코드 이하 버전 거부.
test('prepare validates prior versionCode using release body without JSON attachment', async () => {
  const prior = {...metadata, version_code: 19, repository: 'mchany/comcigan-neo'};
  const github = apiFixture((route) => {
    if (route.includes('/git/ref/')) { const e = new Error(); e.status = 404; throw e; }
    if (route === 'GET /repos/{owner}/{repo}/releases') return [{tag_name:'v1.1.1',assets:[],body:'<!-- release-metadata:' + Buffer.from(JSON.stringify(prior)).toString('base64') + ' -->'}];
    throw new Error(route);
  });
  await assert.rejects(() => release.prepare({github, context: {...context, payload:{head_commit:{message:'chore: release 1.1.2'}}}, core:coreFixture(), metadata:{...metadata,version:'1.1.2',version_code:18}}), /버전과 versionCode는/);
});
