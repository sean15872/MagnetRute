#!/usr/bin/env bash
# MagnetRute Bootstrap fuer Linux/macOS ohne offizielle Wrapper-JAR.
# Laedt ausschliesslich die offizielle Gradle-8.9-Distribution mit SHA256-Pruefung.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "$0")" && pwd)"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/magnetrute-bootstrap"
BIN="$CACHE/gradle-8.9/bin/gradle"
if [[ ! -x "$BIN" ]]; then
    if ! command -v curl >/dev/null || ! command -v unzip >/dev/null || ! command -v sha256sum >/dev/null; then
        echo 'Bitte curl, unzip und sha256sum installieren oder Gradle 8.9 in Android Studio einstellen.' >&2
        exit 1
    fi
    mkdir -p "$CACHE"
    TMP="$(mktemp -d "$CACHE/install.XXXXXXXX")"
    trap 'rm -rf "$TMP"' EXIT
    URL='https://services.gradle.org/distributions/gradle-8.9-bin.zip'
    echo 'Lade offizielles Gradle 8.9 (erster Start benötigt Internet) ...'
    curl --fail --location --retry 3 "$URL" --output "$TMP/gradle.zip"
    curl --fail --location --retry 3 "$URL.sha256" --output "$TMP/gradle.sha256"
    EXPECTED="$(tr -d '[:space:]' < "$TMP/gradle.sha256")"
    printf '%s  %s\n' "$EXPECTED" "$TMP/gradle.zip" | sha256sum --check --status
    unzip -q "$TMP/gradle.zip" -d "$TMP"
    mv "$TMP/gradle-8.9" "$CACHE/gradle-8.9"
    echo 'SHA256 geprüft: Gradle 8.9 bereit.'
fi
cd "$HERE"
exec "$BIN" "$@"
