// Package locale holds the language chosen in the panel and the Tr helper used for
// messages that the backend itself produces (errors, tray texts).
package locale

import "sync"

// Supported lists every language the panel ships.
var Supported = []string{"fa", "en", "ar", "ru", "zh", "ko"}

var (
	mu      sync.RWMutex
	current = "en"
)

// Valid reports whether lang is one of the shipped languages.
func Valid(lang string) bool {
	for _, l := range Supported {
		if l == lang {
			return true
		}
	}
	return false
}

// Get returns the current panel language.
func Get() string {
	mu.RLock()
	defer mu.RUnlock()
	return current
}

// Set changes the current language. Unknown codes are ignored.
func Set(lang string) {
	if !Valid(lang) {
		return
	}
	mu.Lock()
	current = lang
	mu.Unlock()
}

// IsRTL reports whether the language is written right to left.
func IsRTL(lang string) bool { return lang == "fa" || lang == "ar" }

// Tr picks the Persian or the English text according to the panel language
// (every language except Persian falls back to English, as before).
func Tr(fa, en string) string {
	if Get() == "fa" {
		return fa
	}
	return en
}
