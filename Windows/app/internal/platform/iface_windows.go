//go:build windows

package platform

import (
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	iphlpapi            = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetBestInterface = iphlpapi.NewProc("GetBestInterface")
)

// IP_UNICAST_IF / IPV6_UNICAST_IF
const unicastIF = 31

// DefaultIPv4InterfaceIndex returns the index of the physical adapter that routes to the
// internet. 1.1.1.1 is 0x01010101 in either byte order, so no conversion is needed.
func DefaultIPv4InterfaceIndex() (int, bool) {
	var idx uint32
	r, _, _ := procGetBestInterface.Call(uintptr(0x01010101), uintptr(unsafe.Pointer(&idx)))
	if r != 0 {
		return 0, false
	}
	return int(idx), true
}

func htonl(v uint32) uint32 { return v<<24 | (v&0xff00)<<8 | (v>>8)&0xff00 | v>>24 }

// DialControl returns a net.Dialer.Control function that pins the socket to one network
// interface, so ping probes bypass the TUN adapter and measure the real link.
func DialControl(ifIndex int) func(network, address string, c syscall.RawConn) error {
	return func(network, address string, c syscall.RawConn) error {
		var sockErr error
		err := c.Control(func(fd uintptr) {
			h := windows.Handle(fd)
			if strings.HasSuffix(network, "6") {
				sockErr = windows.SetsockoptInt(h, windows.IPPROTO_IPV6, unicastIF, ifIndex) // host order
			} else {
				sockErr = windows.SetsockoptInt(h, windows.IPPROTO_IP, unicastIF, int(htonl(uint32(ifIndex))))
			}
		})
		if err != nil {
			return err
		}
		return sockErr
	}
}
