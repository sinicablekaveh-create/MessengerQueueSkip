import { createServer } from "node:http";

const port = Number(process.env.PORT || 8080);
const graphVersion = process.env.META_GRAPH_VERSION;
const phoneNumberId = process.env.WHATSAPP_PHONE_NUMBER_ID;
const accessToken = process.env.WHATSAPP_ACCESS_TOKEN;
const serviceToken = process.env.THREE_IB_SERVICE_TOKEN;

function sendJson(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
}

async function readJson(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > 32_768) throw new Error("Request body too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

const server = createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/v1/whatsapp/messages") {
    sendJson(response, 404, { error: "not_found" });
    return;
  }

  if (!serviceToken || request.headers.authorization !== `Bearer ${serviceToken}`) {
    sendJson(response, 401, { error: "unauthorized" });
    return;
  }

  if (!graphVersion || !phoneNumberId || !accessToken) {
    sendJson(response, 503, { error: "whatsapp_business_not_configured" });
    return;
  }

  let payload;
  try {
    payload = await readJson(request);
  } catch {
    sendJson(response, 400, { error: "invalid_json_or_body_too_large" });
    return;
  }

  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    sendJson(response, 400, { error: "json_object_required" });
    return;
  }
  const recipient = typeof payload.recipient === "string" ? payload.recipient.trim() : "";
  const text = typeof payload.text === "string" ? payload.text.trim() : "";
  if (payload.optIn !== true || !recipient || !/^\+[1-9][0-9]{7,14}$/.test(recipient)
      || !text || text.length > 4096) {
    sendJson(response, 400, { error: "valid_recipient_text_and_opt_in_required" });
    return;
  }

  const url = `https://graph.facebook.com/${encodeURIComponent(graphVersion)}/${encodeURIComponent(phoneNumberId)}/messages`;
  let metaResponse;
  try {
    metaResponse = await fetch(url, {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json"
      },
      body: JSON.stringify({
        messaging_product: "whatsapp",
        recipient_type: "individual",
        to: recipient.slice(1),
        type: "text",
        text: { body: text }
      }),
      signal: AbortSignal.timeout(20_000)
    });
  } catch {
    sendJson(response, 502, { error: "meta_unreachable", status: "REVIEW" });
    return;
  }

  const result = await metaResponse.json().catch(() => ({}));
  if (!metaResponse.ok) {
    sendJson(response, 502, {
      error: "meta_rejected_request",
      status: metaResponse.status >= 500 ? "REVIEW" : "FAILED"
    });
    return;
  }

  const messageId = result?.messages?.[0]?.id;
  if (typeof messageId !== "string" || !messageId) {
    sendJson(response, 502, { error: "meta_response_missing_message_id", status: "REVIEW" });
    return;
  }

  sendJson(response, 202, { status: "SUBMITTED", messageId });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`WhatsApp Cloud API adapter listening on port ${port}`);
});
