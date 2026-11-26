#!/usr/bin/env bash
set -euo pipefail

desktop=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$desktop")
build="$desktop/build"
version=$(sed -n 's/^version = "\([^"]*\)"$/\1/p' "$desktop/build.gradle.kts")
xray_version=26.11.9
xray_sha256=b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82

[[ $(uname -s) == Linux && $(uname -m) == x86_64 && $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    { echo 'Build the Linux package on x86-64 Linux with JDK 17.' >&2; exit 1; }

"$root/gradlew" -p "$desktop" --console=plain test installDist
bash -n "$desktop/src/main/resources/whitegame/LinuxNetwork.sh"

mkdir -p "$build/native" "$root/dist"
archive="$build/native/Xray-linux-64.zip"
if ! printf '%s  %s\n' "$xray_sha256" "$archive" | sha256sum --status --check; then
    curl --fail --location --retry 2 --silent --show-error --output "$archive" \
        "https://github.com/XTLS/Xray-core/releases/download/v$xray_version/Xray-linux-64.zip"
fi
printf '%s  %s\n' "$xray_sha256" "$archive" | sha256sum --check

staging=$(mktemp -d "$build/package-linux.XXXXXXXX")
jpackage --type app-image --name WhiteGame --app-version "$version" \
    --input "$build/install/whitegame-desktop/lib" --main-jar "whitegame-desktop-$version.jar" \
    --main-class com.whitegame.desktop.MainKt --dest "$staging" --vendor WhiteGame

image="$staging/WhiteGame"
engine="$image/engines/xray"
mkdir -p "$engine"
unzip -q "$archive" xray geoip.dat geosite.dat LICENSE -d "$engine"
chmod 755 "$engine/xray"
"$engine/xray" version | sed -n '1p'
cp "$desktop/README.md" "$image/README.md"

name="WhiteGame-Linux-x64-$version.tar.gz"
tar -C "$staging" -czf "$staging/$name" WhiteGame
mv -f "$staging/$name" "$root/dist/$name"
printf 'Distribution: %s\n' "$root/dist/$name"
