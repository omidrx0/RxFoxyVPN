package tun

import (
	"context"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"

	singbox "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	singjson "github.com/sagernet/sing/common/json"

	runtimeassets "rxvpn/bin"
	"rxvpn/internal/config"
	"rxvpn/internal/platform"
	"rxvpn/internal/sysproxy"
)

// Paths locates optional native runtime assets. The sing-box engine is linked
// into RXFOXYVPN; only Wintun's native DLL remains an external runtime asset.
type Paths struct{ SingBox, Wintun string }

// Exists reports whether the native Wintun DLL is available. Sing-box itself is
// part of the Go executable and no sing-box.exe is required.
func (p Paths) Exists() bool {
	st, err := os.Stat(p.Wintun)
	return err == nil && !st.IsDir()
}

// ResolveRuntime extracts Wintun from the executable when built with
// -tags embedruntime, otherwise it looks in bin next to the executable.
func ResolveRuntime() Paths {
	if fsys := runtimeassets.FS(); fsys != nil {
		if p, err := extractRuntime(fsys, config.ExtractedRuntimeDir()); err == nil {
			return p
		}
	}
	var dirs []string
	if exe, err := os.Executable(); err == nil {
		dirs = append(dirs, filepath.Join(filepath.Dir(exe), "bin"))
	}
	if wd, err := os.Getwd(); err == nil {
		dirs = append(dirs, filepath.Join(wd, "bin"))
	}
	for _, d := range dirs {
		p := Paths{Wintun: filepath.Join(d, "wintun.dll")}
		if p.Exists() {
			return p
		}
	}
	if len(dirs) > 0 {
		return Paths{Wintun: filepath.Join(dirs[0], "wintun.dll")}
	}
	return Paths{}
}

func extractRuntime(fsys fs.FS, dir string) (Paths, error) {
	p := Paths{Wintun: filepath.Join(dir, "wintun.dll")}
	data, err := fs.ReadFile(fsys, "wintun.dll")
	if err != nil {
		return p, err
	}
	if st, err := os.Stat(p.Wintun); err == nil && st.Size() == int64(len(data)) {
		return p, nil
	}
	if err := config.WriteFileAtomic(p.Wintun, data, 0o755); err != nil {
		return p, err
	}
	return p, nil
}

// Process owns the in-process sing-box instance. It deliberately does not spawn
// a child process, so the application can stop the engine and release TUN routes
// through sing-box's own lifecycle.
type Process struct {
	mu     sync.Mutex
	engine *singbox.Box
	cancel context.CancelFunc
}

// Start validates the config through sing-box's typed decoder and starts the
// embedded engine. configPath is still written for diagnostics and compatibility.
func (p *Process) Start(rt Paths, configPath, logPath string, socksPort int, s config.Settings) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.engine != nil {
		return sysproxy.NewModeError("TUN engine is already running")
	}
	if !platform.IsWindows {
		return sysproxy.NewModeError("TUN mode is only available on Windows")
	}
	if !platform.IsAdmin() {
		return sysproxy.NewModeError("Run RXFOXYVPN as Administrator to start TUN mode")
	}
	if !rt.Exists() {
		return sysproxy.NewModeError("Wintun runtime is missing; rebuild RXFOXYVPN to install wintun.dll")
	}

	data, err := config.MarshalJSON(MakeConfig(socksPort, s), true)
	if err != nil {
		return err
	}
	if err := config.WriteFileAtomic(configPath, data, 0o644); err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	// sing-box uses a custom decoder for polymorphic inbound/outbound options.
	// Register its built-in protocols before decoding the configuration.
	ctx = include.Context(ctx)
	options, err := singjson.UnmarshalExtendedContext[option.Options](ctx, data)
	if err != nil {
		cancel()
		return sysproxy.NewModeError(fmt.Sprintf("Invalid TUN configuration: %v", err))
	}
	if options.Log == nil {
		options.Log = &option.LogOptions{}
	}
	if logPath != "" {
		options.Log.Output = logPath
	}
	engine, err := singbox.New(singbox.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		return sysproxy.NewModeError(fmt.Sprintf("Could not initialize embedded sing-box: %v", err))
	}
	if err := engine.Start(); err != nil {
		cancel()
		_ = engine.Close()
		return sysproxy.NewModeError(fmt.Sprintf("Could not start embedded sing-box: %v", err))
	}
	p.engine, p.cancel = engine, cancel
	return nil
}

// Stop closes sing-box, which tears down its TUN interface and managed routes.
func (p *Process) Stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.cancel != nil {
		p.cancel()
	}
	if p.engine != nil {
		_ = p.engine.Close()
	}
	p.engine, p.cancel = nil, nil
}
