// Command sshhelper is the small ssh client Sunrestic hands to restic as its "sftp.command".
//
// restic's sftp backend starts `ssh [options] user@host -s sftp` and talks to the program over stdin and stdout.
// This program understands exactly the options the app uses, nothing more:
//
//	-i keyfile  -p port  -l user  -o HostName=ip  -o HostKeyAlias=name  -o StrictHostKeyChecking=accept-new|yes
//	-o UserKnownHostsFile=file  -o BatchMode=yes  -o ConnectTimeout=n  user@host  -s sftp
//
// It always works without prompts. Unknown hosts are remembered (accept-new) and a host whose key changed is refused.
// Other ssh options are accepted and ignored. It is built from source together with restic (scripts/build-native.sh).
package main

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
	"golang.org/x/crypto/ssh/knownhosts"
)

type options struct {
	identities   []string
	port         string
	user         string
	host         string
	hostName     string // the address to connect to (HostName=), defaults to host
	hostKeyAlias string
	strict       string // "accept-new" or "yes"
	knownHosts   string
	connectTime  time.Duration
	subsystem    string
	command      string
}

// flags that take no value; they are accepted and ignored
const noValueFlags = "vqxTanN46CgKkXYAMfV"

// flags that take a value we do not use; the value is skipped
const skippedValueFlags = "FJLRDWbcEeIBmOQSw"

func parseArgs(args []string) (*options, error) {
	o := &options{port: "22", strict: "accept-new", connectTime: 30 * time.Second}
	var positional []string
	wantSubsystem := false
	for i := 0; i < len(args); i++ {
		a := args[i]
		if len(a) < 2 || a[0] != '-' {
			positional = append(positional, a)
			continue
		}
		flag := a[1]
		takes := func() (string, error) {
			if len(a) > 2 { // -p22
				return a[2:], nil
			}
			if i+1 >= len(args) {
				return "", fmt.Errorf("option -%c needs a value", flag)
			}
			i++
			return args[i], nil
		}
		switch {
		case flag == 's':
			wantSubsystem = true
		case flag == 'i':
			v, err := takes()
			if err != nil {
				return nil, err
			}
			o.identities = append(o.identities, v)
		case flag == 'p':
			v, err := takes()
			if err != nil {
				return nil, err
			}
			o.port = v
		case flag == 'l':
			v, err := takes()
			if err != nil {
				return nil, err
			}
			o.user = v
		case flag == 'o':
			v, err := takes()
			if err != nil {
				return nil, err
			}
			if err := o.setOption(v); err != nil {
				return nil, err
			}
		case strings.IndexByte(skippedValueFlags, flag) >= 0:
			if _, err := takes(); err != nil {
				return nil, err
			}
		case strings.IndexByte(noValueFlags, flag) >= 0:
			// ignored
		default:
			return nil, fmt.Errorf("unsupported option -%c", flag)
		}
	}
	if len(positional) == 0 {
		return nil, fmt.Errorf("no host given")
	}
	dest := positional[0]
	if at := strings.LastIndexByte(dest, '@'); at >= 0 {
		if o.user == "" {
			o.user = dest[:at]
		}
		dest = dest[at+1:]
	}
	o.host = dest
	if rest := positional[1:]; len(rest) > 0 {
		if wantSubsystem {
			o.subsystem = rest[0]
		} else {
			o.command = strings.Join(rest, " ")
		}
	}
	if o.subsystem == "" && o.command == "" {
		return nil, fmt.Errorf("nothing to run: expected -s sftp")
	}
	if o.hostName == "" {
		o.hostName = o.host
	}
	if o.hostKeyAlias == "" {
		o.hostKeyAlias = o.host
	}
	if o.user == "" {
		o.user = os.Getenv("SSH_USER")
	}
	if o.user == "" {
		o.user = "root"
	}
	if o.knownHosts == "" {
		o.knownHosts = os.Getenv("HOME") + "/.ssh/known_hosts"
	}
	if _, err := strconv.Atoi(o.port); err != nil {
		return nil, fmt.Errorf("bad port %q", o.port)
	}
	return o, nil
}

