#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP_BUILD = ROOT / "app" / "build.gradle.kts"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = "{" + ANDROID_NS + "}"
ET.register_namespace("android", ANDROID_NS)

CHANNELS = {
    "bale": ("com.sinicable.threeib.bale", "THREE IB - Bale"),
    "rubika": ("com.sinicable.threeib.rubika", "THREE IB - Rubika"),
    "eitaa": ("com.sinicable.threeib.eitaa", "THREE IB - Eitaa"),
    "telegram": ("com.sinicable.threeib.telegram", "THREE IB - Telegram"),
    "sms": ("com.sinicable.threeib.sms", "THREE IB - SMS"),
    "whatsapp": ("com.sinicable.threeib.whatsapp", "THREE IB - WhatsApp Business"),
}

def read_version() -> tuple[int, str]:
    text = APP_BUILD.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        raise RuntimeError("Unable to read version from app/build.gradle.kts")
    return int(code.group(1)), name.group(1)

def root_build_gradle() -> str:
    return '''plugins {
    id("com.android.application") version "8.7.3" apply false
}
'''

def settings_gradle(channel: str) -> str:
    return f'''pluginManagement {{
    repositories {{ google(); mavenCentral(); gradlePluginPortal() }}
}}
dependencyResolutionManagement {{
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {{ google(); mavenCentral() }}
}}
rootProject.name = "THREE_IB_{channel.upper()}"
include(":app")
'''

def app_build_gradle(channel: str, package_id: str, label: str, version_code: int, version_name: str) -> str:
    quoted_channel = '\\"' + channel + '\\"'
    return f'''plugins {{ id("com.android.application") }}

android {{
    namespace = "com.sinicable.messengerqueue"
    compileSdk = 35

    defaultConfig {{
        applicationId = "{package_id}"
        minSdk = 26
        targetSdk = 35
        versionCode = {version_code}
        versionName = "{version_name}"
        manifestPlaceholders["appLabel"] = "{label}"
        buildConfigField("String", "TARGET_CHANNEL", "{quoted_channel}")
        ndk {{ abiFilters.add("arm64-v8a") }}
    }}

    buildFeatures {{ buildConfig = true }}
    packaging {{
        jniLibs {{
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libgoomni.so")
        }}
    }}

    buildTypes {{
        release {{ isMinifyEnabled = false }}
    }}

    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}
}}

dependencies {{
    implementation("io.github.tdlib-android:core:0.1.1")
}}
'''

def patch_manifest(manifest: Path, channel: str) -> None:
    tree = ET.parse(manifest)
    root = tree.getroot()
    app = root.find("application")
    if app is None:
        raise RuntimeError(f"No application node in {manifest}")

    if channel in {"bale", "rubika", "eitaa"}:
        app.set(ANDROID + "usesCleartextTraffic", "true")

    if channel == "sms":
        existing = {
            node.get(ANDROID + "name")
            for node in root.findall("uses-permission")
        }
        for permission in (
            "android.permission.SEND_SMS",
            "android.permission.READ_PHONE_STATE",
        ):
            if permission not in existing:
                node = ET.Element("uses-permission")
                node.set(ANDROID + "name", permission)
                root.insert(0, node)

    tree.write(manifest, encoding="utf-8", xml_declaration=True)

def write_readme(out_dir: Path, channel: str, package_id: str, version_name: str) -> None:
    text = f"""# THREE IB {channel.upper()} standalone

Independent Android project for the {channel} channel.

- Package: {package_id}
- Version: {version_name}
- Fixed TARGET_CHANNEL: {channel}
- Build: gradle :app:assembleDebug
- Recipient rows require opt_in=true.
- SMS also requires send_sms=true.

This project does not launch or screen-control another installed messenger.
"""
    (out_dir / "README.md").write_text(text, encoding="utf-8")

def make_one(channel: str, base_output: Path) -> Path:
    package_id, label = CHANNELS[channel]
    version_code, version_name = read_version()
    out_dir = base_output / channel

    if out_dir.exists():
        shutil.rmtree(out_dir)
    (out_dir / "app" / "src").mkdir(parents=True)

    shutil.copytree(
        ROOT / "app" / "src" / "main",
        out_dir / "app" / "src" / "main",
    )
    shutil.copy2(ROOT / "gradle.properties", out_dir / "gradle.properties")

    (out_dir / "build.gradle.kts").write_text(root_build_gradle(), encoding="utf-8")
    (out_dir / "settings.gradle.kts").write_text(settings_gradle(channel), encoding="utf-8")
    (out_dir / "app" / "build.gradle.kts").write_text(
        app_build_gradle(channel, package_id, label, version_code, version_name),
        encoding="utf-8",
    )
    patch_manifest(out_dir / "app" / "src" / "main" / "AndroidManifest.xml", channel)
    write_readme(out_dir, channel, package_id, version_name)
    return out_dir

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        default=str(ROOT / "standalone"),
        help="Directory that receives one project per channel.",
    )
    parser.add_argument(
        "channels",
        nargs="*",
        choices=sorted(CHANNELS),
        default=list(CHANNELS),
    )
    args = parser.parse_args()

    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    channels = args.channels or list(CHANNELS)
    for channel in channels:
        made = make_one(channel, output)
        print(f"{channel}: {made}")

if __name__ == "__main__":
    main()
