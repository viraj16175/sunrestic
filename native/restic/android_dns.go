package main

// Added to restic by Sunrestic (see scripts/build-native.sh).
// Android has no /etc/resolv.conf, so Go's own resolver would ask 127.0.0.1:53 and every lookup would fail.
// Set RESTIC_DNS="1.1.1.1:53" (or your own resolver) and all of restic's Go networking uses it.

import (
	"context"
	"net"
	"os"
)

func init() {
	dns := os.Getenv("RESTIC_DNS")
	if dns == "" {
		return
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{}
			return d.DialContext(ctx, network, dns)
		},
	}
}
