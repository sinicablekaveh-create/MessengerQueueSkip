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


# Android app-private filesystems may reject hard-link publication even though
# ordinary app-private file IO works. Keep GoOmni's hard-link path first, then
# fall back to an exclusive streamed copy. Cleanup verifies copied bytes before
# deleting an unregistered final file, preserving the ownership invariant.
media = Path("src/infrastructure/mediafile/manager.go")
mtext = media.read_text(encoding="utf-8")

old_publish = '''func (u *Upload) Publish() error {
\tif err := u.File.Sync(); err != nil {
\t\treturn err
\t}
\tif err := u.File.Close(); err != nil {
\t\treturn err
\t}
\tdir := filepath.Join(u.m.root, u.v.ConnectionID)
\tif err := ensureDirectory(dir); err != nil {
\t\treturn err
\t}
\t// Link publishes without replacing any existing file, including a symlink.
\tif err := os.Link(filepath.Join(u.dir, "data"), filepath.Join(dir, u.v.MediaID)); err != nil {
\t\treturn errors.New("media file cannot be published")
\t}
\tif err := syncDirectory(dir); err != nil {
\t\treturn err
\t}
\t// Retain the staging hard link until cleanup. It proves that an unregistered
\t// final file is ours even after a crash, and protects an unexpected collision
\t// when publication failed without replacing the existing destination.
\treturn nil
}
'''

new_publish = '''func copyExclusive(src, dst string) error {
\tin, err := os.Open(src)
\tif err != nil {
\t\treturn err
\t}
\tdefer in.Close()
\tout, err := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
\tif err != nil {
\t\treturn err
\t}
\tok := false
\tdefer func() {
\t\t_ = out.Close()
\t\tif !ok { _ = os.Remove(dst) }
\t}()
\tif _, err = io.Copy(out, in); err != nil {
\t\treturn err
\t}
\tif err = out.Sync(); err != nil {
\t\treturn err
\t}
\tif err = out.Close(); err != nil {
\t\treturn err
\t}
\tok = true
\treturn nil
}

func sameFileBytes(a, b string) bool {
\taInfo, err := os.Stat(a)
\tif err != nil || !aInfo.Mode().IsRegular() {
\t\treturn false
\t}
\tbInfo, err := os.Stat(b)
\tif err != nil || !bInfo.Mode().IsRegular() || aInfo.Size() != bInfo.Size() {
\t\treturn false
\t}
\taf, err := os.Open(a)
\tif err != nil { return false }
\tdefer af.Close()
\tbf, err := os.Open(b)
\tif err != nil { return false }
\tdefer bf.Close()
\tab := make([]byte, 64*1024)
\tbb := make([]byte, 64*1024)
\tfor {
\t\tan, ae := af.Read(ab)
\t\tbn, be := bf.Read(bb)
\t\tif an != bn || !bytes.Equal(ab[:an], bb[:bn]) {
\t\t\treturn false
\t\t}
\t\tif ae == io.EOF && be == io.EOF { return true }
\t\tif ae != nil || be != nil { return false }
\t}
}

func (u *Upload) Publish() error {
\tif err := u.File.Sync(); err != nil {
\t\treturn err
\t}
\tif err := u.File.Close(); err != nil {
\t\treturn err
\t}
\tdir := filepath.Join(u.m.root, u.v.ConnectionID)
\tif err := ensureDirectory(dir); err != nil {
\t\treturn err
\t}
\tsrc := filepath.Join(u.dir, "data")
\tdst := filepath.Join(dir, u.v.MediaID)
\t// Preferred upstream publication: hard link, no replacement.
\tif err := os.Link(src, dst); err != nil {
\t\t// Android fallback: still no replacement, but avoid depending on link(2).
\t\tif err := copyExclusive(src, dst); err != nil {
\t\t\treturn errors.New("media file cannot be published")
\t\t}
\t}
\tif err := syncDirectory(dir); err != nil {
\t\treturn err
\t}
\treturn nil
}
'''

if old_publish not in mtext:
    raise SystemExit("media Publish block not found")
mtext = mtext.replace(old_publish, new_publish, 1)

old_cleanup = '''\tif !registered {
\t\tif finalExists {
\t\t\tdataInfo, dataErr := os.Lstat(dataPath)
\t\t\tfinalInfo, finalErr := os.Lstat(final)
\t\t\tif !dataExists || dataErr != nil || finalErr != nil || !os.SameFile(dataInfo, finalInfo) {
\t\t\t\treturn errors.New("unregistered media ownership cannot be verified")
\t\t\t}
\t\t\tif err = os.Remove(final); err != nil {
\t\t\t\treturn err
\t\t\t}
\t\t\tif err = syncDirectory(filepath.Dir(final)); err != nil {
\t\t\t\treturn err
\t\t\t}
\t\t}
\t}
'''

new_cleanup = '''\tif !registered {
\t\tif finalExists {
\t\t\tdataInfo, dataErr := os.Lstat(dataPath)
\t\t\tfinalInfo, finalErr := os.Lstat(final)
\t\t\towned := dataExists && dataErr == nil && finalErr == nil && os.SameFile(dataInfo, finalInfo)
\t\t\tif !owned && dataExists && dataErr == nil && finalErr == nil {
\t\t\t\towned = sameFileBytes(dataPath, final)
\t\t\t}
\t\t\tif !owned {
\t\t\t\treturn errors.New("unregistered media ownership cannot be verified")
\t\t\t}
\t\t\tif err = os.Remove(final); err != nil {
\t\t\t\treturn err
\t\t\t}
\t\t\tif err = syncDirectory(filepath.Dir(final)); err != nil {
\t\t\t\treturn err
\t\t\t}
\t\t}
\t}
'''

if old_cleanup not in mtext:
    raise SystemExit("media cleanup block not found")
mtext = mtext.replace(old_cleanup, new_cleanup, 1)

old_import = '''import (
\t"context"
\t"encoding/json"'''
new_import = '''import (
\t"bytes"
\t"context"
\t"encoding/json"'''
if old_import not in mtext:
    raise SystemExit("media import block not found")
mtext = mtext.replace(old_import, new_import, 1)

media.write_text(mtext, encoding="utf-8")
print("patched", media)
