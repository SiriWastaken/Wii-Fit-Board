#!/usr/bin/env bash
# Compiles and runs the hardware-free tests. Exit code is non-zero on any failure.
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
mkdir -p build/test
javac -cp "out:lib/*" -d build/test test/Tests.java
java -cp "build/test:out:lib/*" Tests
