#!/bin/sh

set -eu

backend_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/voenix-code-quality.XXXXXX")

cleanup() {
  rm -rf "$fixture_dir"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'Code-quality fixture failed: %s\n' "$1" >&2
  exit 1
}

run_kotlin() {
  (cd "$fixture_dir" && ./kotlin "$@")
}

expect_failure_containing() {
  label=$1
  expected_text=$2
  shift 2

  set +e
  output=$(run_kotlin "$@" 2>&1)
  status=$?
  set -e

  if [ "$status" -eq 0 ]; then
    fail "$label unexpectedly succeeded"
  fi
  case "$output" in
    *"$expected_text"*) ;;
    *)
      printf '%s\n' "$output" >&2
      fail "$label did not print '$expected_text'"
      ;;
  esac
}

copy_plugin() {
  plugin_name=$1
  target="$fixture_dir/plugins/$plugin_name"
  mkdir -p "$target"
  cp "$backend_dir/plugins/$plugin_name/module.yaml" "$target/module.yaml"
  cp "$backend_dir/plugins/$plugin_name/plugin.yaml" "$target/plugin.yaml"
  cp -R "$backend_dir/plugins/$plugin_name/src" "$target/src"
}

cp "$backend_dir/kotlin" "$fixture_dir/kotlin"
chmod +x "$fixture_dir/kotlin"
cp "$backend_dir/.editorconfig" "$fixture_dir/.editorconfig"
cp "$backend_dir/libs.versions.toml" "$fixture_dir/libs.versions.toml"
mkdir -p "$fixture_dir/config/detekt" "$fixture_dir/src/fixture" "$fixture_dir/test/fixture"
cp "$backend_dir/config/detekt/detekt.yml" "$fixture_dir/config/detekt/detekt.yml"
copy_plugin detekt
copy_plugin ktfmt
copy_plugin ktlint

cat >"$fixture_dir/project.yaml" <<'EOF'
modules:
  - plugins/detekt
  - plugins/ktfmt
  - plugins/ktlint

plugins:
  - ./plugins/detekt
  - ./plugins/ktfmt
  - ./plugins/ktlint
EOF

cat >"$fixture_dir/module.yaml" <<'EOF'
product: jvm/lib

plugins:
  detekt:
    enabled: true
    includePluginSources: true
  ktfmt:
    enabled: true
    includePluginSources: true
  ktlint:
    enabled: true
    includePluginSources: true

settings:
  kotlin:
    version: 2.4.0
  jvm:
    release: 25
    jdk:
      version: 25
EOF

cat >"$fixture_dir/src/fixture/Example.kt" <<'EOF'
package fixture

class Example
EOF

cat >"$fixture_dir/test/fixture/ExampleTest.kt" <<'EOF'
package fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleTest {
    @Test
    fun exampleHasTheExpectedName() {
        assertEquals("Example", Example::class.simpleName)
    }
}
EOF

checks=$(run_kotlin show checks)
case "$checks" in
  *detekt*ktfmt*ktlint*) ;;
  *) fail "registered checks are incomplete" ;;
esac

commands=$(run_kotlin show commands)
printf '%s\n' "$commands" | grep -Eq '^│[[:space:]]+detekt[[:space:]]+│' ||
  fail "detekt command is not registered"
printf '%s\n' "$commands" | grep -Eq '^│[[:space:]]+ktfmt[[:space:]]+│' ||
  fail "ktfmt command is not registered"

run_kotlin check >/dev/null
run_kotlin do detekt >/dev/null

cat >"$fixture_dir/src/fixture/Example.kt" <<'EOF'
package fixture
class Example{fun answer()=42}
EOF
expect_failure_containing "ktfmt violation" "./kotlin do ktfmt" check ktfmt
run_kotlin do ktfmt >/dev/null
first_format=$(cksum "$fixture_dir/src/fixture/Example.kt")
second_format_output=$(run_kotlin do ktfmt)
second_format=$(cksum "$fixture_dir/src/fixture/Example.kt")
[ "$first_format" = "$second_format" ] || fail "second ktfmt run changed the source"
case "$second_format_output" in
  *"already use canonical ktfmt formatting"*) ;;
  *) fail "second ktfmt run did not report an unchanged source" ;;
esac
cat >"$fixture_dir/src/fixture/Example.kt" <<'EOF'
package fixture

class Example
EOF

cat >"$fixture_dir/test/fixture/ExampleTest.kt" <<'EOF'
package fixture

import kotlin.collections.*
import kotlin.test.Test

class ExampleTest {
    @Test
    fun wildcardImportIsRejected() = Unit
}
EOF
expect_failure_containing "Ktlint test-source violation" "standard:no-wildcard-imports" check ktlint
cat >"$fixture_dir/test/fixture/ExampleTest.kt" <<'EOF'
package fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleTest {
    @Test
    fun exampleHasTheExpectedName() {
        assertEquals("Example", Example::class.simpleName)
    }
}
EOF

cat >"$fixture_dir/plugins/detekt/src/shop/voenix/detekt/IntentionalPluginViolation.kt" <<'EOF'
package shop.voenix.detekt

// TODO: Intentional fixture proving plugin-source coverage.
class IntentionalPluginViolation
EOF
expect_failure_containing "Detekt plugin-source violation" "ForbiddenComment" check detekt
rm "$fixture_dir/plugins/detekt/src/shop/voenix/detekt/IntentionalPluginViolation.kt"

cp "$fixture_dir/config/detekt/detekt.yml" "$fixture_dir/config/detekt/detekt.valid.yml"
cat >>"$fixture_dir/config/detekt/detekt.yml" <<'EOF'

rule-set-that-does-not-exist:
  active: true
EOF
expect_failure_containing "invalid Detekt configuration" "invalid configuration file" check detekt
mv "$fixture_dir/config/detekt/detekt.valid.yml" "$fixture_dir/config/detekt/detekt.yml"

run_kotlin check >/dev/null
printf 'Code-quality Toolchain fixture passed.\n'
