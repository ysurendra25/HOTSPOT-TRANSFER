package main

import (
	"net/http/httptest"
	"testing"
)

func TestQRImageHandlerEncodesPayload(t *testing.T) {
	req := httptest.NewRequest("GET", "/qr.png?data=PBQ1%7CT%7Cid%7C0%7C1%7CaGVsbG8", nil)
	res := httptest.NewRecorder()
	qrImageHandler(res, req)
	if res.Code != 200 || res.Header().Get("Content-Type") != "image/png" || len(res.Body.Bytes()) < 100 {
		t.Fatalf("unexpected QR response: %d %q %d bytes", res.Code, res.Header().Get("Content-Type"), res.Body.Len())
	}
}

func TestQRImageHandlerRejectsOversizedPayload(t *testing.T) {
	data := make([]byte, 2201)
	for i := range data { data[i] = 'x' }
	req := httptest.NewRequest("GET", "/qr.png?data="+string(data), nil)
	res := httptest.NewRecorder()
	qrImageHandler(res, req)
	if res.Code != 400 { t.Fatalf("expected HTTP 400, got %d", res.Code) }
}
