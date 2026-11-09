#!/usr/bin/env bash
set -euo pipefail

GOOMNI="${1:?usage: test-rubika-runtime-contract.sh /path/to/goomni}"
CLIENT="$GOOMNI/src/internal/rubikameow/client.go"
SERVER="$GOOMNI/src/ui/rest/server.go"
MULTIPART="$GOOMNI/src/ui/rest/multipart.go"
SEND_CONTRACT="$GOOMNI/src/ui/rest/send_contract.go"
CONTRACT="$GOOMNI/src/infrastructure/providers/rubika/contract.go"
EXTENDED="$GOOMNI/src/internal/rubikameow/extended.go"
EVIDENCE="$GOOMNI/src/infrastructure/providers/evidence/records.json"

for f in "$CLIENT" "$SERVER" "$MULTIPART" "$SEND_CONTRACT" "$CONTRACT" "$EXTENDED" "$EVIDENCE"; do
  test -s "$f"
done

grep -Fq 'State = "awaiting_password"' "$CLIENT"
grep -Fq 'State = "awaiting_code"' "$CLIENT"
grep -Fq 'func (c *Client) SubmitCode' "$CLIENT"
grep -Fq 'func (c *Client) SubmitPassword' "$CLIENT"
grep -Fq 'r.Post("/operations/:operation"' "$SERVER"
grep -Fq 'r.Get("/send/operations/:send_id"' "$SERVER"
grep -Fq 'fileField := kind' "$MULTIPART"
grep -Fq 'fields["caption"]' "$SEND_CONTRACT"
grep -Fq 'case "contacts.add":' "$EXTENDED"
grep -Fq 'method = "addAddressBook"' "$EXTENDED"
grep -Fq 'MaxTextCharacters: 4200' "$CONTRACT"

python3 - "$EVIDENCE" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    r=json.load(f)["rubika"]
assert r["provider"]["level"] == "live_scoped"
assert r["send"]["text"]["level"] == "live_scoped"
assert r["send"]["image"]["level"] == "live_scoped"
assert r["operations"]["contacts.add"]["level"] in {"live_response","live_scoped"}
print("Rubika upstream runtime contract OK")
PY
