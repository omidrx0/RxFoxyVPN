// Package web embeds the panel (HTML, CSS, JavaScript, fonts, images) into the executable.
// The files in this folder are served as they are; the Go server only injects the saved
// language into index.html.
package web

import "embed"

// FS holds the panel files.
//
//go:embed index.html style.css app.js i18n.js lang assets
var FS embed.FS
