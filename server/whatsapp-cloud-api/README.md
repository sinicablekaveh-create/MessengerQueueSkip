# THREE IB WhatsApp Cloud API adapter

This server-side adapter keeps the Meta access token in server environment variables. The Android app's separate WhatsApp launcher screen sends opted-in recipients and text to this service over HTTPS.

## Required environment

- `PORT` optional, defaults to 8080
- `META_GRAPH_VERSION` required, set to the Graph API version enabled for the Meta app
- `WHATSAPP_PHONE_NUMBER_ID` required
- `WHATSAPP_ACCESS_TOKEN` required, store as a deployment secret
- `THREE_IB_SERVICE_TOKEN` required, a separate high-entropy token for authenticating the Android client to this service

Run with Node.js 20 or newer:

```sh
node server.mjs
```

## Request

The Android app requires the full HTTPS endpoint URL and the service token at runtime. The token is held in memory for the active screen and is not stored in the APK or app preferences. Send a POST to `/v1/whatsapp/messages`:

```json
{
  "recipient": "+15551234567",
  "text": "Your message",
  "optIn": true
}
```

The adapter rejects requests unless opt-in is explicitly true. It currently supports text only. Cloud API template requirements and the 24-hour customer-service window still apply. A `SUBMITTED` response means Meta accepted the request, not that the recipient received it. Delivery confirmation requires a webhook and is not implemented here.

Deploy behind HTTPS and configure business credentials only in the server environment. The Android queue currently runs while its WhatsApp screen stays open. Its recipient list and progress are held in memory, so closing the screen clears the active run. A network timeout is marked for manual review because the request may already have been accepted. Do not retry that recipient until the backend or Meta logs have been checked.

A Meta business account, deployed backend and service token are required before sending can work.
