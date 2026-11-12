"""릴리즈 계약의 회귀 테스트."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.request

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    """합성 입력으로 릴리즈 경계를 검증한다."""

    def test_trigger(self):
        """역할: 명시적 커밋만 허용한다. 입력: 커밋 제목. 출력: 버전 또는 None."""
        self.assertEqual("1.1.1", release.trigger_version("chore: release 1.1.1 (#20)\n\nbody"))
        for value in ["chore: dependencies", "fix: chore: release 1.1.1", "chore: realese 1.1.1"]:
            self.assertIsNone(release.trigger_version(value))
        for value in ["chore: release 01.1.1", "chore: release 1.2.3; echo bad"]:
            with self.assertRaises(ValueError):
                release.trigger_version(value)

    def test_manifest_and_invalid_types(self):
        """역할: TOML 필수값을 엄격히 검사한다. 입력: 합성 TOML. 출력: 정상 버전 또는 오류."""
        valid = '[release]\nversion="1.1.1"\nversion_code=17\nsummary="수정"\nchanges=["검색 수정"]\n'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.toml"
            path.write_text(valid, encoding="utf-8")
            self.assertEqual(17, release.manifest(path)["version_code"])
            for text in [valid.replace("17", "true"), valid.replace('["검색 수정"]', "[]"), valid + 'version="1.1.2"\n']:
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    release.manifest(path)

    def test_version_progress(self):
        """역할: 버전 역행과 Android 코드 재사용을 차단한다. 입력: 버전쌍. 출력: 정상 또는 오류."""
        release.validate_progress("1.1.1", 17, "1.1.0", 16)
        for version, code in [("1.1.0", 17), ("1.0.9", 18), ("1.2.0", 16)]:
            with self.assertRaises(ValueError):
                release.validate_progress(version, code, "1.1.0", 16)

    def test_readme_markers_and_body_preservation(self):
        """역할: 배포 영역만 바꾸고 본문을 보존한다. 입력: README와 메타데이터. 출력: 수정 문서."""
        data = {"version": "1.1.1", "summary": "검색 수정", "repository": "mchany/comcigan-neo"}
        original = "앞\n<!-- release:start -->\n옛 내용\n<!-- release:end -->\n뒤 1.1.0\n"
        updated = release.render_readme(original, data)
        self.assertTrue(updated.startswith("앞\n"))
        self.assertTrue(updated.endswith("뒤 1.1.0\n"))
        self.assertIn("releases/download/v1.1.1/comcigan-neo-1.1.1.apk", updated)
        self.assertNotIn("설정과 데이터가 유지됩니다", updated)
        for text in ["no markers", original + "<!-- release:start -->"]:
            with self.assertRaises(ValueError):
                release.render_readme(text, data)

    def test_notes_require_evidence_and_do_not_copy_device_claims(self):
        """역할: 실행 근거로만 설명을 생성한다. 입력: 합성 APK와 근거. 출력: 설명과 체크섬."""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / "input.apk"
            apk.write_bytes(b"synthetic APK")
            data = {"version": "1.1.1", "version_code": 17, "summary": "검색 수정", "changes": ["검색 처리 개선"]}
            evidence = {"checks": ["JVM 테스트 통과"], "certificate_sha256": "AB" * 32}
            release.make_notes(data, apk, evidence, root / "out", "a" * 40, "mchany/comcigan-neo")
            notes = (root / "out/release-notes.md").read_text(encoding="utf-8")
            self.assertIn("JVM 테스트 통과", notes)
            self.assertNotIn("A33", notes)
            self.assertIn("미수행", notes)
            metadata = json.loads((root / "out/release-metadata.json").read_text(encoding="utf-8"))
            self.assertEqual("a" * 40, metadata["source_sha"])
            with self.assertRaises(ValueError):
                release.make_notes(data, apk, {}, root / "bad", "a" * 40, "mchany/comcigan-neo")

    def test_redirect_drops_authorization(self):
        """역할: 첨부 호스트에 토큰을 보내지 않는다. 입력: 인증 요청. 출력: 인증 없는 리다이렉트."""
        request = urllib.request.Request("https://api.github.com/test", headers={"Authorization": "Bearer synthetic"})
        redirected = release.SafeRedirect().redirect_request(request, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/file")
        self.assertFalse(redirected.has_header("Authorization"))
        with self.assertRaises(ValueError):
            release.SafeRedirect().redirect_request(request, None, 302, "Found", {}, "https://evil.example/file")

    def test_ordinary_commit_does_not_access_secrets_or_api(self):
        """역할: 일반 커밋의 배포를 생략한다. 입력: 일반 커밋. 출력: false이며 API 호출 없음."""
        with patch.object(release, "source_sha", return_value="a" * 40), patch.object(release, "command", return_value="chore: dependencies"), patch.object(release, "GitHub") as api, patch.object(release, "output") as result, patch.dict(release.os.environ, {"GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": ""}):
            release.prepare()
            api.assert_not_called()
            result.assert_called_once_with(trigger="false")

    def test_publish_draft_and_immutable_reentry(self):
        """역할: 검증 후 공개하고 재실행에서 기존 첨부를 보존한다. 입력: 합성 산출물. 출력: 한 번 공개."""
        data = {"version": "1.1.1", "version_code": 17, "summary": "검색 수정", "changes": ["검색 개선"]}
        evidence = {"checks": ["APK 검증 통과"], "certificate_sha256": "AB" * 32}
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / "input.apk"
            apk.write_bytes(b"synthetic")
            release.make_notes(data, apk, evidence, root / "out", "a" * 40, "mchany/comcigan-neo")
            remote = {"id": 1, "draft": True, "assets": [], "html_url": "https://github.com/mchany/comcigan-neo/releases/tag/v1.1.1"}
            assets = {}
            mutations = []

            def request(path, method="GET", payload=None, **kwargs):
                """역할: 원격 릴리즈 저장소를 흉내낸다. 입력: API 요청. 출력: 저장된 상태."""
                if path == "releases/latest":
                    return None
                mutations.append((path, method))
                if path.startswith("https://uploads.github.com/"):
                    assets[path.split("name=", 1)[1]] = payload
                    return {}
                if path == "releases/1" and method == "PATCH":
                    remote["draft"] = False
                    return remote
                raise AssertionError(path)

            with patch.object(release, "manifest", return_value=data), patch.object(release, "source_sha", return_value="a" * 40), patch.object(release, "repository", return_value="mchany/comcigan-neo"), patch.object(release, "GitHub") as api, patch.object(release, "output"):
                client = api.return_value
                client.tag_sha.return_value = "a" * 40
                client.release.return_value = remote
                client.request.side_effect = request
                client.asset.side_effect = lambda item, name: assets.get(name)
                release.publish(root / "out")
                self.assertFalse(remote["draft"])
                self.assertEqual(3, len(assets))
                mutations.clear()
                release.publish(root / "out")
                self.assertEqual([], mutations)
                assets["comcigan-neo-1.1.1.apk"] = b"different"
                with self.assertRaises(ValueError):
                    release.publish(root / "out")
                self.assertEqual([], mutations)

    def test_latest_without_new_metadata_is_rejected(self):
        """역할: 새 배포 이력의 메타데이터 누락을 거부한다. 입력: 최신 릴리즈. 출력: 오류."""
        with patch.object(release, "GitHub") as api:
            client = api.return_value
            client.request.return_value = {"tag_name": "v1.2.0", "assets": []}
            client.asset.return_value = None
            with self.assertRaisesRegex(ValueError, "메타데이터"):
                release.validate_latest(client, {"version": "1.3.0", "version_code": 30})

    def test_check_readme_rejects_stale_pr(self):
        """역할: 오래된 README PR의 병합을 차단한다. 입력: 과거 안내. 출력: 오류."""
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "README.md"
            path.write_text("<!-- release:start -->\nold\n<!-- release:end -->\n", encoding="utf-8")
            meta = {"version": "1.1.1", "summary": "수정", "repository": "mchany/comcigan-neo"}
            with patch.object(release, "GitHub") as api:
                api.return_value.repo = "mchany/comcigan-neo"
                api.return_value.request.return_value = {"tag_name": "v1.1.1"}
                api.return_value.asset.return_value = json.dumps(meta).encode()
                with self.assertRaisesRegex(ValueError, "최신"):
                    release.check_readme(path)

    def test_pause_readme_disables_only_enabled_auto_merge(self):
        """역할: 새 배포 전 과거 PR 자동 병합을 멈춘다. 입력: 기존 PR. 출력: 자동 병합 해제."""
        with patch.object(release, "GitHub") as api, patch.object(release, "command") as command:
            api.return_value.repo = "mchany/comcigan-neo"
            api.return_value.request.return_value = [{"number": 25, "auto_merge": {"enabled": True}}]
            release.pause_readme()
            self.assertIn("--disable-auto", command.call_args.args[0])
            command.reset_mock()
            api.return_value.request.return_value = [{"number": 25, "auto_merge": None}]
            release.pause_readme()
            command.assert_not_called()

    def test_readme_noop_and_missing_markers(self):
        """역할: 일치 문서에는 쓰지 않고 불명확한 영역을 거부한다. 입력: 원격 README. 출력: synced 또는 오류."""
        meta = {"version": "1.1.1", "summary": "수정", "repository": "mchany/comcigan-neo"}
        original = "앞\n<!-- release:start -->\nold\n<!-- release:end -->\n뒤\n"
        with patch.object(release, "GitHub") as api, patch.object(release, "output") as output:
            client = api.return_value
            client.repo = meta["repository"]
            client.asset.return_value = json.dumps(meta).encode()
            current = release.render_readme(original, meta)

            def request(path, *args, **kwargs):
                """역할: 읽기 전용 API를 모사한다. 입력: 조회 경로. 출력: 고정 문서."""
                if path == "releases/latest":
                    return {"id": 1, "tag_name": "v1.1.1"}
                if path == "git/ref/heads/main":
                    return {"object": {"sha": "a" * 40}}
                if path.startswith("contents/README.md"):
                    return {"content": release.base64.b64encode(current.encode()).decode()}
                raise AssertionError(path)

            client.request.side_effect = request
            release.readme()
            output.assert_called_once_with(readme_status="synced")
            current = "missing markers"
            with self.assertRaisesRegex(ValueError, "마커"):
                release.readme()

    def test_readme_pending_preserves_latest_main(self):
        """역할: main 경합 후 최신 본문을 보존하고 PR 대기를 알린다. 입력: 변하는 main. 출력: pending."""
        meta = {"version": "1.1.1", "summary": "수정", "repository": "mchany/comcigan-neo"}
        original = "앞\n<!-- release:start -->\nold\n<!-- release:end -->\n동시 수정 보존\n"
        with patch.object(release, "GitHub") as api, patch.object(release, "output") as output, patch.object(release, "command"), patch.dict(release.os.environ, {"GITHUB_STEP_SUMMARY": ""}):
            client = api.return_value
            client.repo = meta["repository"]
            client.asset.return_value = json.dumps(meta).encode()
            main_reads = []
            trees = []

            def request(path, method="GET", payload=None, **kwargs):
                """역할: 경합과 PR 대기 API를 모사한다. 입력: 요청. 출력: 최신 main과 PR."""
                if path == "releases/latest":
                    return {"id": 1, "tag_name": "v1.1.1"}
                if path == "git/ref/heads/main":
                    main_reads.append(1)
                    return {"object": {"sha": "a" * 40 if len(main_reads) == 1 else "b" * 40}}
                if path.startswith("contents/README.md"):
                    return {"content": release.base64.b64encode(original.encode()).decode()}
                if path.startswith("git/ref/heads/"):
                    return {"object": {"sha": "c" * 40}}
                if path.startswith("git/commits/"):
                    return {"tree": {"sha": "tree"}}
                if path == "git/trees":
                    trees.append(payload)
                    return {"sha": "newtree"}
                if path == "git/commits":
                    return {"sha": "d" * 40}
                if path.startswith("git/refs/heads/"):
                    return {}
                if path.startswith("pulls?"):
                    return []
                if path == "pulls" or path == "pulls/25":
                    return {"number": 25, "html_url": "https://github.com/mchany/comcigan-neo/pull/25", "merged": False}
                raise AssertionError(path)

            client.request.side_effect = request
            release.readme()
            self.assertEqual(2, len(trees))
            self.assertIn("동시 수정 보존", trees[-1]["tree"][0]["content"])
            output.assert_called_once_with(readme_status="pending", readme_pr="https://github.com/mchany/comcigan-neo/pull/25")


if __name__ == "__main__":
    unittest.main()