func (o *options) setOption(kv string) error {
	k, v, ok := strings.Cut(kv, "=")
	if !ok {
		k, v, ok = strings.Cut(kv, " ")
	}
	if !ok {
		return fmt.Errorf("bad -o option %q", kv)
	}
	v = strings.Trim(strings.TrimSpace(v), `"`)
	switch strings.ToLower(strings.TrimSpace(k)) {
	case "hostname":
		o.hostName = v
	case "hostkeyalias":
		o.hostKeyAlias = v
	case "stricthostkeychecking":
		switch strings.ToLower(v) {
		case "yes", "true":
			o.strict = "yes"
		default: // accept-new, no, off, ask: with no prompts they all mean "remember new hosts, refuse changed ones"
			o.strict = "accept-new"
		}
	case "userknownhostsfile":
		o.knownHosts = v
	case "port":
		o.port = v
	case "user":
		o.user = v
	case "identityfile":
		o.identities = append(o.identities, v)
	case "connecttimeout":
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			o.connectTime = time.Duration(n) * time.Second
		}
	}
	return nil // every other option (BatchMode, ServerAlive…, Compression…) is accepted and ignored
}

func loadSigners(files []string) ([]ssh.Signer, error) {
	var signers []ssh.Signer
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			return nil, fmt.Errorf("cannot read key %s: %v", f, err)
		}
		s, err := ssh.ParsePrivateKey(data)
		if err != nil {
			if _, ok := err.(*ssh.PassphraseMissingError); ok {
				return nil, fmt.Errorf("key %s is protected by a passphrase, which is not supported", f)
			}
			return nil, fmt.Errorf("cannot use key %s: %v", f, err)
		}
		signers = append(signers, s)
	}
	if len(signers) == 0 {
		return nil, fmt.Errorf("no key given (-i)")
	}
	return signers, nil
}

// hostKeys checks the server's key against the known_hosts file and remembers new hosts when allowed.
func hostKeys(o *options) (ssh.HostKeyCallback, []string, string, error) {
	if _, err := os.Stat(o.knownHosts); os.IsNotExist(err) {
		if dir := o.knownHosts[:max(strings.LastIndexByte(o.knownHosts, '/'), 0)]; dir != "" {
			_ = os.MkdirAll(dir, 0o700)
		}
		if err := os.WriteFile(o.knownHosts, nil, 0o600); err != nil {
			return nil, nil, "", fmt.Errorf("cannot create %s: %v", o.knownHosts, err)
		}
	}
	check, err := knownhosts.New(o.knownHosts)
	if err != nil {
		return nil, nil, "", fmt.Errorf("cannot read %s: %v", o.knownHosts, err)
	}
	label := net.JoinHostPort(o.hostKeyAlias, o.port) // what the known_hosts entry is named after
	cb := func(hostname string, remote net.Addr, key ssh.PublicKey) error {
		err := check(hostname, remote, key)
		if err == nil {
			return nil
		}
		ke, ok := err.(*knownhosts.KeyError)
		if !ok {
			return err
		}
		if len(ke.Want) > 0 {
			return fmt.Errorf("REMOTE HOST IDENTIFICATION HAS CHANGED for %s (%s). Someone may be doing something nasty, or the server was reinstalled. "+
				"If you expected this, remove its line from %s", o.hostKeyAlias, ssh.FingerprintSHA256(key), o.knownHosts)
		}
		if o.strict == "yes" {
			return fmt.Errorf("host %s is not in %s and StrictHostKeyChecking is on", o.hostKeyAlias, o.knownHosts)
		}
		f, err := os.OpenFile(o.knownHosts, os.O_APPEND|os.O_WRONLY, 0o600)
		if err != nil {
			return fmt.Errorf("cannot remember the host key: %v", err)
		}
		defer f.Close()
		_, err = fmt.Fprintln(f, knownhosts.Line([]string{label}, key))
		return err
	}
	return cb, knownAlgorithms(o.knownHosts, label), label, nil
}

// knownAlgorithms lists the host key types already remembered for [label] (most preferred first), so the client asks the
// server for one of those. Without this a server offering several key types could be matched against the wrong one.
func knownAlgorithms(file, label string) []string {
	f, err := os.Open(file)
	if err != nil {
		return nil
	}
	defer f.Close()
	want := knownhosts.Normalize(label)
	var out []string
	seen := map[string]bool{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] == '#' {
			continue
		}
		marker, _, key, _, _, err := ssh.ParseKnownHosts([]byte(line))
		if err != nil || marker != "" || key == nil {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 3 || !hostMatches(fields[0], want) {
			continue
		}
		for _, a := range algorithmsFor(key.Type()) {
			if !seen[a] {
				seen[a] = true
				out = append(out, a)
			}
		}
	}
	return out
}

