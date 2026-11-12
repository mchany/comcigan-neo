"""TOML 기반 릴리즈 조립과 GitHub 게시. Python 3.12 표준 라이브러리만 사용한다."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
import urllib.error
import urllib.parse
import urllib.request

VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
SHA = re.compile(r"[0-9a-f]{40}")
MARKERS = ("<!-- release:start -->", "<!-- release:end -->")
BRANCH = "codex/readme-release-sync"


def checked_version(value):
    """역할: 정식 버전을 검사한다. 입력: 버전 문자열. 출력: 숫자 세 쌍 또는 오류."""
    if not isinstance(value, str) or not re.fullmatch(VERSION, value):
        raise ValueError("정식 버전 X.Y.Z가 필요합니다.")
    return tuple(map(int, value.split(".")))


def trigger_version(message):
    """역할: 릴리즈 커밋을 판별한다. 입력: 커밋 메시지. 출력: 요청 버전 또는 None."""
    title = message.splitlines()[0] if message else ""
    if not title.startswith("chore: release "):
        return None
    match = re.fullmatch(rf"chore: release ({VERSION})(?: \(#[0-9]+\))?", title)
    if not match:
        raise ValueError("릴리즈 커밋 형식이 잘못되었습니다.")
    return match[1]


def manifest(path=Path("release.toml")):
    """역할: 버전 파일을 엄격히 읽는다. 입력: TOML 경로. 출력: 검증된 릴리즈 정보."""
    with Path(path).open("rb") as source:
        data = tomllib.load(source)["release"]
    checked_version(data.get("version"))
    code = data.get("version_code")
    if type(code) is not int or not 1 <= code <= 2100000000:
        raise ValueError("version_code는 양의 Android 버전 코드여야 합니다.")
    if not isinstance(data.get("changes"), list) or not data["changes"]:
        raise ValueError("변경 사항이 필요합니다.")
    for value in [data.get("summary"), *data["changes"]]:
        if not isinstance(value, str) or not value.strip() or any(c in value for c in "\r\n\x00"):
            raise ValueError("설명은 비어 있지 않은 한 줄 문자열이어야 합니다.")
    return data


def validate_progress(version, code, previous_version, previous_code):
    """역할: 버전 역행을 차단한다. 입력: 새 버전과 배포 기준. 출력: 정상 시 None."""
    if checked_version(version) <= checked_version(previous_version) or code <= previous_code:
        raise ValueError("버전과 version_code는 기존 배포보다 커야 합니다.")


def command(args):
    """역할: 셸 없이 도구를 실행한다. 입력: 인수 목록. 출력: 표준 출력 또는 오류."""
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=90)
    if result.returncode:
        raise ValueError(f"도구 실행 실패: {args[0]}")
    return result.stdout.strip()


def source_sha():
    """역할: 고정 소스 SHA를 읽는다. 입력: 환경 또는 HEAD. 출력: 검증된 SHA."""
    value = os.environ.get("RELEASE_SHA") or command(["git", "rev-parse", "HEAD"])
    if not SHA.fullmatch(value):
        raise ValueError("정확한 소스 SHA가 필요합니다.")
    return value


def repository():
    """역할: 저장소 식별자를 검사한다. 입력: GITHUB_REPOSITORY. 출력: owner/repo."""
    value = os.environ.get("GITHUB_REPOSITORY", "mchany/comcigan-neo")
    if value != "mchany/comcigan-neo":
        raise ValueError("공식 저장소에서만 릴리즈할 수 있습니다.")
    return value


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    """첨부 다운로드 리다이렉트에서 API 인증 헤더를 제거한다."""

    def redirect_request(self, request, fp, code, message, headers, new_url):
        """역할: 안전한 HTTPS 첨부 호스트만 허용한다. 입력: 리다이렉트. 출력: 인증 없는 새 요청."""
        parsed = urllib.parse.urlparse(new_url)
        if parsed.scheme != "https" or not (parsed.hostname or "").endswith(".githubusercontent.com"):
            raise ValueError("허용되지 않은 첨부 리다이렉트입니다.")
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        redirected.remove_header("Authorization")
        return redirected


class GitHub:
    """제한된 오류 출력과 타임아웃을 사용하는 GitHub API 클라이언트."""

    def __init__(self, repo):
        """역할: API 인증을 준비한다. 입력: 저장소 이름. 출력: 클라이언트."""
        self.repo = repo
        self.token = os.environ.get("GH_TOKEN", "")
        if not self.token:
            raise ValueError("GH_TOKEN이 필요합니다.")

    def request(self, path, method="GET", data=None, raw=False, content_type="application/json", missing=False):
        """역할: GitHub API를 호출한다. 입력: 경로와 요청값. 출력: JSON 또는 바이트."""
        url = path if path.startswith("https://") else f"https://api.github.com/repos/{self.repo}/{path}"
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in {"api.github.com", "uploads.github.com"}:
            raise ValueError("허용되지 않은 API 호스트입니다.")
        payload = data if isinstance(data, bytes) else (json.dumps(data).encode() if data is not None else None)
        request = urllib.request.Request(url, data=payload, method=method, headers={
            "Authorization": f"Bearer {self.token}", "Accept": "application/octet-stream" if raw else "application/vnd.github+json",
            "Content-Type": content_type, "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "comcigan-neo-release",
        })
        try:
            with urllib.request.build_opener(SafeRedirect()).open(request, timeout=60) as response:
                body = response.read(100 * 1024 * 1024)
                return body if raw else (json.loads(body) if body else None)
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing:
                return None
            raise ValueError(f"GitHub API {method} 요청 실패: HTTP {error.code}") from None

    def release(self, tag):
        """역할: 태그 릴리즈를 조회한다. 입력: 태그. 출력: 릴리즈 또는 None."""
        return self.request(f"releases/tags/{urllib.parse.quote(tag, safe='')}", missing=True)

    def tag_sha(self, tag):
        """역할: 경량·주석 태그를 커밋으로 해석한다. 입력: 태그. 출력: SHA 또는 None."""
        ref = self.request(f"git/ref/tags/{urllib.parse.quote(tag, safe='')}", missing=True)
        if ref is None:
            return None
        obj = ref["object"]
        for _ in range(5):
            if obj["type"] == "commit":
                return obj["sha"]
            if obj["type"] != "tag":
                break
            obj = self.request(f"git/tags/{obj['sha']}")["object"]
        raise ValueError("태그가 정상 커밋을 가리키지 않습니다.")

    def asset(self, release, name):
        """역할: 중복 이름 없이 첨부를 읽는다. 입력: 릴리즈와 이름. 출력: 바이트 또는 None."""
        matches = [item for item in release["assets"] if item["name"] == name]
        if len(matches) > 1:
            raise ValueError("릴리즈 첨부 이름이 중복되었습니다.")
        return self.request(f"releases/assets/{matches[0]['id']}", raw=True) if matches else None


def output(**values):
    """역할: Actions에 단일행 결과를 전달한다. 입력: 안전한 결과값. 출력: 출력 파일 기록."""
    destination = os.environ.get("GITHUB_OUTPUT")
    for key, value in values.items():
        if any(c in str(value) for c in "\r\n"):
            raise ValueError("다중행 출력은 지원하지 않습니다.")
    text = "".join(f"{key}={value}\n" for key, value in values.items())
    if destination:
        with open(destination, "a", encoding="utf-8") as stream:
            stream.write(text)
    else:
        print(text, end="")


def prepare():
    """역할: 커밋·배포 이력과 버전을 검증한다. 입력: 환경과 TOML. 출력: Actions 릴리즈 입력."""
    sha = source_sha()
    requested = trigger_version(command(["git", "show", "-s", "--format=%B", sha]))
    manual = os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if os.environ.get("GITHUB_EVENT_NAME") == "push" and event_path:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        before = event.get("before", "")
        if SHA.fullmatch(before) and before != "0" * 40:
            messages = command(["git", "log", "--format=%s", f"{before}..{sha}"]).splitlines()
            requests = [trigger_version(message) for message in messages]
            if sum(value is not None for value in requests) > 1 or (any(requests) and requested is None):
                raise ValueError("릴리즈 요청은 push의 마지막 커밋 한 개여야 합니다.")
    if requested is None and not manual:
        output(trigger="false")
        return
    data = manifest()
    version, code = data["version"], data["version_code"]
    if requested and requested != version:
        raise ValueError("커밋 버전과 TOML 버전이 다릅니다.")
    github = GitHub(repository())
    tag = f"v{version}"
    existing_sha = github.tag_sha(tag)
    if existing_sha and existing_sha != sha:
        raise ValueError("태그가 다른 SHA를 가리킵니다.")
    existing = github.release(tag)
    # Legacy APK versionCode 16 is documented by v1.1.0; all newer releases require metadata.
    prior_version, prior_code = "1.1.0", 16
    for page in range(1, 101):
        releases = github.request(f"releases?per_page=100&page={page}")
        for item in releases:
            if item["draft"] or item["prerelease"] or not re.fullmatch(rf"v{VERSION}", item["tag_name"]):
                continue
            if item["tag_name"] == tag and existing_sha == sha:
                continue
            prior_version = max(prior_version, item["tag_name"][1:], key=checked_version)
            raw = github.asset(item, "release-metadata.json")
            if raw:
                meta = json.loads(raw)
                prior_code = max(prior_code, int(meta["version_code"]))
            elif item["tag_name"] != "v1.1.0" and checked_version(item["tag_name"][1:]) > (1, 1, 0):
                raise ValueError("기존 릴리즈의 versionCode 메타데이터가 없습니다.")
        if len(releases) < 100:
            break
    else:
        raise ValueError("릴리즈 이력이 조회 한도를 초과했습니다.")
    validate_progress(version, code, prior_version, prior_code)
    if existing and not existing_sha:
        raise ValueError("릴리즈 태그가 없습니다.")
    output(trigger="true", version=version, code=code, tag=tag, name=f"comcigan-neo-{version}.apk", sha=sha)


def digest(path):
    """역할: 파일 무결성 값을 계산한다. 입력: 파일 경로. 출력: SHA-256 문자열."""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_latest(github, data):
    """역할: 게시 직전 최신 배포와 버전을 비교한다. 입력: API와 버전. 출력: 정상 또는 오류."""
    latest = github.request("releases/latest", missing=True)
    if latest and latest["tag_name"] != "v" + data["version"]:
        raw = github.asset(latest, "release-metadata.json")
        if raw is None and latest["tag_name"] != "v1.1.0":
            raise ValueError("기존 릴리즈의 versionCode 메타데이터가 없습니다.")
        previous_code = json.loads(raw)["version_code"] if raw else 16
        validate_progress(data["version"], data["version_code"], latest["tag_name"].removeprefix("v"), previous_code)


def make_notes(data, apk, evidence, destination, sha, repo):
    """역할: 실행 근거로 릴리즈 산출물을 만든다. 입력: 버전·APK·근거. 출력: 출력 폴더."""
    checks = evidence.get("checks")
    cert = evidence.get("certificate_sha256", "").replace(":", "").lower()
    if not isinstance(checks, list) or not checks or not all(isinstance(item, str) and item and "\n" not in item for item in checks):
        raise ValueError("실행 검증 근거가 필요합니다.")
    if not re.fullmatch(r"[0-9a-f]{64}", cert) or not SHA.fullmatch(sha):
        raise ValueError("인증서 지문과 소스 SHA가 필요합니다.")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    version = data["version"]
    name = f"comcigan-neo-{version}.apk"
    target = destination / name
    if Path(apk).resolve() != target.resolve():
        shutil.copyfile(apk, target)
    meta = {"version": version, "version_code": data["version_code"], "source_sha": sha,
            "repository": repo, "summary": data["summary"], "apk_name": name, "apk_sha256": digest(target),
            "certificate_sha256": cert, "checks": checks, "run_url": os.environ.get("GITHUB_SERVER_URL", "https://github.com") + f"/{repo}/actions/runs/" + os.environ.get("GITHUB_RUN_ID", "local")}
    (destination / "release-metadata.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (destination / "SHA256SUMS.txt").write_text(f"{meta['apk_sha256']}  {name}\n", encoding="utf-8")
    notes = f"## 컴시간neo {version}\n\nAndroid 8.0(API 26) 이상을 지원하는 정식 APK입니다. {data['summary']}\n\n### 수정 및 개선 사항\n\n"
    notes += "".join(f"- {item}\n" for item in data["changes"])
    notes += f"\n### 설치 및 업데이트\n\n\u0060{name}\u0060를 내려받아 설치하세요. 기존 배포 인증서와 일치하는 서명을 확인했습니다.\n\n- 앱 ID: \u0060io.github.mchany.comciganneo\u0060\n- 버전: \u0060{version}\u0060 / versionCode \u0060{data['version_code']}\u0060\n- 지원 환경: Android 8.0(API 26) 이상\n- 소스: [릴리즈 빌드 기준 커밋](https://github.com/{repo}/commit/{sha})\n\n### 검증\n\n"
    notes += "".join(f"- {item}\n" for item in checks)
    notes += f"\n[자동 검증 실행 기록]({meta['run_url']})\n\n실기기 업데이트·설정 보존 검증은 이번 자동화에서 미수행했습니다.\nAPK의 SHA-256은 첨부된 \u0060SHA256SUMS.txt\u0060에서 확인할 수 있습니다.\n"
    (destination / "release-notes.md").write_text(notes, encoding="utf-8")


def publish(folder):
    """역할: 검증 산출물을 Draft로 올리고 공개한다. 입력: 산출물 폴더. 출력: 공개 릴리즈 URL."""
    root = Path(folder)
    meta = json.loads((root / "release-metadata.json").read_text(encoding="utf-8"))
    data = manifest()
    sha, repo = source_sha(), repository()
    if any([meta["source_sha"] != sha, meta["repository"] != repo, meta["version"] != data["version"], meta["version_code"] != data["version_code"]]):
        raise ValueError("산출물과 배포 입력이 다릅니다.")
    name = f"comcigan-neo-{data['version']}.apk"
    if meta["apk_name"] != name or digest(root / name) != meta["apk_sha256"]:
        raise ValueError("APK 무결성 검증에 실패했습니다.")
    if (root / "SHA256SUMS.txt").read_text(encoding="utf-8") != f"{meta['apk_sha256']}  {name}\n":
        raise ValueError("체크섬 파일이 APK와 다릅니다.")
    github = GitHub(repo)
    tag = "v" + data["version"]
    tag_sha = github.tag_sha(tag)
    if tag_sha and tag_sha != sha:
        raise ValueError("다른 소스의 기존 태그는 변경하지 않습니다.")
    validate_latest(github, data)
    if not tag_sha:
        github.request("git/refs", "POST", {"ref": "refs/tags/" + tag, "sha": sha})
    release = github.release(tag)
    if release is None:
        release = github.request("releases", "POST", {"tag_name": tag, "target_commitish": sha, "name": "컴시간neo " + data["version"], "draft": True, "prerelease": False})
    filenames = [name, "SHA256SUMS.txt", "release-metadata.json"]
    for filename in filenames:
        payload = (root / filename).read_bytes()
        previous = github.asset(release, filename)
        if previous is not None:
            if previous != payload:
                raise ValueError("기존 릴리즈 첨부는 덮어쓰지 않습니다.")
        elif not release["draft"]:
            raise ValueError("기존 공개 릴리즈의 첨부가 누락되었습니다.")
        else:
            github.request(f"https://uploads.github.com/repos/{repo}/releases/{release['id']}/assets?name={urllib.parse.quote(filename)}", "POST", payload, content_type="application/octet-stream")
    release = github.release(tag)
    for filename in filenames:
        if github.asset(release, filename) != (root / filename).read_bytes():
            raise ValueError("원격 첨부 검증에 실패했습니다.")
    if release["draft"]:
        validate_latest(github, data)
        if github.tag_sha(tag) != sha:
            raise ValueError("게시 직전 태그 대상이 변경되었습니다.")
        release = github.request(f"releases/{release['id']}", "PATCH", {"body": (root / "release-notes.md").read_text(encoding="utf-8"), "draft": False, "make_latest": "true"})
    output(release_url=release["html_url"])


def render_readme(text, meta):
    """역할: 배포 안내 영역만 생성한다. 입력: README와 공개 정보. 출력: 수정된 README."""
    if any(text.count(marker) != 1 for marker in MARKERS):
        raise ValueError("README 관리 영역 마커가 누락되거나 중복되었습니다.")
    start, end = (text.index(marker) for marker in MARKERS)
    if start >= end:
        raise ValueError("README 마커 순서가 잘못되었습니다.")
    version = meta["version"]
    checked_version(version)
    repo = meta["repository"]
    base = f"https://github.com/{repo}/releases"
    block = f"\n현재 배포 버전: **{version} — {meta['summary']}** Android 8.0(API 26) 이상에서 설치할 수 있습니다.\n\n"
    block += f"[![APK 다운로드 · {version}](https://img.shields.io/badge/APK_다운로드-{version}-3DDC84?style=for-the-badge&logo=android&logoColor=white)]({base}/download/v{version}/comcigan-neo-{version}.apk)\n"
    block += f"[![릴리즈 노트 · {version}](https://img.shields.io/badge/릴리즈_노트-{version}-181717?style=for-the-badge&logo=github&logoColor=white)]({base}/tag/v{version})\n\n"
    block += f"기존 배포 인증서와 동일한 인증서로 서명한 APK입니다.\nAPK의 SHA-256은 릴리즈에 첨부된 [SHA256SUMS.txt]({base}/download/v{version}/SHA256SUMS.txt)에서 확인할 수 있습니다.\n"
    return text[:start + len(MARKERS[0])] + block + text[end:]


def check_readme(path=Path("README.md")):
    """역할: 오래된 동기화 PR을 거부한다. 입력: 검사할 README. 출력: 최신 일치 시 정상."""
    github = GitHub(repository())
    latest = github.request("releases/latest")
    raw = github.asset(latest, "release-metadata.json")
    if raw is None:
        raise ValueError("최신 릴리즈 메타데이터가 없습니다.")
    meta = json.loads(raw)
    if meta["repository"] != github.repo or latest["tag_name"] != "v" + meta["version"]:
        raise ValueError("최신 릴리즈 메타데이터가 다릅니다.")
    text = Path(path).read_text(encoding="utf-8")
    if text != render_readme(text, meta):
        raise ValueError("README가 최신 공개 릴리즈와 다릅니다.")


def pause_readme():
    """역할: 새 공개 전 이전 README 자동 병합을 멈춘다. 입력: API 환경. 출력: 해제 완료."""
    github = GitHub(repository())
    pulls = github.request("pulls?state=open&base=main&head=" + urllib.parse.quote(github.repo.split("/")[0] + ":" + BRANCH))
    for pull in pulls:
        if pull.get("auto_merge") is not None:
            command(["gh", "pr", "merge", str(pull["number"]), "--repo", github.repo, "--disable-auto"])


def readme():
    """역할: 최신 공개 릴리즈의 README 동기화 PR을 갱신한다. 입력: API 환경. 출력: PR 상태."""
    github = GitHub(repository())
    latest = github.request("releases/latest")
    raw = github.asset(latest, "release-metadata.json")
    if not raw:
        raise ValueError("최신 공개 릴리즈의 메타데이터가 없습니다.")
    meta = json.loads(raw)
    if latest["tag_name"] != "v" + meta["version"] or meta["repository"] != github.repo:
        raise ValueError("최신 릴리즈 메타데이터가 일치하지 않습니다.")
    for _ in range(3):
        main = github.request("git/ref/heads/main")["object"]["sha"]
        original = github.request("contents/README.md?ref=" + main)
        text = base64.b64decode(original["content"]).decode("utf-8")
        updated = render_readme(text, meta)
        if updated == text:
            output(readme_status="synced")
            return
        if github.request("releases/latest")["id"] != latest["id"]:
            raise ValueError("새 릴리즈가 공개되었습니다. README 단계를 다시 실행하세요.")
        branch = github.request("git/ref/heads/" + BRANCH, missing=True)
        if not branch:
            github.request("git/refs", "POST", {"ref": "refs/heads/" + BRANCH, "sha": main})
            parent = main
        else:
            parent = branch["object"]["sha"]
        tree_sha = github.request("git/commits/" + main)["tree"]["sha"]
        tree = github.request("git/trees", "POST", {"base_tree": tree_sha, "tree": [{"path": "README.md", "mode": "100644", "type": "blob", "content": updated}]})
        parents = list(dict.fromkeys([main, parent]))
        commit = github.request("git/commits", "POST", {"message": "docs: sync README for v" + meta["version"], "tree": tree["sha"], "parents": parents})
        if github.request("git/ref/heads/main")["object"]["sha"] != main:
            continue
        github.request("git/refs/heads/" + BRANCH, "PATCH", {"sha": commit["sha"], "force": False})
        pulls = github.request("pulls?state=open&base=main&head=" + urllib.parse.quote(github.repo.split("/")[0] + ":" + BRANCH))
        body = f"## 문제•개선점\n\n공개된 {latest['tag_name']}의 배포 안내를 README에 반영합니다.\n\n## 변경 사항\n\n- 현재 버전·요약·APK·릴리즈 노트·체크섬 링크 갱신\n\n## 검증\n\n- [x] 공개 릴리즈 메타데이터 및 관리 영역 확인\n"
        pull = pulls[0] if pulls else github.request("pulls", "POST", {"title": "docs: sync README release", "head": BRANCH, "base": "main", "body": body})
        if pulls:
            github.request(f"pulls/{pull['number']}", "PATCH", {"body": body})
        command(["gh", "pr", "merge", str(pull["number"]), "--repo", github.repo, "--squash", "--auto", "--match-head-commit", commit["sha"]])
        state = github.request(f"pulls/{pull['number']}")
        output(readme_status="synced" if state.get("merged") else "pending", readme_pr=pull["html_url"])
        if not state.get("merged"):
            print("::warning::README PR은 필수 검사·보호 규칙 통과 후 병합됩니다. 아직 동기화가 완료되지 않았습니다.")
            summary = os.environ.get("GITHUB_STEP_SUMMARY")
            if summary:
                with open(summary, "a", encoding="utf-8") as stream:
                    stream.write(f"릴리즈 공개 완료 / README 동기화 대기: [{pull['html_url']}]({pull['html_url']})\n\n필수 검사·보호 규칙을 확인하세요. PR 병합 전에는 동기화가 완료되지 않았습니다.\n")
        return
    raise ValueError("main이 계속 변경되어 README 동기화를 완료하지 못했습니다.")


def main():
    """역할: 릴리즈 CLI를 실행한다. 입력: 명령행. 출력: 결과 또는 실패 코드."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare")
    notes = commands.add_parser("notes")
    notes.add_argument("--apk", required=True)
    notes.add_argument("--evidence", required=True)
    notes.add_argument("--output", required=True)
    publish_parser = commands.add_parser("publish")
    publish_parser.add_argument("--directory", required=True)
    commands.add_parser("readme")
    commands.add_parser("check-readme")
    commands.add_parser("pause-readme")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare()
    elif args.command == "notes":
        make_notes(manifest(), args.apk, json.loads(Path(args.evidence).read_text(encoding="utf-8")), args.output, source_sha(), repository())
    elif args.command == "publish":
        publish(args.directory)
    elif args.command == "readme":
        readme()
    elif args.command == "check-readme":
        check_readme()
    else:
        pause_readme()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired) as error:
        # API response bodies, subprocess stderr and environment values are intentionally not printed.
        print(f"릴리즈 실패: {type(error).__name__}: {error}" if isinstance(error, ValueError) else f"릴리즈 실패: {type(error).__name__}", file=sys.stderr)
        sys.exit(1)
