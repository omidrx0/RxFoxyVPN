// Package packaging embeds the application icon (used for the favicon and the window / tray).
package packaging

import _ "embed"

// IconICO is the multi-size Windows icon.
//
//go:embed icon.ico
var IconICO []byte
