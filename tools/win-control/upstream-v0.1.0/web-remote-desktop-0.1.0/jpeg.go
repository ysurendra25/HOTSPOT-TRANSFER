package main

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"net/http"
	"sync"
	"time"
)

var jpegBufferPool = sync.Pool{New: func() any { return new(bytes.Buffer) }}

// jpegStreamHandler is the no-install, no-FFmpeg screen path for local networks.
// The normal H.264 path is more bandwidth-efficient, while this multipart JPEG
// path keeps browser control usable when the PC is offline or has no FFmpeg.
func jpegStreamHandler(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming is unavailable", http.StatusInternalServerError)
		return
	}
	// A viewer is watching: wake the panel and hold it on, exactly like the
	// H.264 path. Without this the JPEG view (the one Android WebView and
	// FFmpeg-less laptops use) stayed black whenever the display had idled off,
	// because capture fails on a powered-down panel and nothing woke it.
	viewerArrived()
	defer viewerLeft()
	if _, err := captureAfterWake(liveCfg().Monitor, 5*time.Second); err != nil {
		http.Error(w, "the laptop screen could not be captured: "+err.Error(), http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=pbframe")
	w.Header().Set("X-Accel-Buffering", "no")
	w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
	w.Header().Set("Connection", "keep-alive")
	flusher.Flush()
	limitSendBuffer(r, streamSendBuffer)

	lastJPEG := time.Time{}
	interval := time.Second / time.Duration(maxInt(1, minInt(30, liveCfg().FPS)))
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var skipper frameSkipper
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
		}
		if !skipper.ready(time.Now().UnixNano()) {
			continue
		}
		c := liveCfg()
		frame, changed, err := captureDisplayChanged(c.Monitor)
		if err != nil {
			// Secure desktop (UAC, lock screen) or a display switching mode:
			// keep the connection and try again on the next tick.
			continue
		}
		// A repeated JPEG every two seconds keeps mobile browsers' image load
		// heartbeat alive on a motionless desktop without continuously encoding.
		if !changed && time.Since(lastJPEG) < 2*time.Second {
			continue
		}
		outW, outH := outputSize(c, frame.Bounds().Dx(), frame.Bounds().Dy())
		if outW > 0 && outH > 0 && (outW != frame.Bounds().Dx() || outH != frame.Bounds().Dy()) {
			frame = scaleRGBA(frame, outW, outH)
		}
		data := jpegBufferPool.Get().(*bytes.Buffer)
		data.Reset()
		if err := jpeg.Encode(data, frame, &jpeg.Options{Quality: c.Quality}); err != nil {
			data.Reset()
			jpegBufferPool.Put(data)
			continue
		}
		writeStart := time.Now()
		if _, err := fmt.Fprintf(w, "--pbframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", data.Len()); err != nil {
			data.Reset()
			jpegBufferPool.Put(data)
			return
		}
		if _, err := w.Write(data.Bytes()); err != nil {
			data.Reset()
			jpegBufferPool.Put(data)
			return
		}
		if _, err := w.Write([]byte("\r\n")); err != nil {
			data.Reset()
			jpegBufferPool.Put(data)
			return
		}
		data.Reset()
		jpegBufferPool.Put(data)
		flusher.Flush()
		lastJPEG = time.Now()
		skipper.sent(writeStart.UnixNano(), lastJPEG.UnixNano(), int64(interval))
	}
}

func scaleRGBA(src *image.RGBA, width, height int) *image.RGBA {
	dst := image.NewRGBA(image.Rect(0, 0, width, height))
	sb := src.Bounds()
	for y := 0; y < height; y++ {
		sy := sb.Min.Y + y*sb.Dy()/height
		for x := 0; x < width; x++ {
			sx := sb.Min.X + x*sb.Dx()/width
			si, di := src.PixOffset(sx, sy), dst.PixOffset(x, y)
			copy(dst.Pix[di:di+4], src.Pix[si:si+4])
		}
	}
	return dst
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
