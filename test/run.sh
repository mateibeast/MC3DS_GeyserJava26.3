#!/bin/bash
# End-to-end test: a fake Java 1.12.2 server + the bridge + a fake 3DS client, all on localhost.
# Needs the sel-utils "pocket113" Java classes (MIT, https://github.com/sel-project/sel-utils)
# compiled into test/sel/ - the fake 3DS client uses them to decode packets.
set -e
cd "$(dirname "$0")"
[ -d sel ] || { echo "Put the compiled sel-utils pocket113 classes in test/sel/ first."; exit 1; }
(cd .. && ./build.sh)
mkdir -p out run
javac -nowarn -d out -cp ../build/classes:sel src/*.java
cd run
[ -f key.pem ] || head -c 16 /dev/urandom > key.pem          # throwaway test key
cat > bridge.properties <<P
bind-host=127.0.0.1
bind-port=19333
motd=Test Bridge
java-host=127.0.0.1
java-port=25599
floodgate-key=key.pem
chunk-radius=4
debug=true
P
J="java -cp ../../build/classes:../out:../sel"
timeout 40 $J FakeJavaServer 25599 key.pem > java.log 2>&1 & JP=$!
sleep 0.7
timeout 38 java -jar ../../build/MC3DSBridge.jar < /dev/null > bridge.log 2>&1 & BP=$!
sleep 1.2
timeout 34 $J FakePEClient 19333 > pe.log 2>&1 || true
sleep 1.5
kill $JP $BP 2>/dev/null || true; wait 2>/dev/null || true
grep -E "Spawned|WARN|ERROR" bridge.log || true
tail -1 pe.log
echo "Logs: test/run/java.log, bridge.log, pe.log"
