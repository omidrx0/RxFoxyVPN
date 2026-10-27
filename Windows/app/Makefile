# Developer shortcuts (any OS). The Windows release build is scripts/build.ps1.
.PHONY: test vet fmt tidy build-windows

tidy:
	go mod tidy

test:
	go test ./...

vet:
	go vet ./...
	GOOS=windows GOARCH=amd64 go vet ./...

fmt:
	gofmt -l -w .

build-windows:
	GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-H windowsgui -s -w" -o dist/RXFOXYVPN.exe ./cmd/rxvpn
