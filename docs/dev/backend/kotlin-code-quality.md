# Kotlin code quality

This guide explains the three code-quality tools used by the Kotlin backend.

Run every command in this guide from [`backend/`](../../../backend). The
repository wrapper downloads and runs the required tool versions. Do not
install Gradle, Maven, ktfmt, Ktlint, or Detekt globally.

## The three tools have different jobs

| Tool | Question it answers | What it checks |
| --- | --- | --- |
| ktfmt | "How should this code be laid out?" | Whitespace, indentation, line wrapping, import order, and trailing commas |
| Ktlint | "Does this code follow the remaining Kotlin style rules?" | Reliable rules such as forbidding wildcard imports; conflicting layout rules are disabled |
| Detekt | "Is this code structurally safe and maintainable?" | Potential bugs, complexity, coroutines, exceptions, performance, naming, and code smells |

Ktfmt is the only formatting authority. Do not hand-format code to satisfy a
Ktlint layout preference. Ktlint is configured to accept the canonical output
from ktfmt, while its non-formatting standard rules stay active. Detekt's Ktlint
wrapper is not installed, so formatting is not reported twice.

The Kotlin compiler still has a separate job: it checks syntax and types. A
file can be formatted correctly and still fail compilation or Detekt.

## Daily workflow

After editing Kotlin code, run:

```sh
./kotlin do ktfmt
./kotlin check
```

The first command writes canonical formatting. The second command runs all
tests and the three mandatory quality checks. A second formatting run must not
change any file.

For quicker feedback, run one check at a time:

```sh
./kotlin check ktfmt
./kotlin check ktlint
./kotlin check detekt
```

Detekt is also available as a custom command:

```sh
./kotlin do detekt
```

Both Detekt commands run the same analysis. The custom command is useful when
you discover the available operations with `./kotlin show commands`.

`./kotlin check ktfmt` never writes a file. If it finds a difference, its error
lists every affected file and tells you to run `./kotlin do ktfmt`.

Maintainers changing the quality plugins should also run the public-command
contract fixture:

```sh
./scripts/verify-code-quality-tooling.sh
```

The fixture creates a separate temporary Kotlin project. It checks command and
check registration, a clean full gate, ktfmt repair and idempotence, a Ktlint
violation in test code, a Detekt violation in plugin code, and rejection of an
unknown Detekt configuration key. Its deliberately bad sources never enter the
real backend source tree.

## Which source files are checked

All three tools inspect:

- production sources under `backend/src`;
- test sources under `backend/test`; and
- quality-plugin production and test sources under `backend/plugins`.

The quality plugins are covered explicitly by the checks enabled on the
backend module. This avoids a plugin bootstrap cycle while still checking the
plugins' own Kotlin code.

Both `.kt` and Kotlin script `.kts` files are included.

## Configuration and pinned versions

The configuration is deliberately split by responsibility:

- [`plugins/ktfmt/module.yaml`](../../../backend/plugins/ktfmt/module.yaml)
  pins ktfmt `0.64`. Its canonical Kotlinlang-style options live in
  [`KtfmtPlugin.kt`](../../../backend/plugins/ktfmt/src/shop/voenix/ktfmt/KtfmtPlugin.kt)
  and cannot vary per backend module.
- [`.editorconfig`](../../../backend/.editorconfig) contains shared editor
  settings and the Ktlint compatibility rules. Ktlint `1.8.0` remains pinned in
  [`plugins/ktlint/module.yaml`](../../../backend/plugins/ktlint/module.yaml).
- [`config/detekt/detekt.yml`](../../../backend/config/detekt/detekt.yml)
  contains only documented changes to Detekt's recommended defaults. Detekt
  `2.0.0-alpha.5` is pinned in
  [`plugins/detekt/plugin.yaml`](../../../backend/plugins/detekt/plugin.yaml).

