package main

// Resumable uploads into the laptop: the same protocol as the phone's
// MediaHttpServer (see docs/TRANSFER_ENGINE.md), so the Android app's
// ChunkedUploader and the Edge page talk to either receiver unchanged.
//
//	POST   /api/upload/start          {name,size,lastModified,relPath} -> state with missing ranges
//	PUT    /api/upload/{id}?offset=N  raw bytes for [N, N+Content-Length) -> {received, complete}
//	GET    /api/upload/{id}           state
//	DELETE /api/upload/{id}           drop it
//	POST   /api/upload/{id}/finish    move it into the receive folder -> {id, name, bytes}
//	POST   /api/upload/batch          PBB1 body of many small files -> {files:[...]}
//
// Data goes into a preallocated sparse file (Truncate) written with WriteAt,
// so several connections fill different ranges at once. Progress is kept in a
// PBRESUME 1 manifest beside it, saved only after the data it covers was
// synced (every 64 MiB or 2 s), so a crash can lose progress but never marks
// unwritten bytes as done.

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	mib                   = 1 << 20
	uploadMinChunk        = 1 * mib
	uploadMaxChunk        = 16 * mib
	uploadFlushBytes      = 64 * mib
	uploadFlushEvery      = 2 * time.Second
	uploadStaleAfter      = 7 * 24 * time.Hour
	uploadBufferSize      = 1 * mib
	batchMaxPathBytes     = 4096
	batchMaxEntries       = 100000
	relPathMaxDepth       = 32
	relPathMaxSegment     = 200
	receiveFolderName     = "PocketBridge"
	uploadStateFolderName = ".pocketbridge-uploads"
	resumeMagic           = "PBRESUME 1"
)

// uploadFailure carries the HTTP status that describes it.
type uploadFailure struct {
	status int
	msg    string
}

func (f *uploadFailure) Error() string { return f.msg }

func failf(status int, format string, args ...any) error {
	return &uploadFailure{status: status, msg: fmt.Sprintf(format, args...)}
}

// ---- buffers ----

// uploadBuffers bounds allocation: every PUT and batch borrows one 1 MiB
// buffer instead of allocating per request.
var uploadBuffers = sync.Pool{New: func() any { b := make([]byte, uploadBufferSize); return &b }}

// ---- safe relative paths ----

var (
	reservedDeviceName = regexp.MustCompile(`(?i)^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\$|CONOUT\$)(\..*)?$`)
	driveLetter        = regexp.MustCompile(`^[A-Za-z]:`)
	reservedChars      = regexp.MustCompile(`[<>:"|?*\x00-\x1f]`)
)

// safeRelPath turns "Trip/day 1/a.jpg" from the network into a path that can
// only ever point inside the receive folder. Unlike the phone (which rewrites
// such names), the laptop refuses anything that tries to escape: "..",
// absolute paths, drive letters, UNC prefixes and Windows device names.
func safeRelPath(raw string) (string, error) {
	p := strings.ReplaceAll(raw, "\\", "/")
	if strings.TrimSpace(p) == "" {
		return "", errors.New("empty path")
	}
	if strings.HasPrefix(p, "/") || driveLetter.MatchString(p) {
		return "", errors.New("absolute paths are not allowed")
	}
	var out []string
	for _, part := range strings.Split(p, "/") {
		if part == ".." {
			return "", errors.New("'..' is not allowed")
		}
		seg := strings.TrimSpace(reservedChars.ReplaceAllString(part, "_"))
		seg = strings.TrimRight(seg, ". ")
		if seg == "" || seg == "." {
			continue
		}
		if reservedDeviceName.MatchString(seg) {
			return "", fmt.Errorf("%q is a Windows device name", seg)
		}
		if len(seg) > relPathMaxSegment {
			seg = strings.TrimSpace(seg[:relPathMaxSegment])
		}
		out = append(out, seg)
		if len(out) > relPathMaxDepth {
			return "", errors.New("folder path is too deep")
		}
	}
	if len(out) == 0 {
		return "", errors.New("empty path")
	}
	return strings.Join(out, "/"), nil
}

