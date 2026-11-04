#!/usr/bin/env bash
# Compile and run the pure-Kotlin :core module WITHOUT Gradle downloads, using the Kotlin compiler that ships
# inside a Gradle distribution (GRADLE_HOME, default /opt/gradle). Handy on machines where Maven Central is blocked.
#
#   tools/kotlinc-local.sh compile            -> build/local/classes
#   tools/kotlinc-local.sh test               -> compile main + tests, run the golden-vector suite (no JUnit needed)
#   tools/kotlinc-local.sh run <MainClassKt>  -> run any main() in main or test sources
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
G="${GRADLE_HOME:-/opt/gradle}/lib"
OUT="$HERE/build/local"
CP_COMPILER="$(ls "$G"/kotlin-compiler-embeddable-*.jar "$G"/kotlin-stdlib-*.jar "$G"/kotlin-reflect-*.jar \
  "$G"/kotlin-script-runtime-*.jar "$G"/kotlin-daemon-embeddable-*.jar "$G"/trove4j-*.jar \
  "$G"/kotlinx-coroutines-core-jvm-*.jar "$G"/annotations-*.jar | tr '\n' ':')"
STDLIB="$(ls "$G"/kotlin-stdlib-*.jar | head -1)"
JSON="$(ls "$G"/kotlinx-serialization-core-jvm-*.jar "$G"/kotlinx-serialization-json-jvm-*.jar | tr '\n' ':')"
LIBCP="$STDLIB:$JSON"

kc() { java -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn "$@"; }

cmd="${1:-test}"; shift || true
mkdir -p "$OUT"
case "$cmd" in
  compile)
    kc -cp "$LIBCP" -d "$OUT/main" $(find "$HERE/core/src/main" -name '*.kt')
    ;;
  test)
    kc -cp "$LIBCP" -d "$OUT/main" $(find "$HERE/core/src/main" -name '*.kt')
    kc -cp "$LIBCP:$OUT/main" -d "$OUT/test" $(find "$HERE/core/src/testsupport" "$HERE/core/src/localtest" -name '*.kt')
    java -cp "$LIBCP:$OUT/main:$OUT/test:$HERE/core/src/main/resources" -Dpb.golden="$HERE/core/src/test/resources/golden" \
      -Dpb.packs="$HERE/core/src/main/resources/packs" "${TEST_MAIN:-promptbeast.LocalRunnerKt}" "$@"
    ;;
  run)
    kc -cp "$LIBCP" -d "$OUT/main" $(find "$HERE/core/src/main" -name '*.kt')
    kc -cp "$LIBCP:$OUT/main" -d "$OUT/test" $(find "$HERE/core/src/testsupport" "$HERE/core/src/localtest" -name '*.kt')
    java -cp "$LIBCP:$OUT/main:$OUT/test:$HERE/core/src/main/resources" "$@"
    ;;
  *) echo "usage: $0 compile|test|run <Main>" >&2; exit 2;;
esac
