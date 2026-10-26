#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
javac -encoding UTF-8 -d "$DIR" \
    app/src/main/java/at/andi/magnetrute/SensorMath.java \
    app/src/main/java/at/andi/magnetrute/PlanGeometry.java \
    app/src/main/java/at/andi/magnetrute/PlanArchive.java \
    app/src/main/java/at/andi/magnetrute/GeoMath.java \
    app/src/main/java/at/andi/magnetrute/GpsQuality.java \
    app/src/main/java/at/andi/magnetrute/CalibrationGuard.java \
    app/src/main/java/at/andi/magnetrute/FieldGrid.java \
    app/src/main/java/at/andi/magnetrute/SurveyComparison.java \
    tests/SensorMathTest.java tests/PlanGeometryTest.java tests/PlanArchiveTest.java \
    tests/GeoMathTest.java tests/GpsQualityTest.java tests/CalibrationGuardTest.java \
    tests/FieldGridTest.java tests/SurveyComparisonTest.java
java -cp "$DIR" SensorMathTest
java -cp "$DIR" PlanGeometryTest
java -cp "$DIR" PlanArchiveTest
java -cp "$DIR" GeoMathTest
java -cp "$DIR" GpsQualityTest
java -cp "$DIR" CalibrationGuardTest
java -cp "$DIR" FieldGridTest
java -cp "$DIR" SurveyComparisonTest
