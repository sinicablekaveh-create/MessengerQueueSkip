#!/usr/bin/env bash
set -euo pipefail

SERVICE="app/src/main/java/com/sinicable/messengerqueue/DirectQueueService.java"
ACCOUNT="app/src/main/java/com/sinicable/messengerqueue/DirectAccountActivity.java"
CLIENT="app/src/main/java/com/sinicable/messengerqueue/GatewayClient.java"

for f in "$SERVICE" "$ACCOUNT" "$CLIENT"; do
  test -s "$f"
done

grep -Fq '"/devices/"+alias+"/login"' "$ACCOUNT"
grep -Fq 'currentChallenge(provider,"awaiting_code")' "$ACCOUNT"
grep -Fq 'currentChallenge(provider,"awaiting_password")' "$ACCOUNT"
grep -Fq 'hasLiveEvidence(descriptor.optJSONObject("evidence"))' "$ACCOUNT"
grep -Fq '"/operations/account.info"' "$ACCOUNT"
grep -Fq 'contactResolveLive=hasLiveEvidence' "$ACCOUNT"
grep -Fq '"/operations/contacts.add"' "$SERVICE"
grep -Fq 'peer.put("type","user")' "$SERVICE"
grep -Fq 'guid.matches("^u0[A-Za-z0-9]{1,126}$")' "$SERVICE"
grep -Fq 'providerMessageId=reply.result().optString("message_id","")' "$SERVICE"
grep -Fq 'confirmedSendIdentity(provider,providerMessageId)' "$SERVICE"
grep -Fq 'long parsed=Long.parseLong(messageId)' "$SERVICE"
grep -Fq 'open(context,"POST","/send/image"' "$CLIENT"
grep -Fq 'formValue(out,boundary,"peer",peer.toString())' "$CLIENT"
grep -Fq 'formValue(out,boundary,"caption",caption)' "$CLIENT"
grep -Fq 'name=\"image\"; filename=\"threeib.' "$CLIENT"

echo "THREE IB direct Rubika queue contract OK"
