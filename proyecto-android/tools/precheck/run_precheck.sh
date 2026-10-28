#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PRE="$ROOT/tools/precheck"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

python3 "$PRE/static_precheck.py"
javac -d "$OUT/junit" "$PRE/junit/org/junit/Test.java" "$PRE/junit/org/junit/Assert.java"

BCPROV="${BCPROV_JAR:-/usr/share/java/bcprov-1.80.jar}"
BCPKIX="${BCPKIX_JAR:-/usr/share/java/bcpkix-1.80.jar}"
if [[ ! -f "$BCPROV" || ! -f "$BCPKIX" ]]; then
  echo "Para ejecutar el precheck completo, define BCPROV_JAR y BCPKIX_JAR con los jars BouncyCastle."
  exit 2
fi

# Compile the core and MITM in a single kotlinc invocation to keep runtime reasonable.
kotlinc -classpath "$OUT/junit:$BCPROV:$BCPKIX" \
  "$PRE/AndroidPmStubs.kt" "$PRE/AndroidNetStubs.kt" "$PRE/AndroidOsStubs.kt" "$PRE/AndroidContextStubs.kt" \
  "$PRE/AndroidLogStub.kt" "$PRE/CaManagerStub.kt" \
  "$ROOT/app/src/main/java/com/lab/netlens/DebugLog.kt" \
  "$PRE/DebugLogSmoke.kt" \
  "$ROOT/app/src/main/java/com/lab/netlens/Store.kt" \
  "$ROOT/app/src/main/java/com/lab/netlens/Dpi.kt" \
  "$ROOT/app/src/main/java/com/lab/netlens/NetworkTools.kt" \
  "$ROOT/app/src/main/java/com/lab/netlens/Engine.kt" \
  "$ROOT/app/src/main/java/com/lab/netlens/MitmProxy.kt" \
  "$ROOT/app/src/test/java/com/lab/netlens/DpiTlsTest.kt" \
  "$PRE/EngineParserSmoke.kt" "$PRE/NetworkRulesSmoke.kt" "$PRE/EngineRelaySmoke.kt" "$PRE/MitmLifecycleSmoke.kt" "$PRE/TlsTestRunner.kt" \
  -d "$OUT/classes"

kotlin -classpath "$OUT/junit:$OUT/classes" com.lab.netlens.DebugLogSmokeKt
kotlin -classpath "$OUT/junit:$OUT/classes" com.lab.netlens.EngineParserSmokeKt
kotlin -classpath "$OUT/junit:$OUT/classes" com.lab.netlens.NetworkRulesSmokeKt
kotlin -classpath "$OUT/junit:$OUT/classes" com.lab.netlens.EngineRelaySmokeKt
kotlin -classpath "$OUT/junit:$OUT/classes:$BCPROV:$BCPKIX" com.lab.netlens.MitmLifecycleSmokeKt
kotlin -classpath "$OUT/junit:$OUT/classes" com.lab.netlens.TlsTestRunnerKt

python3 - <<PY
import xml.etree.ElementTree as ET
from pathlib import Path
root=Path(r"$ROOT")
for rel in ["app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml", "app/src/main/res/values/themes.xml"]:
    ET.parse(root/rel)
    print("PASS — XML válido:", rel)
PY

printf "\nAVISO: esto NO ejecuta Gradle/Android SDK ni prueba una VPN real.\n"
