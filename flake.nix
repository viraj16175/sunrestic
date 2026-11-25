{
  description = "Static aarch64 restic + ssh (for restic's sftp backend) for rooted Android";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-linux" ];
    in
    {
      packages = forAllSystems (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          # aarch64 + musl + fully static
          target = pkgs.pkgsCross.aarch64-multiplatform.pkgsStatic;

          # Android has no /etc/resolv.conf, so Go's pure resolver falls back to
          # 127.0.0.1:53 and every lookup fails. Set RESTIC_DNS="host:53" to override.
          restic = target.restic.overrideAttrs (old: {
            doCheck = false;
            postInstall = "";
            env = (old.env or { }) // { CGO_ENABLED = "0"; };
            postPatch = (old.postPatch or "") + ''
              cat > cmd/restic/android_dns.go <<'EOF'
              package main

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
              EOF
            '';
          });

          # Android has no /etc/passwd; stock ssh exits with "No user exists for uid".
          ssh = (target.openssh.override {
            withPAM = false;
            withKerberos = false;
            withFIDO = false;
          }).overrideAttrs (old: {
            doCheck = false;
            postPatch = (old.postPatch or "") + ''
              cat > android_pw.h <<'EOF'
              static struct passwd *android_getpwuid(uid_t uid)
              {
                  static struct passwd fb;
                  struct passwd *p = getpwuid(uid);
                  if (p != NULL)
                      return p;
                  fb.pw_name = getenv("SSH_USER") ? getenv("SSH_USER") : "root";
                  fb.pw_passwd = "*";
                  fb.pw_uid = uid;
                  fb.pw_gid = getgid();
                  fb.pw_gecos = "";
                  fb.pw_dir = getenv("HOME") ? getenv("HOME") : "/data/local/tmp";
                  fb.pw_shell = "/system/bin/sh";
                  return &fb;
              }
              EOF
              sed -i '0,/#include "includes.h"/s//#include "includes.h"\n#include "android_pw.h"/' ssh.c
              sed -i 's/getpwuid(getuid())/android_getpwuid(getuid())/' ssh.c
            '';
          });

          bundle = pkgs.runCommand "restic-android-bundle" { } ''
            mkdir -p $out/bin
            cp ${restic}/bin/restic $out/bin/restic
            cp ${ssh}/bin/ssh $out/bin/ssh
          '';
        in
        {
          inherit restic ssh bundle;
          default = bundle;
        });
    };
}
