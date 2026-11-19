package main

import (
	"net/http"

	qrcode "github.com/skip2/go-qrcode"
)

func qrImageHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	data := r.URL.Query().Get("data")
	if data == "" || len(data) > 2200 {
		http.Error(w, "QR payload must be between 1 and 2,200 bytes", http.StatusBadRequest)
		return
	}
	image, err := qrcode.Encode(data, qrcode.Medium, 720)
	if err != nil {
		http.Error(w, "could not encode QR payload", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(image)
}
