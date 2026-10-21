#!/usr/bin/env bash
# Builds dist/ (scale.jar + lib/ + scale.properties), which can be copied to the target as-is.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes dist
mkdir -p build/classes dist
javac --release 17 -Xlint:all -cp "lib/*" -d build/classes src/*.java
jar --create --file dist/scale.jar --manifest manifest.txt -C build/classes .
cp -r lib dist/lib
cp scale.properties dist/
echo "Built dist/scale.jar"
