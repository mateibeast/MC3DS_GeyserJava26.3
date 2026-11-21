#!/bin/sh
# Builds MC3DSBridge.jar with only a JDK (no Maven/Gradle needed). Run from this folder.
set -e
rm -rf build && mkdir -p build/stubs build/classes build/jar
javac -nowarn -d build/stubs $(find stubs -name '*.java')
javac -nowarn --release 8 -d build/classes -cp build/stubs $(find src -name '*.java')
cp -r build/classes/dev build/jar/
cp res/plugin.yml res/pe_blocks.txt res/pe_items.txt res/recipes_112.txt res/sounds_112.txt README.md build/jar/
(cd build/jar && jar cfm ../../MC3DSBridge.jar ../../manifest.txt .)
echo "Built MC3DSBridge.jar"
