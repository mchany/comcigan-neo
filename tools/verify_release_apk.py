"""서명 전 APK의 버전·배포 설정과 내장 나이스 키를 검증한다."""

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath

PACKAGE = "io.github.mchany.comciganneo"
MAX_DEX_SIZE = 64 * 1024 * 1024
PRIVATE_NAMES = {"neis.properties", "local.properties", "gradle.properties", "key.properties",
                 "keystore.properties", "signing.properties", ".env"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".pem", ".key", ".p12", ".pfx"}


def parse_badging(text, version, code):
    """역할: aapt 응답이 정식 배포 앱의 설정과 일치하는지 확인한다.
    입력: aapt badging 문자열과 기대 버전·versionCode.
    출력: 정상은 없음이며 불일치·모호한 응답은 ValueError.
    """
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version):
        raise ValueError("Invalid requested release version")
    if type(code) is not int or not 1 <= code <= 2_100_000_000:
        raise ValueError("Invalid requested versionCode")
    packages = re.findall(r"^package: (.+)$", text, re.MULTILINE)
    sdks = re.findall(r"^sdkVersion:'([0-9]+)'\s*$", text, re.MULTILINE)
    if len(packages) != 1 or sdks != ["26"] or "application-debuggable" in text:
        raise ValueError("APK package, minimum SDK or debuggable state is invalid")
    fields = {}
    for name in ("name", "versionCode", "versionName"):
        values = re.findall(r"(?:^|\s)" + name + r"='([^']*)'", packages[0])
        if len(values) != 1:
            raise ValueError("APK version fields are missing or ambiguous")
        fields[name] = values[0]
    if fields != {"name": PACKAGE, "versionCode": str(code), "versionName": version}:
        raise ValueError("APK package or release version does not match")


def inspect_contents(apk, key):
    """역할: 개인 빌드 입력 유출과 나이스 키의 DEX 반영 여부를 검사한다.
    입력: APK 경로와 비어 있지 않은 출력 가능 ASCII 인증키.
    출력: 정상은 없음이며 실패 시 키를 포함하지 않는 ValueError.
    """
    if not key or any(not 33 <= ord(character) <= 126 for character in key):
        raise ValueError("NEIS_API_KEY must be nonempty printable ASCII without whitespace")
    needle = key.encode("ascii")
    found = False
    with zipfile.ZipFile(apk) as archive:
        names = set()
        for item in archive.infolist():
            normalized = item.filename.replace("\\", "/")
            path = PurePosixPath(normalized)
            if normalized in names or path.is_absolute() or ".." in path.parts:
                raise ValueError("APK contains ambiguous or unsafe ZIP entries")
            names.add(normalized)
            if path.name.lower() in PRIVATE_NAMES or path.suffix.lower() in PRIVATE_SUFFIXES:
                raise ValueError("APK contains a private build input")
            if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", normalized):
                if item.file_size > MAX_DEX_SIZE:
                    raise ValueError("APK DEX exceeds verification size limit")
                with archive.open(item) as stream:
                    data = stream.read(MAX_DEX_SIZE + 1)
                if len(data) > MAX_DEX_SIZE:
                    raise ValueError("APK DEX exceeds verification size limit")
                found = found or needle in data
    if not found:
        raise ValueError("APK does not contain the configured NEIS key in DEX")


def verify(apk, version, code):
    """역할: Android SDK 도구와 ZIP 검사로 서명 전 APK를 검증한다.
    입력: APK 경로와 기대 버전·코드, 환경의 SDK 경로·나이스 키.
    출력: 실제 수행한 검사의 공개 가능한 증거 사전.
    """
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("ANDROID_HOME is required")
    aapt = Path(sdk) / "build-tools" / "35.0.0" / ("aapt.exe" if os.name == "nt" else "aapt")
    result = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=60, check=False)
    if result.returncode:
        raise ValueError("aapt metadata verification failed")
    parse_badging(result.stdout, version, code)
    inspect_contents(apk, os.environ.get("NEIS_API_KEY", ""))
    return {"checks": ["APK 앱 ID·버전·versionCode 및 Android 8.0 이상 설정 확인",
                       "비디버그 정식 패키지 확인", "설정된 나이스 인증키의 DEX 반영 확인",
                       "APK 내 개인 빌드 입력 파일 미포함 확인"]}


def main():
    """역할: APK 검증 결과를 JSON 파일로 기록한다.
    입력: APK·버전·코드·출력 경로 CLI 인수.
    출력: 성공 0, 키 원문을 출력하지 않는 실패 1.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        evidence = verify(args.apk, args.version, args.code)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("APK verification passed")
        return 0
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.SubprocessError):
        print("APK verification failed; check metadata, private inputs, SDK and NEIS key configuration", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
