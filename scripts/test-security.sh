#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin 2.2.21}"
: "${JAVA_HOME:?Set JAVA_HOME to Java 17+}"
mkdir -p core/build/security artifacts/security-review/interop
"$KOTLIN_HOME/bin/kotlinc" core/src/main/kotlin/com/calcvault/core/*.kt core/src/test/kotlin/com/calcvault/core/SecurityChecks.kt -jvm-target 17 -include-runtime -d core/build/security/checks.jar
"$JAVA_HOME/bin/java" -Xmx128m -cp core/build/security/checks.jar com.calcvault.core.SecurityChecks artifacts/security-review/interop
python3 scripts/security-interop.py artifacts/security-review/interop
"$JAVA_HOME/bin/java" -Xmx128m -cp core/build/security/checks.jar com.calcvault.core.SecurityChecks --verify-python artifacts/security-review/interop
