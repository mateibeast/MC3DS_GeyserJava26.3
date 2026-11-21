#!/bin/sh
# Builds build/MC3DSBridge.jar with only a JDK (17+). No Maven/Gradle, no downloads.
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/stubs build/classes build/jar
javac -nowarn -d build/stubs $(find stubs -name '*.java')
javac -nowarn --release 8 -d build/classes -cp build/stubs $(find src -name '*.java')
cp -r build/classes/dev build/jar/
cp res/* README.md build/jar/
(cd build/jar && jar cfm ../MC3DSBridge.jar ../../manifest.txt .)
echo "Built build/MC3DSBridge.jar"
