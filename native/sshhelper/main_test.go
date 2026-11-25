package main

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"os"
	"testing"
	"time"

	"golang.org/x/crypto/ssh/knownhosts"
)

func TestParseArgsAsTheAppCallsIt(t *testing.T) {
	o, err := parseArgs([]string{
		"-i", "/data/key", "-p", "2222", "-o", "HostName=10.0.0.5", "-o", "HostKeyAlias=nas.local",
		"-o", "StrictHostKeyChecking=accept-new", "-o", "UserKnownHostsFile=/data/known", "-o", "BatchMode=yes",
		"backup@nas.local", "-s", "sftp",
	})
	if err != nil {
		t.Fatal(err)
	}
	if o.user != "backup" || o.host != "nas.local" || o.hostName != "10.0.0.5" || o.hostKeyAlias != "nas.local" ||
		o.port != "2222" || o.knownHosts != "/data/known" || o.subsystem != "sftp" || o.strict != "accept-new" ||
		len(o.identities) != 1 || o.identities[0] != "/data/key" {
		t.Fatalf("wrong result: %+v", o)
	}
}

func TestDefaultsAndGluedValues(t *testing.T) {
	o, err := parseArgs([]string{"-i/k", "-p22", "-q", "host", "-s", "sftp"})
	if err != nil || o.port != "22" || o.identities[0] != "/k" || o.hostName != "host" || o.user != "root" {
		t.Fatalf("%+v %v", o, err)
	}
	if o.connectTime != 30*time.Second {
		t.Fatal("default timeout")
	}
}

func TestStrictYesAndTimeout(t *testing.T) {
	o, err := parseArgs([]string{"-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=5", "-o", "ServerAliveInterval=15", "u@h", "-s", "sftp"})
	if err != nil || o.strict != "yes" || o.connectTime != 5*time.Second {
		t.Fatalf("%+v %v", o, err)
	}
}

func TestRefusesWhatItCannotDo(t *testing.T) {
	for _, bad := range [][]string{
		{},                             // no host
		{"host"},                       // nothing to run
		{"-p", "x", "h", "-s", "sftp"}, // bad port
		{"-z", "h", "-s", "sftp"},      // unknown option
	} {
		if _, err := parseArgs(bad); err == nil {
			t.Errorf("expected an error for %v", bad)
		}
	}
}

func TestHostMatchingLikeOpenSSHWritesIt(t *testing.T) {
	// OpenSSH names a host on port 22 plainly, other ports as [host]:port
	if !hostMatches("nas.local", knownhosts.Normalize("nas.local:22")) {
		t.Error("plain name, port 22")
	}
	if !hostMatches("[nas.local]:2222", knownhosts.Normalize("nas.local:2222")) {
		t.Error("bracketed name, other port")
	}
	if !hostMatches("other,[nas.local]:2222", knownhosts.Normalize("nas.local:2222")) {
		t.Error("comma separated list")
	}
	if hostMatches("[nas.local]:2222", knownhosts.Normalize("nas.local:22")) {
		t.Error("a different port must not match")
	}
	// hashed entry, as written with HashKnownHosts yes
	salt := []byte("0123456789abcdefghij")
	m := hmac.New(sha1.New, salt)
	m.Write([]byte("[nas.local]:2222"))
	hashed := "|1|" + base64.StdEncoding.EncodeToString(salt) + "|" + base64.StdEncoding.EncodeToString(m.Sum(nil))
	if !hostMatches(hashed, knownhosts.Normalize("nas.local:2222")) || hostMatches(hashed, "[other]:2222") {
		t.Error("hashed entry")
	}
}

func TestKnownAlgorithmsReadsTheFile(t *testing.T) {
	line := "[nas.local]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\n"
	f := t.TempDir() + "/kh"
	if err := os.WriteFile(f, []byte("# comment\n\n"+line), 0o600); err != nil {
		t.Fatal(err)
	}
	got := knownAlgorithms(f, "nas.local:2222")
	if len(got) != 1 || got[0] != "ssh-ed25519" {
		t.Fatalf("got %v", got)
	}
	if len(knownAlgorithms(f, "unknown:2222")) != 0 || knownAlgorithms(f+".missing", "x:1") != nil {
		t.Error("unknown host or file must give nothing")
	}
}
