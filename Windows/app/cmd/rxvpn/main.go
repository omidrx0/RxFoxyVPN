// Command rxvpn is the RXFOXYVPN desktop panel: a native window plus a tray icon (or an
// Edge/Chrome app window with --browser) around a local web UI that controls a Firefox VPN
// connection through a local SOCKS5 / HTTP proxy, the Windows system proxy or a TUN adapter.
//
//	rxvpn.exe                       app window + tray icon
//	rxvpn.exe --browser             Edge/Chrome app window instead of the native window
//	rxvpn.exe --port 8787 --socks-port 10808 --http-port 10809
//	rxvpn.exe --api-proxy socks5h://127.0.0.1:1080   (if Firefox Accounts is blocked)
package main

import (
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"rxvpn/internal/app"
	"rxvpn/internal/config"
	"rxvpn/internal/desktop"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
	"rxvpn/internal/server"
)

// version is set at build time: -ldflags "-X main.version=1.2.3"
var version = "dev"

func init() {
	// the native window's message loop must stay on the main OS thread
	runtime.LockOSThread()
}

func main() { os.Exit(run()) }

type options struct {
	port, socksPort, httpPort int
	apiProxy, caFile          string
	insecure, noBrowser       bool
	nativeFrame, browser      bool
	verbose, showVersion      bool
}

func parseFlags() options {
	var o options
	fs := flag.NewFlagSet("rxvpn", flag.ExitOnError)
	fs.IntVar(&o.port, "port", 0, "panel port (default: random free port)")
	fs.IntVar(&o.socksPort, "socks-port", 10808, "local SOCKS5 port")
	fs.IntVar(&o.httpPort, "http-port", 10809, "local HTTP proxy port (system proxy mode)")
	fs.StringVar(&o.apiProxy, "api-proxy", "", "proxy URL used ONLY for sign-in / token / server-list / flag downloads")
	fs.StringVar(&o.caFile, "ca", "", "extra CA bundle (PEM) to trust for the tunnel servers")
	fs.BoolVar(&o.insecure, "insecure", false, "DO NOT verify the tunnel certificate (unsafe)")
	fs.BoolVar(&o.noBrowser, "no-browser", false, "do not open any window (panel only reachable by URL)")
	fs.BoolVar(&o.nativeFrame, "native-frame", false, "use the normal Windows title bar instead of the custom one")
	fs.BoolVar(&o.browser, "browser", false, "use an Edge/Chrome app window instead of the native window + tray icon")
	fs.BoolVar(&o.verbose, "v", false, "verbose log")
	fs.BoolVar(&o.verbose, "verbose", false, "verbose log")
	fs.BoolVar(&o.showVersion, "version", false, "print the version and exit")
	_ = fs.Parse(os.Args[1:])
	return o
}

func setupLogging(verbose bool) {
	level := slog.LevelInfo
	if verbose {
		level = slog.LevelDebug
	}
	var w io.Writer = os.Stderr
	if f, err := os.OpenFile(config.AppLogPath(), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		w = io.MultiWriter(f, os.Stderr) // the file first: a missing console must not lose log lines
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: level})))
}

func run() int {
	config.LoadLang()
	o := parseFlags()
	if o.showVersion {
		fmt.Println("RXFOXYVPN", version)
		return 0
	}
	setupLogging(o.verbose)

	if platform.IsWindows && !platform.IsAdmin() {
		msg := "RXFOXYVPN needs Administrator privileges. Please close it and run it as Administrator."
		platform.MessageBox("RXFOXYVPN - Administrator required", msg, platform.MBError)
		slog.Error(msg)
		fmt.Fprintln(os.Stderr, msg)
		return 1
	}
	native := !o.browser && !o.noBrowser
	if native && platform.AlreadyRunning() {
		platform.MessageBox("RXFOXYVPN", locale.Tr(
			"برنامه در حال اجراست.\nآیکونش را کنار ساعت ویندوز پیدا کن.",
			"RXFOXYVPN is already running.\nFind its icon next to the Windows clock."), platform.MBInfo)
		return 0
	}
	if o.insecure {
		fmt.Fprintln(os.Stderr, "WARNING: --insecure disables certificate checks; your proxy pass can be stolen.")
	}

	a, err := app.New(app.Options{
		SocksPort: o.socksPort, HTTPPort: o.httpPort, APIProxy: o.apiProxy,
		TLS: foxy.TLSOptions{CAFile: o.caFile, Insecure: o.insecure},
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "could not start:", err)
		slog.Error("could not start", "err", err)
		return 1
	}
	srv, url, err := server.StartWithVersion(a, o.port, version)
	if err != nil {
		msg := fmt.Sprintf("Could not open the panel port %d: %v  (try --port)", o.port, err)
		fmt.Fprintln(os.Stderr, msg)
		slog.Error(msg)
		a.Close()
		return 1
	}
	fmt.Fprintf(os.Stderr, "RXFOXYVPN panel: %s   (Ctrl+C to quit)\n", url)

	defer func() {
		a.Close() // disconnects: restores the system proxy, stops TUN
		srv.Shutdown()
	}()

	if native {
		err := desktop.Run(a, url, desktop.Options{NativeFrame: o.nativeFrame})
		if err == nil {
			return 0
		}
		slog.Warn("native window unavailable, falling back to a browser window", "err", err)
		fmt.Fprintln(os.Stderr, "native window unavailable (is the WebView2 runtime installed?); opening a browser window")
	}
	if !o.noBrowser {
		time.AfterFunc(600*time.Millisecond, func() { desktop.OpenAppWindow(url) })
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	fmt.Fprintln(os.Stderr, "\nBye.")
	return 0
}