// safeFileName is one segment: separators and reserved characters become "_".
func safeFileName(raw string) string {
	v := strings.TrimSpace(reservedChars.ReplaceAllString(strings.NewReplacer("/", "_", "\\", "_").Replace(raw), "_"))
	v = strings.TrimRight(v, ". ")
	if v == "" || v == "." || v == ".." {
		return "shared-file"
	}
	if reservedDeviceName.MatchString(v) {
		v = "_" + v
	}
	return v
}

// resolveInside joins root and a safe relative path, checks the result stays
// inside root and creates the parent folders.
func resolveInside(root, rel string) (string, error) {
	clean, err := safeRelPath(rel)
	if err != nil {
		return "", err
	}
	target := filepath.Join(root, filepath.FromSlash(clean))
	r, err := filepath.Rel(root, target)
	if err != nil || r == ".." || strings.HasPrefix(r, ".."+string(filepath.Separator)) || filepath.IsAbs(r) {
		return "", errors.New("path escapes the receive folder")
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return "", err
	}
	return target, nil
}

// uniquePath returns p, or "name (2).ext", "name (3).ext"... that does not exist yet.
func uniquePath(p string) string {
	if _, err := os.Lstat(p); errors.Is(err, os.ErrNotExist) {
		return p
	}
	dir, name := filepath.Split(p)
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	if base == "" {
		base, ext = name, ""
	}
	for n := 2; ; n++ {
		next := filepath.Join(dir, fmt.Sprintf("%s (%d)%s", base, n, ext))
		if _, err := os.Lstat(next); errors.Is(err, os.ErrNotExist) {
			return next
		}
	}
}

// ---- resume manifest (same text format as ResumeManifest.java) ----

type resumeManifest struct {
	mu        sync.Mutex
	size      int64
	validator string
	source    string
	done      [][2]int64 // sorted, merged, half-open
}

func (m *resumeManifest) add(start, end int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if start < 0 {
		start = 0
	}
	if end > m.size {
		end = m.size
	}
	if end <= start {
		return
	}
	merged := make([][2]int64, 0, len(m.done)+1)
	placed := false
	for _, r := range m.done {
		switch {
		case r[1] < start:
			merged = append(merged, r)
		case r[0] > end:
			if !placed {
				merged = append(merged, [2]int64{start, end})
				placed = true
			}
			merged = append(merged, r)
		default:
			start, end = min(start, r[0]), max(end, r[1])
		}
	}
	if !placed {
		merged = append(merged, [2]int64{start, end})
	}
	sort.Slice(merged, func(i, j int) bool { return merged[i][0] < merged[j][0] })
	m.done = merged
}

func (m *resumeManifest) completed() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	var n int64
	for _, r := range m.done {
		n += r[1] - r[0]
	}
	return n
}

func (m *resumeManifest) missing() [][2]int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	gaps := [][2]int64{}
	var at int64
	for _, r := range m.done {
		if r[0] > at {
			gaps = append(gaps, [2]int64{at, r[0]})
		}
		at = max(at, r[1])
	}
	if at < m.size {
		gaps = append(gaps, [2]int64{at, m.size})
	}
	return gaps
}

func (m *resumeManifest) encode() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	var b strings.Builder
	b.WriteString(resumeMagic + "\n")
	fmt.Fprintf(&b, "size=%d\nvalidator=%s\nsource=%s\ndone=", m.size,
		strings.ReplaceAll(m.validator, "\n", " "), strings.ReplaceAll(m.source, "\n", " "))
	for i, r := range m.done {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, "%d-%d", r[0], r[1])
	}
	b.WriteByte('\n')
	return b.String()
}

func decodeManifest(text string) *resumeManifest {
	if !strings.HasPrefix(text, resumeMagic) {
		return nil
	}
	m := &resumeManifest{size: -1}
	ranges := ""
	for _, line := range strings.Split(text, "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok || k == "" {
			continue
		}
		switch k {
		case "size":
			n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
			if err != nil {
				return nil
			}
			m.size = n
		case "validator":
			m.validator = v
		case "source":
			m.source = v
		case "done":
			ranges = strings.TrimSpace(v)
		}
	}
	if m.size < 0 {
		return nil
	}
	if ranges != "" {
		for _, part := range strings.Split(ranges, ",") {
			a, b, ok := strings.Cut(part, "-")
			s, e1 := strconv.ParseInt(a, 10, 64)
			e, e2 := strconv.ParseInt(b, 10, 64)
			if !ok || e1 != nil || e2 != nil {
				return nil
			}
			m.add(s, e)
		}
	}
	return m
}

