//go:build embedruntime

// Package runtimeassets embeds only Wintun's native DLL. The sing-box engine
// is linked directly into RXFOXYVPN's Go executable.
package runtimeassets

import (
	"embed"
	"io/fs"
)

//go:embed wintun.dll
var files embed.FS

func FS() fs.FS { return files }
