# Golden vectors

golden.json was produced by running the original Python implementation
(foxy_bridge.py, settings.py, tun.py) on fixed inputs. The Go tests assert that the Go
port returns exactly the same results; that is the proof the logic was carried over unchanged.