func readManifest(path string) *resumeManifest {
	info, err := os.Stat(path)
	if err != nil || info.Size() > mib {
		return nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	return decodeManifest(string(data))
}

// writeFileAtomic: temp file, fsync, rename.
func writeFileAtomic(path, text string) error {
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err = f.WriteString(text); err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ---- sessions ----

// uploadData is where a session's bytes go. A real file in production; the
// benchmarks swap in a discarding sink so 4 GB runs fit a small /tmp.
type uploadData interface {
	io.WriterAt
	Sync() error
	Close() error
}

// openUploadData opens (and, when fresh, sparsely preallocates) the data file.
var openUploadData = func(path string, size int64, fresh bool) (uploadData, error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if fresh {
		// Sparse preallocation: no zeros are written; WriteAt lands anywhere.
		if err := f.Truncate(size); err != nil {
			f.Close()
			return nil, err
		}
	}
	return f, nil
}

type uploadSession struct {
	id, name, relPath  string
	size, lastModified int64
	dataPath, manPath  string
	manifest           *resumeManifest
	resumed            bool

	mu        sync.Mutex
	data      uploadData
	writers   atomic.Int32
	unflushed int64
	lastFlush time.Time
	closed    bool
}

func (s *uploadSession) file() (uploadData, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, failf(http.StatusGone, "This upload was cancelled or already finished.")
	}
	if s.data == nil {
		d, err := openUploadData(s.dataPath, s.size, false)
		if err != nil {
			return nil, err
		}
		s.data = d
	}
	return s.data, nil
}

// wrote counts bytes and saves progress every 64 MiB or 2 s (or when forced).
func (s *uploadSession) wrote(n int64, force bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.unflushed += n
	if !force && s.unflushed < uploadFlushBytes && time.Since(s.lastFlush) < uploadFlushEvery {
		return nil
	}
	// The snapshot only holds ranges whose writes returned; Sync runs after it.
	snapshot := s.manifest.encode()
	if s.data != nil {
		if err := s.data.Sync(); err != nil {
			return err
		}
	}
	s.unflushed, s.lastFlush = 0, time.Now()
	return writeFileAtomic(s.manPath, snapshot)
}

func (s *uploadSession) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.data != nil {
		_ = s.data.Close()
		s.data = nil
	}
}

func (s *uploadSession) state() map[string]any {
	conns := initialConnections(s.size)
	done := s.manifest.completed()
	return map[string]any{
		"id": s.id, "name": s.name, "relPath": s.relPath, "size": s.size,
		"received": done, "complete": done >= s.size, "resumed": s.resumed,
		"missing": s.manifest.missing(), "chunk": initialChunk(s.size, conns), "connections": conns,
	}
}

// Same planning constants as TransferPlan.java.
func initialConnections(total int64) int {
	switch {
	case total < 8*mib:
		return 1
	case total < 256*mib:
		return 2
	default:
		return 4
	}
}

func initialChunk(total int64, conns int) int64 {
	if total <= 0 {
		return uploadMinChunk
	}
	share := total / int64(max(1, conns)) / 8
	share = max(uploadMinChunk, min(uploadMaxChunk, share))
	return share / mib * mib
}

// uploadFreeSpace is replaced by the discarding benchmark.
var uploadFreeSpace = freeSpace

// uploadStore owns the sessions of one receive folder.
type uploadStore struct {
	mu   sync.Mutex
	root string // receive folder; state lives in root/.pocketbridge-uploads
	open map[string]*uploadSession
	free func(dir string) (uint64, bool)
}

func newUploadStore(root string) *uploadStore {
	return &uploadStore{root: root, open: map[string]*uploadSession{}, free: uploadFreeSpace}
}

func (u *uploadStore) stateDir() string { return filepath.Join(u.root, uploadStateFolderName) }

var uploadIDPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)

// uploadSessionID: first 32 hex of SHA-256(name\nsize\nlastModified\nrelPath),
// identical to UploadSessions.sessionId, so a client resumes on either side.
func uploadSessionID(name string, size, lastModified int64, relPath string) string {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s\n%d\n%d\n%s", name, size, lastModified, relPath)))
	return hex.EncodeToString(sum[:16])
}

