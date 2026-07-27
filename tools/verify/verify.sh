#!/usr/bin/env bash
#
# Runs inside the container built from tools/verify/Dockerfile.
#
#   tier1  logic tests for the post-processing client, on a plain JVM
#   tier2  AAPT2 resource validation + full javac type-check of the app sources
#   all    both (default)
#
# Not covered here: the Rust core, the JNI boundary, and APK assembly. Those
# need the NDK and a device, so they belong on a developer machine.
set -uo pipefail

MODE="${1:-all}"
OUT=/tmp/verify-out
FAILED=0

banner() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
fail()   { printf '\033[31m✗ %s\033[0m\n' "$1"; FAILED=1; }
pass()   { printf '\033[32m✓ %s\033[0m\n' "$1"; }

# ---------------------------------------------------------------- tier 1

tier1() {
  banner "Tier 1 — post-processing logic tests (plain JVM, no Android)"

  if [ -n "${OPENROUTER_API_KEY:-}" ]; then
    echo "OpenRouter live tests: ENABLED (model: ${OPENROUTER_MODEL:-openai/gpt-4o-mini})"
  else
    echo "OpenRouter live tests: skipped (set OPENROUTER_API_KEY to enable)"
  fi
  rm -rf "$OUT/classes"
  mkdir -p "$OUT/classes"

  # PostProcessClient is deliberately free of Android imports, so it compiles
  # against nothing but org.json. If this javac ever needs android.jar, that
  # means Android crept into the class and the tests are about to become
  # untestable off-device — treat it as a design regression, not a build fix.
  if ! javac -nowarn \
      -cp "$TESTLIBS/json.jar:$TESTLIBS/junit-console.jar" \
      -d "$OUT/classes" \
      app/src/main/java/dev/notune/transcribe/PostProcessClient.java \
      tools/verify/tests/*.java 2>&1; then
    fail "test compilation"
    return
  fi
  pass "test compilation"

  if java -jar "$TESTLIBS/junit-console.jar" execute \
      --class-path "$OUT/classes:$TESTLIBS/json.jar" \
      --select-package dev.notune.transcribe \
      --details=tree \
      --disable-banner; then
    pass "logic tests"
  else
    fail "logic tests"
  fi
}

# ---------------------------------------------------------------- tier 2

tier2() {
  banner "Tier 2 — resource validation + Java type-check (Android SDK, no NDK)"

  # The repo's gradle.properties pins org.gradle.java.home to an Android Studio
  # JBR path, which is right for a developer machine and absent here. Gradle
  # reads GRADLE_USER_HOME/gradle.properties at a higher precedence than the
  # project's, so the container overrides it without editing the working tree.
  mkdir -p "$GRADLE_USER_HOME"
  cat > "$GRADLE_USER_HOME/gradle.properties" <<PROPS
org.gradle.java.home=${JAVA_HOME}
PROPS

  # cargoNdkBuild builds the Rust .so and downloadModels fetches a 600 MB GGUF.
  # Neither is needed to compile Java or process resources, and neither can run
  # in this image. Everything else — AAPT2, R.java generation, javac against
  # android.jar and the resolved AARs — runs for real.
  if ./gradlew --no-daemon --console=plain \
      :app:compileDebugJavaWithJavac \
      -x :app:cargoNdkBuild \
      -x :app:downloadModels; then
    pass "resources + javac"
  else
    fail "resources + javac"
  fi
}

case "$MODE" in
  tier1) tier1 ;;
  tier2) tier2 ;;
  all)   tier1; tier2 ;;
  *)     echo "usage: verify.sh [tier1|tier2|all]" >&2; exit 2 ;;
esac

banner "Result"
if [ "$FAILED" -eq 0 ]; then
  pass "all checks passed"
else
  fail "one or more checks failed"
fi
exit "$FAILED"
