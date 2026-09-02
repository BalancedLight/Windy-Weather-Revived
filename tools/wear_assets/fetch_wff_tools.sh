#!/usr/bin/env bash
set -euo pipefail

target="$(cd "$(dirname "${BASH_SOURCE[0]}")/../wff" 2>/dev/null && pwd || true)"
if [ -z "$target" ]; then
    target="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/wff"
    mkdir -p "$target"
fi

base="https://github.com/google/watchface/releases/download/latest"
for artifact in wff-validator.jar memory-footprint.jar wff-xsd.zip; do
    if [ ! -s "$target/$artifact" ]; then
        echo "Downloading $artifact"
        curl -sSL --retry 3 --max-time 600 -o "$target/$artifact" "$base/$artifact"
    fi
done

if [ ! -d "$target/xsd" ]; then
    mkdir -p "$target/xsd"
    unzip -o -q "$target/wff-xsd.zip" -d "$target/xsd"
fi

echo "WFF tools ready in $target"
