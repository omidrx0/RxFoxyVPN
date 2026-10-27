//go:build windows

package platform

import (
	"errors"
	"unsafe"

	"golang.org/x/sys/windows"
)

// DPAPI protects data with the Windows Data Protection API (current-user scope), the same
// scheme the Python version used, so already saved accounts stay readable.
type DPAPI struct{}

func (DPAPI) Protect(plain []byte) ([]byte, error) {
	if len(plain) == 0 {
		return nil, errors.New("nothing to protect")
	}
	in := windows.DataBlob{Size: uint32(len(plain)), Data: &plain[0]}
	var out windows.DataBlob
	desc, err := windows.UTF16PtrFromString("RXVPN saved account")
	if err != nil {
		return nil, err
	}
	if err := windows.CryptProtectData(&in, desc, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out); err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte(nil), unsafe.Slice(out.Data, out.Size)...), nil
}

func (DPAPI) Unprotect(sealed []byte) ([]byte, error) {
	if len(sealed) == 0 {
		return nil, errors.New("nothing to unprotect")
	}
	in := windows.DataBlob{Size: uint32(len(sealed)), Data: &sealed[0]}
	var out windows.DataBlob
	if err := windows.CryptUnprotectData(&in, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out); err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte(nil), unsafe.Slice(out.Data, out.Size)...), nil
}
