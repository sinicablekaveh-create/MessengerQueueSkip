# THREE IB WhatsApp Cloud API adapter

This small server-side adapter keeps the Meta access token out of the Android APK. It accepts one opted-in recipient and text message, forwards it to Meta's WhatsApp Business Cloud API, and reports submission separately from delivery.

## Required environment

- `PORT` optional, defaults to 8080
- `META_GRAPH_VERSION` required, set to the Graph API version enabled for the Meta app
- `WHATSAPP_PHONE_NUMBER_ID` required
- `WHATSAPP_ACCESS_TOKEN` required, store as a deployment secret
- `THREE_IB_SERVICE_TOKEN` required, a separate high-entropy token for authenticating THREE IB to this service

Run with Node.js 20 or newer:

```sh
node server.mjs
```

## Request

Send an HTTPS POST to `/v1/whatsapp/messages` with an Authorization bearer token matching `THREE_IB_SERVICE_TOKEN`:

```json
{
  "recipient": "+15551234567",
  "text": "Your message",
  "optIn": true
}
```

The adapter rejects requests unless opt-in is explicitly true. It currently supports text only. Cloud API template requirements and the 24-hour customer-service window still apply. A `SUBMITTED` response means Meta accepted the request, not that the recipient received it. Delivery confirmation requires a webhook and is not implemented here.

Deploy behind HTTPS, restrict access to the Android client or an authenticated gateway, and configure business credentials only in the server environment. Do not put either token in the Android app, GitHub, or chat.

This is a server component. THREE IB's Android UI is not yet connected to this endpoint, and a Meta business account and deployment configuration are needed before it can send.
