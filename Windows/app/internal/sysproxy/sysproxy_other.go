//go:build !windows

package sysproxy

import (
	"net/http"
	"net/url"
)

func supported() bool { return false }

func capture() (snapshot, error) {
	return nil, NewModeError("Windows system proxy is only available on Windows")
}

func write(snapshot) error {
	return NewModeError("Windows system proxy is only available on Windows")
}

func systemProxyFor(*http.Request) (*url.URL, error) { return nil, nil }
