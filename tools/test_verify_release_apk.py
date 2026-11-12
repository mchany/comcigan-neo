"""배포 APK 검사기의 합성 ZIP·aapt 회귀 검사."""

import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_release_apk import inspect_contents, parse_badging


class ApkVerificationTest(unittest.TestCase):
    """실제 자격 증명 없이 APK 정책을 검사한다."""

    def badging(self, package="io.github.mchany.comciganneo", version="1.1.1", code="17", sdk="26"):
        """역할: 합성 aapt 응답을 만든다.
        입력: 패키지·버전·지원 SDK.
        출력: 비디버그 APK 메타데이터 문자열.
        """
        return f"package: name='{package}' versionCode='{code}' versionName='{version}' platformBuildVersionName='15'\nsdkVersion:'{sdk}'\ntargetSdkVersion:'35'\n"

    def test_accepts_matching_metadata(self):
        """역할: 정상 APK 메타데이터를 허용한다.
        입력: 일치하는 패키지·버전·SDK.
        출력: 예외 없이 검증 통과.
        """
        parse_badging(self.badging(), "1.1.1", 17)

    def test_rejects_package_version_sdk_and_debug_mismatch(self):
        """역할: 잘못된 배포 APK 메타데이터를 거부한다.
        입력: 시험 패키지·다른 버전·잘못된 SDK·디버그 플래그.
        출력: 모든 변형이 ValueError로 중단.
        """
        for value in [self.badging(package="io.github.mchany.comciganneo.smoke"),
                      self.badging(version="1.1.0"), self.badging(code="16"),
                      self.badging(sdk="25"), self.badging() + "application-debuggable\n",
                      self.badging() + self.badging(), "not apk metadata"]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    parse_badging(value, "1.1.1", 17)

    def test_rejects_invalid_requested_version(self):
        """역할: 호출자 버전 입력의 모호함을 거부한다.
        입력: 선행 0·범위 밖 코드·문자열 코드.
        출력: ValueError.
        """
        for version, code in [("01.1.1", 17), ("1.1", 17), ("1.1.1", 0), ("1.1.1", True), ("1.1.1", "17")]:
            with self.assertRaises(ValueError):
                parse_badging(self.badging(), version, code)

    def make_apk(self, directory, entries):
        """역할: 검사에 사용할 합성 APK를 저장한다.
        입력: 임시 경로와 ZIP 항목 사전.
        출력: 저장한 APK 경로.
        """
        path = Path(directory) / "synthetic.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        return path

    def test_accepts_embedded_key_in_secondary_dex(self):
        """역할: 여러 DEX 중 정확한 키가 있으면 허용한다.
        입력: 비밀값이 아닌 고정 합성 키와 APK.
        출력: 예외 없이 검증 통과.
        """
        with tempfile.TemporaryDirectory() as directory:
            apk = self.make_apk(directory, {"classes.dex": b"dex header", "classes2.dex": b"dex\0synthetic-key\0"})
            inspect_contents(apk, "synthetic-key")

    def test_rejects_missing_key_and_sensitive_inputs(self):
        """역할: 키 누락과 개인 빌드 입력 유출을 차단한다.
        입력: 키가 없는 DEX 또는 개인 입력 파일을 포함한 ZIP.
        출력: 키 원문 없는 ValueError.
        """
        with tempfile.TemporaryDirectory() as directory:
            for name in ["assets/neis.properties", "assets/LOCAL.PROPERTIES", "keys/release.jks",
                         "keys/secret.p12", "assets/signing.properties", "../escape", "assets\\private.key"]:
                apk = self.make_apk(directory, {"classes.dex": b"synthetic-key", name: b"private"})
                with self.assertRaises(ValueError) as context:
                    inspect_contents(apk, "synthetic-key")
                self.assertNotIn("synthetic-key", str(context.exception))
            apk = self.make_apk(directory, {"classes.dex": b"unrelated"})
            with self.assertRaises(ValueError):
                inspect_contents(apk, "synthetic-key")

    def test_rejects_blank_control_and_non_ascii_key(self):
        """역할: 빌드 문자열과 일치할 수 없는 키 입력을 거부한다.
        입력: 빈 문자열·공백·제어문자·비ASCII 키.
        출력: 모든 입력이 ValueError.
        """
        with tempfile.TemporaryDirectory() as directory:
            apk = self.make_apk(directory, {"classes.dex": b"dex"})
            for key in ["", " ", "value\n", "value\r", "한글"]:
                with self.assertRaises(ValueError):
                    inspect_contents(apk, key)


if __name__ == "__main__":
    unittest.main()
