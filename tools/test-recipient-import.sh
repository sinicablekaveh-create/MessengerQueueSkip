#!/usr/bin/env bash
set -euo pipefail
OUT="${RUNNER_TEMP:-/tmp}/recipient-parser-test"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 -source 17 -target 17 -d "$OUT" \
  app/src/main/java/com/sinicable/messengerqueue/RecipientImportParser.java \
  tools/RecipientImportParserTest.java
java -cp "$OUT" com.sinicable.messengerqueue.RecipientImportParserTest
