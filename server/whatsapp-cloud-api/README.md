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

The adapter rejects requests unless opt-in is explicitly true. This flag is provided by the client and is not independent proof of consent; administrators must retain records of consent. This adapter supports free-form text only within the permitted customer-service window. Approved template messages, delivery webhooks, rate limits, and durable server-side idempotency are not implemented. A `SUBMITTED` response means Meta accepted the request, not that the recipient received it. Delivery confirmation requires a webhook and is not implemented here.

Deploy behind HTTPS and configure business credentials only in the server environment. The Android UI now reads the **same imported Excel/CSV contacts** as the other channels and durably records each WhatsApp row as PENDING, IN_FLIGHT, SUBMITTED, FAILED, CLEARED or REVIEW. Only explicit opted-in rows can be sent. A row is committed as IN_FLIGHT before the HTTP request; after restart, interrupted attempts become REVIEW. No automatic retry occurs for failed or uncertain attempts. The local queue survives closing the screen, but the sending runner does **not** run in the background; reopen the screen, re-enter the backend token and message, confirm consent, and press START to continue. Do not retry REVIEW recipients without verifying backend/Meta logs. The backend does not guarantee exactly-once delivery across manual retries or multiple devices.

A Meta business account, deployed backend and service token are required before sending can work. A GitHub Actions APK build is not proof of WhatsApp delivery; no real sending test was performed.
