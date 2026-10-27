//go:build !embedruntime

// Package runtimeassets is empty unless built with -tags embedruntime.
package runtimeassets

import "io/fs"

func FS() fs.FS { return nil }
