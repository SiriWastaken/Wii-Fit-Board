#!/usr/bin/env bash
# Compiles to out/ (same as the original commands).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -Xlint:all -cp "lib/*" -d out src/*.java
