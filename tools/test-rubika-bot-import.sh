#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" app/src/main/java/com/sinicable/messengerqueue/RubikaBotImportParser.java tools/RubikaBotImportParserTest.java
java -cp "$TMP" com.sinicable.messengerqueue.RubikaBotImportParserTest
