package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// This userspace peer gives the Windows driver an authenticated destination
// that cannot be reached through another Windows adapter.
func main() {
	var input struct {
		PrivateKey string
		ClientKey  string
		ServerIP   string
		ClientIP   string
	}
	require(json.NewDecoder(os.Stdin).Decode(&input))
	serverIP, err := netip.ParseAddr(input.ServerIP)
	require(err)
	clientIP, err := netip.ParseAddr(input.ClientIP)
	require(err)

	tun, stack, err := netstack.CreateNetTUN([]netip.Addr{serverIP}, nil, 1280)
	require(err)
	peer := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wireguard-fixture: "))
	defer peer.Close()
	require(peer.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=%s/32\n",
		input.PrivateKey, input.ClientKey, clientIP)))
	require(peer.Up())

	state, err := peer.IpcGet()
	require(err)
	udpPort := 0
	for _, line := range strings.Split(state, "\n") {
		if value, ok := strings.CutPrefix(line, "listen_port="); ok {
			udpPort, err = strconv.Atoi(value)
			require(err)
		}
	}
	if udpPort <= 0 {
		log.Fatal("userspace WireGuard did not allocate a UDP port")
	}

	listener, err := stack.ListenTCP(&net.TCPAddr{IP: net.IP(serverIP.AsSlice())})
	require(err)
	defer listener.Close()
	fmt.Printf("READY %d %d\n", udpPort, listener.Addr().(*net.TCPAddr).Port)

	// The test process keeps this pipe open until its cleanup.
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		_ = listener.Close()
	}()
	for {
		client, err := listener.Accept()
		if err != nil {
			if !strings.Contains(err.Error(), "closed") {
				log.Print(err)
			}
			return
		}
		go func() {
			defer client.Close()
			_, _ = io.Copy(client, client)
		}()
	}
}

func require(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