func (u *uploadStore) start(rawName string, size, lastModified int64, rawRel string) (*uploadSession, error) {
	if size < 0 {
		return nil, failf(http.StatusBadRequest, "Upload must include the file size.")
	}
	name := safeFileName(rawName)
	rel := ""
	if strings.TrimSpace(rawRel) != "" {
		clean, err := safeRelPath(rawRel)
		if err != nil {
			return nil, failf(http.StatusBadRequest, "Invalid folder path: %v.", err)
		}
		rel = clean
	}
	id := uploadSessionID(name, size, lastModified, rel)
	u.mu.Lock()
	defer u.mu.Unlock()
	if s := u.open[id]; s != nil {
		return s, nil
	}
	if err := os.MkdirAll(u.stateDir(), 0o755); err != nil {
		return nil, failf(http.StatusInternalServerError, "Could not create the receive folder.")
	}
	dataPath := filepath.Join(u.stateDir(), id+".data")
	manPath := filepath.Join(u.stateDir(), id+".pbresume")
	validator := fmt.Sprintf("%d:%d", size, lastModified)
	m := readManifest(manPath)
	resumed := false
	if m != nil && m.size == size && m.validator == validator {
		if info, err := os.Stat(dataPath); err == nil && info.Size() == size {
			resumed = true
		}
	}
	if !resumed {
		_ = os.Remove(dataPath)
		source := "name:" + name
		if rel != "" {
			source = "rel:" + rel
		}
		m = &resumeManifest{size: size, validator: validator, source: source}
	}
	if need := size - m.completed(); need > 0 {
		if free, ok := u.free(u.stateDir()); ok && free < uint64(need) {
			return nil, failf(http.StatusInsufficientStorage, "The laptop needs %d more MB of free space for this file.", (uint64(need)-free)/mib+1)
		}
	}
	s := &uploadSession{id: id, name: name, relPath: rel, size: size, lastModified: lastModified,
		dataPath: dataPath, manPath: manPath, manifest: m, resumed: resumed, lastFlush: time.Now()}
	if !resumed {
		d, err := openUploadData(dataPath, size, true)
		if err != nil {
			return nil, failf(http.StatusInternalServerError, "Could not create the upload file: %v", err)
		}
		s.data = d
		if err := writeFileAtomic(manPath, m.encode()); err != nil {
			d.Close()
			return nil, failf(http.StatusInternalServerError, "Could not save upload progress.")
		}
	}
	u.open[id] = s
	return s, nil
}

