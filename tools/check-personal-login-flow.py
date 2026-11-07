#!/usr/bin/env python3
"""Fail the build if a personal-account APK loses its in-app login or queue wiring.

This is a static regression test, NOT proof of live OTP, account authentication
or delivery. Provider compatibility must be verified on an actual device.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/sinicable/messengerqueue"

def need(file: str, *patterns: str) -> None:
    source = (JAVA / file).read_text(encoding="utf-8")
    for pattern in patterns:
        if not re.search(pattern, source, flags=re.DOTALL):
            raise SystemExit(f"FAIL {file}: missing {pattern!r}")
    print(f"PASS {file}")

def main() -> None:
    # Explicit in-APK sign-in for three independent personal account providers.
    need("DirectAccountActivity.java",
         r"PROVIDERS\s*=\s*\{[^}]*bale[^}]*rubika[^}]*eitaa",
         r"/devices/.*?/login",
         r"/devices/.*?/login/code",
         r"/devices/.*?/login/password",
         r"authenticated", r"connected",
         r"RecipientImportParser\.read",
         r"QueueDb\.append",
         r"startForegroundService\(service\)")
    need("DirectQueueService.java",
         r"QueueDb\.next", r"QueueDb\.delayMinutes",
         r"directBegin", r"directFinish",
         r"/send/message", r"sendImage",
         r"opt", r"review")
    # Telegram must use TDLib and only start when a session is ready.
    need("TelegramActivity.java",
         r"engine\.login", r"RecipientImportParser\.read",
         r"engine\.replace", r"engine\.start")
    need("TelegramEngine.java",
         r"SetAuthenticationPhoneNumber",
         r"CheckAuthenticationCode",
         r"CheckAuthenticationPassword",
         r"if\(!ready\)", r"ImportContacts",
         r"CreatePrivateChat", r"SendMessage",
         r"UpdateMessageSendSucceeded",
         r"UpdateMessageSendFailed")
    need("RecipientImportParser.java", r"\.xlsx", r"opt_in",
         r"if\(!phone\|\|!optIn\)", r"duplicateRows")
    need("QueueDb.java", r"opt_in", r"blocked", r"pending", r"review")
    print("PASS static personal-login -> Excel -> queue -> send wiring")
    print("WARNING: no live OTP, provider session or message delivery was tested.")

if __name__ == "__main__":
    main()