Ktfmt uses a 100-character line width and four spaces for block and
continuation indentation. It sorts imports and manages trailing commas. It
does **not** remove unused imports, because that heuristic can remove a valid
import from compilable Kotlin code. It also does not preserve an author's
optional lambda line breaks, which makes formatting canonical for both people
and AI agents.

Ktfmt `0.64` can occasionally need more than one internal pass for wrapped
comments. The plugin computes the stable result in memory before writing, so
one public formatting command still reaches the fixed point. The Kotlin
Toolchain runs plugins on a compact JRE; the pinned repackaged `javac`
dependency in the ktfmt module supplies the compiler classes that ktfmt's
formatting library expects.

## How Detekt runs

Detekt runs in a separate Java process. Its tool classpath, including its Kotlin
2.4 compiler dependencies, is kept separate from the backend's analyzed
project classpath. The plugin itself does not import Detekt production classes.
This isolation keeps a future Detekt upgrade local to a small command-line
adapter.

Production code runs in Detekt's `full` analysis mode with:

- Kotlin language and API version 2.4;
- JVM target 17; and
- the backend main compile classpath for type resolution.

Test and plugin code currently runs in `light` mode. Kotlin Toolchain `0.11.1`
does not expose the test compile classpath to plugins, so type-dependent rules
cannot reliably analyze tests yet. The public commands will not change when a
future Toolchain version makes that classpath available; only the plugin's
internal analysis mode needs to change.

Detekt does not enable every experimental rule. It starts from the recommended
defaults, fails on findings of warning severity or higher, and applies only the
small, commented project differences in `detekt.yml`. There is no baseline.

## Detekt reports

Every successful or failed Detekt analysis writes machine-readable SARIF and
human-readable Markdown reports to:

```text
backend/build/tasks/_backend_analyze@detekt/
|- main.sarif
|- main.md
|- additional.sarif
`- additional.md
```

`main` is the full production analysis. `additional` is the light analysis of
tests and plugins. Console findings use absolute paths for quick local
diagnosis; report paths are relative to `backend/` where possible.

## Understanding failures

The wrapper returns a non-zero exit code when any mandatory check fails.

- A ktfmt parse error names the file and guarantees that the formatting command
  changed no file. Fix the Kotlin syntax, then run the formatter again.
- A Ktlint error includes the file, line, column, message, and rule ID in
  parentheses.
- A Detekt code finding is reported separately from an invalid Detekt
  configuration and from an unexpected tool failure.
- An unknown or renamed Detekt configuration key fails the check. This is
  intentional: a tool upgrade must not silently stop enforcing a rule.

Detekt may print a JDK warning about `sun.misc.Unsafe` before its normal output.
That warning comes from Detekt's pinned Kotlin compiler runtime; the check's
exit code and the following Detekt message remain authoritative.

## Suppression policy

Treat a finding as a request to understand the code, not as an obstacle to
hide. Use this order:

1. Fix the code when the finding describes a real problem.
2. If a rule is systematically unsuitable for this repository, change the
   narrowest possible setting in `detekt.yml` or `.editorconfig` and add a
   comment explaining why.
3. Use a local `@Suppress("RuleId")` only for one intentional exception that
   cannot be expressed more clearly in code. Add a nearby comment explaining
   the decision.

Do not create a Detekt baseline for new findings. Do not disable all rules, all
warnings, or an entire rule set to make a check green. Unused-import analysis
is the one deliberate initial exception and must have a separate false-positive
test before it is enabled.

## Upgrading a tool

Change one pinned tool version at a time. Then run its plugin tests, format the
repository, run Ktlint on that exact output, and finish with `./kotlin check`.

For a ktfmt upgrade, keep the unused-import and lambda-break options explicit
and re-run the fixed-point, parse-safety, `.kts`, and Kotlin-2.4 syntax tests.
Also verify whether the compact-JRE `javac` compatibility dependency is still
needed.

For a Detekt upgrade, check the CLI arguments and documented exit codes, make
sure invalid configuration still fails distinctly, and verify all four report
files. Detekt 2.0 is still an alpha series, so CLI or rule names may change
before the stable release.
