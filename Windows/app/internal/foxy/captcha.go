package foxy

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"
)

const (
	maxCaptchaImageBytes      = 2 * 1024 * 1024 // 2 MiB
	maxCaptchaSessionRestarts = 8
	interactiveCaptchaTimeout = 12 * time.Minute
)

var expiredCaptchaPostBackCodes = map[int]bool{
	400: true,
	401: true,
	403: true,
	406: true,
	410: true,
	422: true,
}

// CaptchaNoticeKind mirrors Android's CaptchaNoticeKind.
type CaptchaNoticeKind string

const (
	CaptchaNoticeNone          CaptchaNoticeKind = ""
	CaptchaNoticeWrongAnswer   CaptchaNoticeKind = "wrong_answer"
	CaptchaNoticeExpired       CaptchaNoticeKind = "expired"
	CaptchaNoticeDecodeFailed  CaptchaNoticeKind = "decode_failed"
	CaptchaNoticeRefreshFailed CaptchaNoticeKind = "refresh_failed"
)

// CaptchaPrompt is the UI state exposed to the desktop panel while Fastly awaits manual CAPTCHA input.
type CaptchaPrompt struct {
	PromptID     int64             `json:"prompt_id"`
	ImageDataURI string            `json:"image_data_uri,omitempty"`
	MimeType     string            `json:"mime_type"`
	Round        int               `json:"round"`
	Notice       CaptchaNoticeKind `json:"notice,omitempty"`
	Loading      bool              `json:"loading"`
}

type captchaActionKind int

const (
	captchaActionSubmit captchaActionKind = iota + 1
	captchaActionRefresh
	captchaActionCancel
)

type captchaUserAction struct {
	kind   captchaActionKind
	answer string
}

type interactiveCaptchaCtxKey struct{}

// WithInteractiveCaptcha marks ctx as originating from an interactive sign-in / verification flow
// that may prompt the user in the UI if Fastly serves an image CAPTCHA.
func WithInteractiveCaptcha(ctx context.Context) context.Context {
	return context.WithValue(ctx, interactiveCaptchaCtxKey{}, true)
}

func allowsInteractiveCaptcha(ctx context.Context) bool {
	v, _ := ctx.Value(interactiveCaptchaCtxKey{}).(bool)
	return v
}

// DecodedCaptchaImage holds verified image bytes and a clean browser-renderable data URI.
type DecodedCaptchaImage struct {
	Bytes    []byte
	MimeType string
	DataURI  string
}

// DecodeCaptchaDataURI validates and decodes a Fastly image_b64 string (data:image/*;base64,... or raw base64),
// verifying PNG, JPEG, GIF, WebP, BMP, or sanitized SVG magic signatures.
func DecodeCaptchaDataURI(raw string) (*DecodedCaptchaImage, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, errors.New("empty CAPTCHA data")
	}

	b64Part := trimmed
	if strings.HasPrefix(strings.ToLower(trimmed), "data:") {
		comma := strings.IndexByte(trimmed, ',')
		if comma < 0 {
			return nil, errors.New("malformed data URI")
		}
		meta := strings.ToLower(trimmed[5:comma])
		if !strings.HasPrefix(meta, "image/") || !strings.Contains(meta, ";base64") {
			return nil, errors.New("unsupported CAPTCHA data URI header")
		}
		b64Part = trimmed[comma+1:]
	}

	// Strip whitespace inside base64 payload.
	var compact strings.Builder
	compact.Grow(len(b64Part))
	for i := 0; i < len(b64Part); i++ {
		switch b64Part[i] {
		case ' ', '\t', '\r', '\n':
		default:
			compact.WriteByte(b64Part[i])
		}
	}
	cleanB64 := compact.String()
	if cleanB64 == "" || len(cleanB64) > (maxCaptchaImageBytes*4/3)+64 {
		return nil, errors.New("CAPTCHA base64 length out of range")
	}

	decoded, err := decodeFlexibleBase64(cleanB64)
	if err != nil || len(decoded) == 0 || len(decoded) > maxCaptchaImageBytes {
		return nil, errors.New("failed to decode CAPTCHA base64 bytes")
	}

	mimeType, ok := sniffSafeCaptchaImageMime(decoded)
	if !ok {
		return nil, errors.New("unrecognized or unsafe CAPTCHA image format")
	}

	safeDataURI := "data:" + mimeType + ";base64," + base64.StdEncoding.EncodeToString(decoded)
	return &DecodedCaptchaImage{
		Bytes:    decoded,
		MimeType: mimeType,
		DataURI:  safeDataURI,
	}, nil
}

func decodeFlexibleBase64(s string) ([]byte, error) {
	if strings.ContainsAny(s, "-_") {
		if b, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "=")); err == nil {
			return b, nil
		}
	}
	if b, err := base64.StdEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawStdEncoding.DecodeString(strings.TrimRight(s, "="))
}

