#!/usr/bin/env python3
from pathlib import Path

p = Path("src/internal/rubikameow/client.go")
s = p.read_text(encoding="utf-8")

old_import = '''import (
	"bytes"
	"context"
	"crypto/rsa"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"'''
new_import = '''import (
	"bytes"
	"context"
	"crypto/rsa"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"'''
if old_import not in s:
    raise SystemExit("GoOmni import block changed")
s = s.replace(old_import, new_import, 1)

old_headers = '''	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Origin", "https://web.rubika.ir")
	response, err := c.http.Do(request)'''
new_headers = '''	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Origin", "https://m.rubika.ir")
	request.Header.Set("Referer", "https://m.rubika.ir/")
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.0.0 Safari/537.36")
	request.Header.Set("Connection", "keep-alive")
	response, err := c.http.Do(request)'''
if old_headers not in s:
    raise SystemExit("GoOmni request header block changed")
s = s.replace(old_headers, new_headers, 1)

old_http = '''	if response.StatusCode != 200 {
		return nil, &domains.Error{Code: "PROVIDER_HTTP_ERROR", Message: "Rubika returned an unsuccessful HTTP response", HTTP: 502, Ambiguous: mutating}
	}'''
new_http = '''	if response.StatusCode != 200 {
		return nil, &domains.Error{
			Code: "PROVIDER_HTTP_ERROR",
			Message: fmt.Sprintf("Rubika upstream HTTP %d", response.StatusCode),
			HTTP: 502,
			Ambiguous: mutating,
		}
	}'''
if old_http not in s:
    raise SystemExit("GoOmni HTTP error block changed")
s = s.replace(old_http, new_http, 1)

old_status = '''		if decoded.str("status") == "" || (errors.As(failure, &de) && de.Code == "PROVIDER_REJECTED") {
			return nil, &domains.Error{Code: "PROVIDER_PROTOCOL_ERROR", Message: "Rubika returned an unsupported response status", HTTP: 502, Ambiguous: mutating}
		}'''
new_status = '''		if decoded.str("status") == "" || (errors.As(failure, &de) && de.Code == "PROVIDER_REJECTED") {
			return nil, &domains.Error{
				Code: "PROVIDER_PROTOCOL_ERROR",
				Message: "Rubika unsupported status=" + decoded.str("status") + " detail=" + decoded.str("status_det"),
				HTTP: 502,
				Ambiguous: mutating,
			}
		}'''
if old_status not in s:
    raise SystemExit("GoOmni protocol status block changed")
s = s.replace(old_status, new_status, 1)

p.write_text(s, encoding="utf-8")
print("Patched Rubika HTTP identity and diagnostics in", p)
