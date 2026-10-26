#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
javac -encoding UTF-8 -d "$DIR" app/src/main/java/at/andi/magnetrute/SensorMath.java tests/SensorMathTest.java
java -cp "$DIR" SensorMathTest
