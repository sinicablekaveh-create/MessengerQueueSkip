#!/usr/bin/env python3
from pathlib import Path

path = Path("src/internal/rubikameow/client.go")
text = path.read_text(encoding="utf-8")

# Match current web-client request headers more closely.
old = '''request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Origin", "https://web.rubika.ir")'''
new = '''request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Origin", "https://m.rubika.ir")
	request.Header.Set("Referer", "https://m.rubika.ir/")
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/102.0.0.0 Safari/537.36")'''
if old not in text:
    raise SystemExit("postJSON header block not found")
text = text.replace(old, new, 1)

start = text.index("func (c *Client) discover(ctx context.Context) error {")
end = text.index("\nfunc (c *Client) invoke(", start)

replacement = r'''func (c *Client) discoveryGET(ctx context.Context) (object, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.cfg.DiscoveryEndpoint, nil)
	if err != nil {
		return nil, protocolError()
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Origin", "https://m.rubika.ir")
	request.Header.Set("Referer", "https://m.rubika.ir/")
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/102.0.0.0 Safari/537.36")
	response, err := c.http.Do(request)
	if err != nil {
		return nil, &domains.Error{Code: "PROVIDER_TRANSPORT_ERROR", Message: "Rubika discovery request did not complete", HTTP: 502, Retryable: true}
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, &domains.Error{Code: "PROVIDER_HTTP_ERROR", Message: "Rubika discovery returned an unsuccessful HTTP response", HTTP: 502}
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxPayload+1))
	if err != nil || len(body) > maxPayload {
		return nil, protocolError()
	}
	return jsonObject(body)
}

func pickDiscoveryEndpoint(data object) (string, string, bool) {
	var api, socket string

	// Newer web discovery shape: data.API / data.socket maps plus default codes.
	if apis := asObject(data["API"]); apis != nil {
		if code, ok := data["default_api"].(string); ok {
			if v, ok := apis[code].(string); ok { api = v }
		}
		if api == "" {
			if list, ok := data["default_apis"].([]any); ok {
				for _, item := range list {
					code, ok := item.(string); if !ok { continue }
					if v, ok := apis[code].(string); ok { api = v; break }
				}
			}
		}
		if api == "" {
			for _, value := range apis {
				if v, ok := value.(string); ok && v != "" { api = v; break }
			}
		}
	}
	if sockets := asObject(data["socket"]); sockets != nil {
		if code, ok := data["default_socket"].(string); ok {
			if v, ok := sockets[code].(string); ok { socket = v }
		}
		if socket == "" {
			for _, value := range sockets {
				if v, ok := value.(string); ok && v != "" { socket = v; break }
			}
		}
	}

	// Older GoOmni-compatible discovery shape.
	if api == "" {
		if urls, ok := data["default_api_urls"].([]any); ok {
			for _, item := range urls {
				if v, ok := item.(string); ok && v != "" { api = v; break }
			}
		}
	}
	if socket == "" {
		if sockets, ok := data["default_sockets"].([]any); ok {
			for _, item := range sockets {
				if v, ok := item.(string); ok && v != "" { socket = v; break }
			}
		}
	}
	return api, socket, api != "" && socket != ""
}

func (c *Client) discover(ctx context.Context) error {
	c.discoveryMu.Lock()
	defer c.discoveryMu.Unlock()
	c.mu.RLock()
	ready := c.api != "" && c.socket != ""
	c.mu.RUnlock()
	if ready { return nil }

	// Current Rubika web clients fetch discovery with GET.
	response, getErr := c.discoveryGET(ctx)
	if getErr == nil {
		if data := asObject(response["data"]); data != nil {
			if endpoint, socket, ok := pickDiscoveryEndpoint(data); ok &&
				validEndpoint(endpoint, false, c.cfg.HTTPClient != nil) &&
				validEndpoint(socket, true, c.cfg.HTTPClient != nil) {
				c.mu.Lock()
				if c.api == "" { c.api = endpoint }
				if c.socket == "" { c.socket = socket }
				c.mu.Unlock()
				return nil
			}
		}
	}

	// Compatibility fallback for older discovery behavior.
	legacy, err := c.postJSON(ctx, c.cfg.DiscoveryEndpoint,
		object{"api_version": "4", "client": clientIdentity(), "method": "getDCs"}, false)
	if err != nil {
		if getErr != nil { return getErr }
		return err
	}
	data := asObject(legacy["data"])
	if data == nil { return protocolError() }
	endpoint, socket, ok := pickDiscoveryEndpoint(data)
	if !ok || !validEndpoint(endpoint, false, c.cfg.HTTPClient != nil) ||
		!validEndpoint(socket, true, c.cfg.HTTPClient != nil) {
		return protocolError()
	}
	c.mu.Lock()
	if c.api == "" { c.api = endpoint }
	if c.socket == "" { c.socket = socket }
	c.mu.Unlock()
	return nil
}
'''

text = text[:start] + replacement + text[end:]
path.write_text(text, encoding="utf-8")
print("patched", path)