// find returns an open session, reloading it from its manifest after a restart.
func (u *uploadStore) find(id string) *uploadSession {
	if !uploadIDPattern.MatchString(id) {
		return nil
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	if s := u.open[id]; s != nil {
		return s
	}
	dataPath := filepath.Join(u.stateDir(), id+".data")
	manPath := filepath.Join(u.stateDir(), id+".pbresume")
	m := readManifest(manPath)
	if m == nil {
		return nil
	}
	if info, err := os.Stat(dataPath); err != nil || info.Size() != m.size {
		return nil
	}
	name, rel := "", ""
	switch {
	case strings.HasPrefix(m.source, "rel:"):
		r, err := safeRelPath(m.source[4:])
		if err != nil {
			return nil
		}
		rel = r
		name = safeFileName(rel[strings.LastIndex(rel, "/")+1:])
	case strings.HasPrefix(m.source, "name:"):
		name = safeFileName(m.source[5:])
	default:
		return nil
	}
	var lastModified int64
	if _, after, ok := strings.Cut(m.validator, ":"); ok {
		lastModified, _ = strconv.ParseInt(after, 10, 64)
	}
	s := &uploadSession{id: id, name: name, relPath: rel, size: m.size, lastModified: lastModified,
		dataPath: dataPath, manPath: manPath, manifest: m, resumed: true, lastFlush: time.Now()}
	u.open[id] = s
	return s
}

func (u *uploadStore) require(id string) (*uploadSession, error) {
	if s := u.find(id); s != nil {
		return s, nil
	}
	return nil, failf(http.StatusNotFound, "Unknown upload. Start it again.")
}

// write stores length bytes from body at offset. Bytes that arrived before a
// dropped connection stay recorded, so the client resumes from there.
func (u *uploadStore) write(id string, offset, length int64, body io.Reader) (int64, error) {
	s, err := u.require(id)
	if err != nil {
		return 0, err
	}
	if offset < 0 || length < 0 || offset+length > s.size {
		return 0, failf(http.StatusRequestedRangeNotSatisfiable, "Range %d+%d is outside the file (%d bytes).", offset, length, s.size)
	}
	s.writers.Add(1)
	defer s.writers.Add(-1)
	f, err := s.file()
	if err != nil {
		return 0, err
	}
	bp := uploadBuffers.Get().(*[]byte)
	defer uploadBuffers.Put(bp)
	buf := *bp
	pos, left := offset, length
	for left > 0 {
		want := int(min(left, int64(len(buf))))
		n, rerr := io.ReadFull(body, buf[:want])
		if n > 0 {
			if _, werr := f.WriteAt(buf[:n], pos); werr != nil {
				_ = s.wrote(0, true)
				return 0, werr
			}
			s.manifest.add(pos, pos+int64(n))
			pos += int64(n)
			left -= int64(n)
			if werr := s.wrote(int64(n), false); werr != nil {
				return 0, werr
			}
		}
		if rerr != nil {
			_ = s.wrote(0, true)
			if errors.Is(rerr, io.ErrUnexpectedEOF) || errors.Is(rerr, io.EOF) {
				return 0, failf(http.StatusBadRequest, "The upload ended early at byte %d. Resume from there.", pos)
			}
			return 0, rerr
		}
	}
	return s.manifest.completed(), nil
}

type finishedUpload struct {
	path, id, name string
	size           int64
}

// finish moves a complete upload into the receive folder; a clash gets " (2)".
func (u *uploadStore) finish(id string) (finishedUpload, error) {
	s, err := u.require(id)
	if err != nil {
		return finishedUpload{}, err
	}
	if s.writers.Load() > 0 {
		return finishedUpload{}, failf(http.StatusConflict, "Parts of this upload are still being written.")
	}
	if got := s.manifest.completed(); got < s.size {
		return finishedUpload{}, failf(http.StatusConflict, "The upload is missing %d bytes.", s.size-got)
	}
	if err := s.wrote(0, true); err != nil {
		return finishedUpload{}, err
	}
	s.close()
	var target string
	if s.relPath != "" {
		target, err = resolveInside(u.root, s.relPath)
		if err != nil {
			return finishedUpload{}, failf(http.StatusBadRequest, "Invalid folder path.")
		}
	} else {
		if err := os.MkdirAll(u.root, 0o755); err != nil {
			return finishedUpload{}, failf(http.StatusInternalServerError, "Could not create the receive folder.")
		}
		target = filepath.Join(u.root, s.name)
	}
	target = uniquePath(target)
	if err := os.Rename(s.dataPath, target); err != nil {
		return finishedUpload{}, failf(http.StatusInternalServerError, "Could not save the uploaded file: %v", err)
	}
	if s.lastModified > 0 {
		t := time.UnixMilli(s.lastModified)
		_ = os.Chtimes(target, t, t)
	}
	_ = os.Remove(s.manPath)
	u.mu.Lock()
	delete(u.open, id)
	u.mu.Unlock()
	rel, _ := filepath.Rel(u.root, target)
	rel = filepath.ToSlash(rel)
	info, _ := os.Stat(target)
	size := s.size
	if info != nil {
		size = info.Size()
	}
	invalidateListing()
	return finishedUpload{path: target, id: rel, name: rel, size: size}, nil
}

func (u *uploadStore) cancel(id string) bool {
	s := u.find(id)
	if s == nil {
		return false
	}
	s.close()
	u.mu.Lock()
	delete(u.open, id)
	u.mu.Unlock()
	_ = os.Remove(s.dataPath)
	_ = os.Remove(s.manPath)
	return true
}

// prune deletes sessions nobody resumed for a week.
func (u *uploadStore) prune(now time.Time) int {
	entries, err := os.ReadDir(u.stateDir())
	if err != nil {
		return 0
	}
	removed := 0
	for _, e := range entries {
		id, _, _ := strings.Cut(e.Name(), ".")
		u.mu.Lock()
		_, busy := u.open[id]
		u.mu.Unlock()
		info, err := e.Info()
		if busy || err != nil || now.Sub(info.ModTime()) < uploadStaleAfter {
			continue
		}
		if os.Remove(filepath.Join(u.stateDir(), e.Name())) == nil {
			removed++
		}
	}
	return removed
}

// ---- PBB1 batch ----

var batchMagic = []byte("PBB1")

// readBatch reads "PBB1" | (u16 len, path, u64 size, bytes)* | u16 0 from at
// most limit bytes and writes each file under root, keeping folders. On any
// error the files this batch wrote are removed.
func readBatch(body io.Reader, limit int64, root string) ([]finishedUpload, error) {
	r := io.LimitReader(body, limit)
	head := make([]byte, 4)
	if _, err := io.ReadFull(r, head); err != nil || string(head) != string(batchMagic) {
		return nil, failf(http.StatusBadRequest, "Not a PocketBridge batch.")
	}
	bp := uploadBuffers.Get().(*[]byte)
	defer uploadBuffers.Put(bp)
	buf := *bp
	var out []finishedUpload
	fail := func(err error) ([]finishedUpload, error) {
		for _, f := range out {
			_ = os.Remove(f.path)
		}
		return nil, err
	}
	var u16 [2]byte
	var u64 [8]byte
	for {
		if _, err := io.ReadFull(r, u16[:]); err != nil {
			return fail(failf(http.StatusBadRequest, "The batch ended early."))
		}
		n := int(binary.BigEndian.Uint16(u16[:]))
		if n == 0 {
			break
		}
		if n > batchMaxPathBytes || len(out) >= batchMaxEntries {
			return fail(failf(http.StatusBadRequest, "Batch entry is too large."))
		}
		name := make([]byte, n)
		if _, err := io.ReadFull(r, name); err != nil {
			return fail(failf(http.StatusBadRequest, "The batch ended early."))
		}
		if _, err := io.ReadFull(r, u64[:]); err != nil {
			return fail(failf(http.StatusBadRequest, "The batch ended early."))
		}
		size := int64(binary.BigEndian.Uint64(u64[:]))
		if size < 0 {
			return fail(failf(http.StatusBadRequest, "Negative file size in batch."))
		}
		target, err := resolveInside(root, string(name))
		if err != nil {
			return fail(failf(http.StatusBadRequest, "Invalid file path in batch: %v.", err))
		}
		target = uniquePath(target)
		f, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
		if err != nil {
			return fail(failf(http.StatusInternalServerError, "Could not create %s.", filepath.Base(target)))
		}
		rel, _ := filepath.Rel(root, target)
		rel = filepath.ToSlash(rel)
		out = append(out, finishedUpload{path: target, id: rel, name: rel, size: size})
		copied, err := io.CopyBuffer(onlyWriter{f}, io.LimitReader(r, size), buf)
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil || copied != size {
			return fail(failf(http.StatusBadRequest, "The batch ended early."))
		}
	}
	invalidateListing()
	return out, nil
}

// onlyWriter hides ReadFrom so io.CopyBuffer really uses the pooled buffer.
type onlyWriter struct{ w io.Writer }

func (o onlyWriter) Write(p []byte) (int, error) { return o.w.Write(p) }

// ---- HTTP ----

var (
	uploadStoresMu sync.Mutex
	uploadStores   = map[string]*uploadStore{}
)

// receiveDir: received files land in <shared folder>/PocketBridge, so they
// also show up in the phone's laptop file list.
func receiveDir() string {
	root, err := filepath.Abs(liveCfg().ShareDir)
	if err != nil {
		root = defaultShareDir()
	}
	return filepath.Join(root, receiveFolderName)
}

func currentUploadStore() *uploadStore {
	dir := receiveDir()
	uploadStoresMu.Lock()
	defer uploadStoresMu.Unlock()
	s := uploadStores[dir]
	if s == nil {
		s = newUploadStore(dir)
		s.prune(time.Now())
		uploadStores[dir] = s
	}
	return s
}

func invalidateListing() {
	listingMu.Lock()
	listingCache = nil
	listingMu.Unlock()
}

func writeUploadJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeUploadError(w http.ResponseWriter, err error) {
	var f *uploadFailure
	if errors.As(err, &f) {
		writeUploadJSON(w, f.status, map[string]any{"error": f.msg})
		return
	}
	writeUploadJSON(w, http.StatusBadRequest, map[string]any{"error": "The upload did not finish. It resumes from where it stopped."})
}

const maxStartBody = 64 << 10

func uploadHandler(w http.ResponseWriter, r *http.Request) {
	store := currentUploadStore()
	rest := strings.TrimPrefix(r.URL.Path, "/api/upload/")
	switch {
	case rest == "start" && r.Method == http.MethodPost:
		var req struct {
			Name         string `json:"name"`
			Size         *int64 `json:"size"`
			LastModified int64  `json:"lastModified"`
			RelPath      string `json:"relPath"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, maxStartBody)).Decode(&req); err != nil || req.Size == nil {
			writeUploadJSON(w, http.StatusBadRequest, map[string]any{"error": "Invalid upload request."})
			return
		}
		s, err := store.start(req.Name, *req.Size, req.LastModified, req.RelPath)
		if err != nil {
			writeUploadError(w, err)
			return
		}
		writeUploadJSON(w, http.StatusOK, s.state())
		return
	case rest == "batch" && r.Method == http.MethodPost:
		if r.ContentLength <= 0 {
			writeUploadJSON(w, http.StatusLengthRequired, map[string]any{"error": "A batch needs a Content-Length."})
			return
		}
		if err := os.MkdirAll(store.root, 0o755); err != nil {
			writeUploadJSON(w, http.StatusInternalServerError, map[string]any{"error": "Could not create the receive folder."})
			return
		}
		if free, ok := store.free(store.root); ok && free < uint64(r.ContentLength) {
			writeUploadJSON(w, http.StatusInsufficientStorage, map[string]any{"error": "The laptop needs more free space for these files."})
			return
		}
		files, err := readBatch(r.Body, r.ContentLength, store.root)
		if err != nil {
			writeUploadError(w, err)
			return
		}
		list := make([]map[string]any, 0, len(files))
		var bytes int64
		for _, f := range files {
			list = append(list, map[string]any{"id": f.id, "name": f.name, "bytes": f.size})
			bytes += f.size
		}
		writeUploadJSON(w, http.StatusCreated, map[string]any{"ok": true, "count": len(files), "bytes": bytes, "files": list})
		return
	}
	id, action, _ := strings.Cut(rest, "/")
	if !uploadIDPattern.MatchString(id) {
		writeUploadJSON(w, http.StatusNotFound, map[string]any{"error": "Unknown upload. Start it again."})
		return
	}
	switch {
	case action == "" && r.Method == http.MethodPut:
		if r.ContentLength < 0 {
			writeUploadJSON(w, http.StatusLengthRequired, map[string]any{"error": "A part needs a Content-Length."})
			return
		}
		offset, err := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
		if err != nil {
			writeUploadJSON(w, http.StatusBadRequest, map[string]any{"error": "A part needs ?offset=."})
			return
		}
		done, err := store.write(id, offset, r.ContentLength, r.Body)
		if err != nil {
			writeUploadError(w, err)
			return
		}
		s, _ := store.require(id)
		size := int64(0)
		if s != nil {
			size = s.size
		}
		writeUploadJSON(w, http.StatusOK, map[string]any{"id": id, "received": done, "size": size, "complete": done >= size})
	case action == "" && (r.Method == http.MethodGet || r.Method == http.MethodHead):
		s, err := store.require(id)
		if err != nil {
			writeUploadError(w, err)
			return
		}
		writeUploadJSON(w, http.StatusOK, s.state())
	case action == "" && r.Method == http.MethodDelete:
		ok := store.cancel(id)
		status := http.StatusOK
		if !ok {
			status = http.StatusNotFound
		}
		writeUploadJSON(w, status, map[string]any{"ok": ok})
	case action == "finish" && r.Method == http.MethodPost:
		f, err := store.finish(id)
		if err != nil {
			writeUploadError(w, err)
			return
		}
		writeUploadJSON(w, http.StatusCreated, map[string]any{"ok": true, "id": f.id, "name": f.name, "bytes": f.size})
	default:
		writeUploadJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "Unsupported upload request."})
	}
}
