#!/usr/bin/env bash
# Compiles and runs the hardware-free tests. Exit code is non-zero on any failure.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes build/test
mkdir -p build/classes build/test
javac --release 17 -Xlint:all -cp "lib/*" -d build/classes src/*.java
javac --release 17 -cp "build/classes:lib/*" -d build/test test/Tests.java
java -cp "build/test:build/classes:lib/*" Tests
