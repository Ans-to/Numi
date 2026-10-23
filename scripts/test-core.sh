#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KOTLIN_HOME:?Set KOTLIN_HOME to an installed Kotlin 2.2.21 compiler}"
: "${JAVA_HOME:?Set JAVA_HOME to a Java 17+ runtime}"
mkdir -p core/build/standalone
"$KOTLIN_HOME/bin/kotlinc" core/src/main/kotlin/com/calcvault/core/*.kt core/src/test/kotlin/com/calcvault/core/CoreChecks.kt -jvm-target 17 -include-runtime -d core/build/standalone/checks.jar
"$JAVA_HOME/bin/java" -Xmx128m -cp core/build/standalone/checks.jar com.calcvault.core.CoreChecks | tee core/build/standalone/results.txt