func sniffSafeCaptchaImageMime(b []byte) (string, bool) {
	// PNG
	if len(b) >= 8 && bytes.Equal(b[:8], []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}) {
		return "image/png", true
	}
	// JPEG
	if len(b) >= 3 && b[0] == 0xff && b[1] == 0xd8 && b[2] == 0xff {
		return "image/jpeg", true
	}
	// GIF
	if len(b) >= 6 && (bytes.Equal(b[:6], []byte("GIF87a")) || bytes.Equal(b[:6], []byte("GIF89a"))) {
		return "image/gif", true
	}
	// WebP: RIFF....WEBP
	if len(b) >= 12 && bytes.Equal(b[:4], []byte("RIFF")) && bytes.Equal(b[8:12], []byte("WEBP")) {
		return "image/webp", true
	}
	// BMP
	if len(b) >= 26 && b[0] == 'B' && b[1] == 'M' {
		return "image/bmp", true
	}
	// SVG (must be valid UTF-8 XML/SVG without active script/event handlers)
	if utf8.Valid(b) {
		txt := strings.TrimSpace(strings.TrimPrefix(string(b), "\uFEFF"))
		lower := strings.ToLower(txt)
		if (strings.HasPrefix(lower, "<svg") || (strings.HasPrefix(lower, "<?xml") && strings.Contains(lower, "<svg"))) &&
			strings.Contains(lower, "</svg>") &&
			!strings.Contains(lower, "<script") &&
			!strings.Contains(lower, "javascript:") &&
			!strings.Contains(lower, "<foreignobject") &&
			!strings.Contains(lower, "onload=") &&
			!strings.Contains(lower, "onerror=") {
			return "image/svg+xml", true
		}
	}
	return "", false
}

// CaptchaBroker coordinates interactive Fastly CAPTCHA prompts between ControlPlane.SolveChallenge
// and the HTTP / WebView2 UI endpoints.
type CaptchaBroker struct {
	mu       sync.Mutex
	nextID   atomic.Int64
	active   *CaptchaPrompt
	actionCh chan captchaUserAction
}

// NewCaptchaBroker creates an idle CaptchaBroker.
func NewCaptchaBroker() *CaptchaBroker {
	return &CaptchaBroker{}
}

// Current returns a snapshot of the active CAPTCHA prompt (or nil when none is active).
func (b *CaptchaBroker) Current() *CaptchaPrompt {
	if b == nil {
		return nil
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.active == nil {
		return nil
	}
	cp := *b.active
	return &cp
}

// Clear removes any active CAPTCHA prompt (called when a challenge completes or fails).
func (b *CaptchaBroker) Clear() {
	if b == nil {
		return
	}
	b.mu.Lock()
	b.active = nil
	b.actionCh = nil
	b.mu.Unlock()
}

// Submit delivers the user's CAPTCHA answer to the waiting solver goroutine.
func (b *CaptchaBroker) Submit(answer string) error {
	trimmed := strings.TrimSpace(answer)
	if trimmed == "" {
		return errors.New("CAPTCHA answer is empty")
	}
	b.mu.Lock()
	ch := b.actionCh
	if b.active != nil {
		b.active.Loading = true
	}
	b.mu.Unlock()
	if ch == nil {
		return errors.New("no active CAPTCHA session")
	}
	select {
	case ch <- captchaUserAction{kind: captchaActionSubmit, answer: trimmed}:
		return nil
	default:
		return nil
	}
}

// Refresh asks the waiting solver goroutine to fetch a fresh CAPTCHA image.
func (b *CaptchaBroker) Refresh() error {
	b.mu.Lock()
	ch := b.actionCh
	if b.active != nil {
		b.active.Loading = true
	}
	b.mu.Unlock()
	if ch == nil {
		return errors.New("no active CAPTCHA session")
	}
	select {
	case ch <- captchaUserAction{kind: captchaActionRefresh}:
		return nil
	default:
		return nil
	}
}

// Cancel aborts any active interactive CAPTCHA session cleanly.
func (b *CaptchaBroker) Cancel() {
	if b == nil {
		return
	}
	b.mu.Lock()
	ch := b.actionCh
	b.active = nil
	b.actionCh = nil
	b.mu.Unlock()
	if ch != nil {
		select {
		case ch <- captchaUserAction{kind: captchaActionCancel}:
		default:
		}
	}
}

func (b *CaptchaBroker) promptAndAwait(ctx context.Context, img *DecodedCaptchaImage, round int, notice CaptchaNoticeKind) (captchaUserAction, error) {
	id := b.nextID.Add(1)
	ch := make(chan captchaUserAction, 1)
	prompt := &CaptchaPrompt{
		PromptID: id,
		MimeType: "image/png",
		Round:    round,
		Notice:   notice,
		Loading:  false,
	}
	if img != nil {
		prompt.ImageDataURI = img.DataURI
		prompt.MimeType = img.MimeType
	}

	b.mu.Lock()
	b.active = prompt
	b.actionCh = ch
	b.mu.Unlock()

	timer := time.NewTimer(interactiveCaptchaTimeout)
	defer timer.Stop()

	select {
	case act := <-ch:
		if act.kind == captchaActionCancel {
			b.Clear()
			return captchaUserAction{}, CaptchaCancelledError{}
		}
		return act, nil
	case <-ctx.Done():
		b.Clear()
		if errors.Is(ctx.Err(), context.Canceled) {
			return captchaUserAction{}, CaptchaCancelledError{}
		}
		return captchaUserAction{}, ctx.Err()
	case <-timer.C:
		b.Clear()
		return captchaUserAction{}, &FastlyChallengeError{Msg: "CAPTCHA session timed out"}
	}
}
