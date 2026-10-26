#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
javac -encoding UTF-8 -d "$DIR" \
    app/src/main/java/at/andi/magnetrute/SensorMath.java \
    app/src/main/java/at/andi/magnetrute/PlanGeometry.java \
    app/src/main/java/at/andi/magnetrute/PlanArchive.java \
    tests/SensorMathTest.java tests/PlanGeometryTest.java tests/PlanArchiveTest.java
java -cp "$DIR" SensorMathTest
java -cp "$DIR" PlanGeometryTest
java -cp "$DIR" PlanArchiveTest