// hostMatches checks one known_hosts host field (comma separated, plain or hashed |1|salt|hash) against [want].
func hostMatches(field, want string) bool {
	for _, h := range strings.Split(field, ",") {
		if strings.HasPrefix(h, "|1|") {
			parts := strings.Split(h, "|") // "", "1", salt, hash
			if len(parts) != 4 {
				continue
			}
			salt, err1 := base64.StdEncoding.DecodeString(parts[2])
			sum, err2 := base64.StdEncoding.DecodeString(parts[3])
			if err1 != nil || err2 != nil {
				continue
			}
			m := hmac.New(sha1.New, salt)
			m.Write([]byte(want))
			if hmac.Equal(m.Sum(nil), sum) {
				return true
			}
		} else if knownhosts.Normalize(h) == want {
			return true
		}
	}
	return false
}

func algorithmsFor(keyType string) []string {
	if keyType == ssh.KeyAlgoRSA {
		return []string{ssh.KeyAlgoRSASHA512, ssh.KeyAlgoRSASHA256, ssh.KeyAlgoRSA}
	}
	return []string{keyType}
}

func run(args []string) (int, error) {
	o, err := parseArgs(args)
	if err != nil {
		return 255, err
	}
	signers, err := loadSigners(o.identities)
	if err != nil {
		return 255, err
	}
	cb, algos, label, err := hostKeys(o)
	if err != nil {
		return 255, err
	}
	cfg := &ssh.ClientConfig{
		User:              o.user,
		Auth:              []ssh.AuthMethod{ssh.PublicKeys(signers...)},
		HostKeyCallback:   cb,
		HostKeyAlgorithms: algos, // the key types already remembered for this host (nil = the library default)
		Timeout:           o.connectTime,
	}
	dialer := net.Dialer{Timeout: o.connectTime, KeepAlive: 30 * time.Second}
	ctx, cancel := context.WithTimeout(context.Background(), o.connectTime)
	defer cancel()
	conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(o.hostName, o.port))
	if err != nil {
		return 255, fmt.Errorf("ssh: connect to host %s port %s: %v", o.hostName, o.port, err)
	}
	sc, chans, reqs, err := ssh.NewClientConn(conn, label, cfg)
	if err != nil {
		conn.Close()
		return 255, err
	}
	client := ssh.NewClient(sc, chans, reqs)
	defer client.Close()
	sess, err := client.NewSession()
	if err != nil {
		return 255, err
	}
	defer sess.Close()
	if o.subsystem != "" {
		return runSubsystem(sess, o.subsystem)
	}
	sess.Stdin = os.Stdin
	sess.Stdout = os.Stdout
	sess.Stderr = os.Stderr
	if err = sess.Start(o.command); err != nil {
		return 255, err
	}
	if err := sess.Wait(); err != nil {
		if ee, ok := err.(*ssh.ExitError); ok {
			return ee.ExitStatus(), nil
		}
		if _, ok := err.(*ssh.ExitMissingError); ok {
			return 0, nil // the server closed the channel without an exit status, as sftp servers often do
		}
		return 255, err
	}
	return 0, nil
}

// runSubsystem runs a subsystem such as sftp. The ssh library does not move data for subsystems by itself, so the
// three streams are connected here. The program ends when the server closes its side (restic closes our stdin when it is done).
func runSubsystem(sess *ssh.Session, name string) (int, error) {
	stdin, err := sess.StdinPipe()
	if err != nil {
		return 255, err
	}
	stdout, err := sess.StdoutPipe()
	if err != nil {
		return 255, err
	}
	stderr, err := sess.StderrPipe()
	if err != nil {
		return 255, err
	}
	if err := sess.RequestSubsystem(name); err != nil {
		return 255, err
	}
	go func() {
		_, _ = io.Copy(stdin, os.Stdin)
		_ = stdin.Close() // tells the server there is no more input
	}()
	errDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(os.Stderr, stderr)
		close(errDone)
	}()
	_, _ = io.Copy(os.Stdout, stdout)
	select { // let the last error lines through, but never hang on them
	case <-errDone:
	case <-time.After(500 * time.Millisecond):
	}
	return 0, nil
}

func main() {
	code, err := run(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
	}
	os.Exit(code)
}
