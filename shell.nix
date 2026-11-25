{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:
let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "36" ];
    buildToolsVersions = [ "36.0.0" ];
    includeEmulator = false;
    includeNDK = false;
    includeSystemImages = false;
  };
  sdk = "${android.androidsdk}/libexec/android-sdk";
in
pkgs.mkShell {
  packages = [
    pkgs.jdk17
    pkgs.gradle_8
    pkgs.go # builds restic and the ssh helper (scripts/build-native.sh)
    pkgs.git
    pkgs.gh # scripts/release.sh publishes the GitHub release
    pkgs.android-tools
    pkgs.fish
    android.androidsdk
  ];

  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  JAVA_HOME = "${pkgs.jdk17.home}";

  # Point to the actual installed 36.0.0 build-tools aapt2 binary
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2";

  shellHook = ''
    exec fish
  '';
}
