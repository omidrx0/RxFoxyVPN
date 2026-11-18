// Package ui embeds the panel (HTML, CSS, JavaScript, fonts, images) into the executable.
// The files in this folder are served as they are; the Go server only injects the saved
// language into index.html.
package ui

import "embed"

// FS holds the panel files.
//
//go:embed index.html app.js i18n.js lang assets styles screens services components
var FS embed.FS
