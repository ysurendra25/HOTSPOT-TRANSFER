package main

import (
	"context"
	"net"
	"net/http"
)

// Defaults for the laptop-to-phone JPEG stream. Quality 75 at up to 1920
// pixels wide keeps text sharp while a frame stays around 100–250 KB, which a
// phone hotspot can carry at a smooth frame rate.
const (
	jpegDefaultQuality  = 75
	jpegDefaultMaxWidth = 1920
	// streamSendBuffer bounds how much video may wait in the kernel. Windows
	// grows a socket's send buffer automatically to several megabytes; with
	// 1 MB JPEG frames that alone held seconds of stale picture. A small
	// buffer makes a slow link block the writer, so the next frame captured
	// is always the newest one.
	streamSendBuffer = 256 << 10
)

type connKey struct{}

// rememberConn stores the client connection in the request context so a
// streaming handler can tune its socket.
func rememberConn(ctx context.Context, c net.Conn) context.Context {
	return context.WithValue(ctx, connKey{}, c)
}

// limitSendBuffer shrinks the socket send buffer for a live stream. It
// reports whether the buffer was set.
func limitSendBuffer(r *http.Request, size int) bool {
	c, ok := r.Context().Value(connKey{}).(net.Conn)
	if !ok {
		return false
	}
	type writeBufferSetter interface{ SetWriteBuffer(int) error }
	if tc, ok := c.(writeBufferSetter); ok {
		return tc.SetWriteBuffer(size) == nil
	}
	return false
}

// frameSkipper decides whether a live stream should capture on this tick. When
// sending the previous frame took longer than the frame interval (the link is
// slower than the frame rate), it waits that long before the next capture so
// the stream sends fewer, current frames instead of a growing backlog.
type frameSkipper struct {
	busyUntilNs int64
}

func (s *frameSkipper) ready(nowNs int64) bool { return nowNs >= s.busyUntilNs }

func (s *frameSkipper) sent(startNs, endNs, intervalNs int64) {
	spent := endNs - startNs
	if spent > intervalNs {
		// The write took `spent`; give the link that much again to drain.
		s.busyUntilNs = endNs + (spent - intervalNs)
	} else {
		s.busyUntilNs = 0
	}
}
