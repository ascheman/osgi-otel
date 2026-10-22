# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

`osgi-otel` is an **OpenTelemetry Java Agent extension** that emits spans for
**OSGi inter-bundle data flow**. Currently it instruments
`BundleContext.getService(ServiceReference)` and tags each span with the caller
bundle, provider bundle, and service interface.

Driving use-case: observing data flow between bundles in Eclipse RCP / Equinox
applications (Mylyn). The extension itself is plain OSGi — no Eclipse-specific
APIs. Status: very early v1. See `README.adoc` for the user-facing docs and the
roadmap.

## Modules (Maven reactor)

Parent: `net.aschemann.otel:osgi-otel-parent:0.1.0-SNAPSHOT` (root `pom.xml`).

| Module | Purpose |
|---|---|
| `osgi-otel-extension` | The agent extension. Four `InstrumentationModule`s (pkg `net.aschemann.otel.*`): `osgi-bundle-context` (`BundleContext.getService`), `eclipse-ui` (`Command.executeWithChecks` + JFace `IAction.run`/`runWithEvent`), `eclipse-events` (**opt-in**, default-off diagnostic: EMF `eNotify` + JavaBeans `firePropertyChange` → per-event spans for cascade depth/width/timing), and `hibernate-stack` (**opt-in**, default-off diagnostic: `org.hibernate.Transaction.begin`/`commit` → span with `code.stacktrace` to reveal what triggered an orphan transaction across an async hop). |
| `osgi-otel-file-exporter` | `SpanExporter` writing OTLP-shaped JSON Lines to a file (`-Dotel.traces.exporter=file`). |
| `osgi-otel-test-bundle` | Minimal OSGi bundle (`packaging=bundle`, Felix bundle plugin) providing `HelloService`; used by the IT. |
| `osgi-otel-it` | Failsafe IT: starts embedded Equinox 3.15.300 with `agent-for-testing`, installs the test bundle, calls `getService`, asserts the span. |

## Build, test, run

```bash
./mvnw -B verify                 # full build + integration test (the CI command)
./mvnw -B install -DskipTests    # build, then assemble dist/ (see README "Quick start")
samples/run-with-otel.template.sh   # generic launcher; set OSGI_OTEL_APP_LAUNCHER
samples/run-mylyn-with-otel.sh      # worked Eclipse/Mylyn example
```

**Always build via `./mvnw`** (Maven wrapper pinned to **3.9.16**). Maven 4 binds
incompatible beta core plugins (e.g. `maven-install-plugin:4.0.0-beta-3` fails the
install phase), so `maven-enforcer-plugin` requires Maven `[3.9,4.0)` and a bare
`mvn` on Maven 4 fails fast. A general alpha/beta dependency ban is *not* enforced
(bannedDependencies treats wildcard versions as match-all, so a `-alpha` suffix ban
would ban everything). The only accepted pre-release deps are OpenTelemetry's
javaagent extension-API/tooling/testing artifacts (`io.opentelemetry*:*-alpha`),
which OTel publishes only with an `-alpha` suffix and which are `provided`/`test`
only (the shipped agent is GA) — documented at the `otel.*.alpha` property.

`samples/` holds the launcher scripts (generic template + Mylyn example) and
`samples/README.adoc`. The extension itself is configured only via `-D` system
properties (`otel.*`, `osgi.filter.*`); the `OSGI_OTEL_*` env vars are a
launcher-script convenience and all optional.

- CI (`.github/workflows/build.yml`) runs `./mvnw -B verify` on **JDK 21 (Temurin)**.
- Deploy: the `github` profile auto-activates when `GITHUB_TOKEN` is set (also
  needs `GITHUB_LOGIN`), publishing all four modules to GitHub Packages at
  `maven.pkg.github.com/ascheman/osgi-otel`.

## Architecture & conventions — read before editing

- **Java 8 bytecode.** `maven.compiler.source/target = 1.8`. The extension advice
  is injected into arbitrary host applications, so it must stay Java-8-compatible
  even though CI compiles on JDK 21. Do not use newer language/API features in
  `osgi-otel-extension`.
- **`provided` scope everywhere in the extension.** The OTel agent and OSGi APIs
  are supplied at runtime by the agent/host. Adding `compile`/runtime deps to the
  extension is almost always wrong.
- **SPI via AutoService.** `@AutoService` generates the `META-INF/services` files
  for `InstrumentationModule` and `ConfigurableSpanExporterProvider`. The
  `auto-service` annotation-processor path is wired in each module's
  `maven-compiler-plugin` config — keep it when touching those poms.
- **The IT loads jars as files, not on the classpath.** `maven-dependency-plugin`
  copies the agent-for-testing, extension, and test-bundle jars into
  `target/agents/`; failsafe attaches them via `-javaagent` /
  `-Dotel.javaagent.extensions` / `test.bundle.path`. Changing artifactIds or the
  copy step breaks the IT.
- **Span attributes** — OSGi: `osgi.caller.bundle`, `osgi.provider.bundle`,
  `osgi.service.class`. Eclipse UI: `eclipse.command.id`, `eclipse.command.name`,
  `eclipse.action.id`, `eclipse.action.text`, `eclipse.action.class`. Business
  context on UI spans (best-effort, only set when resolvable):
  `eclipse.trigger.widget.class` (SWT widget that fired the command/action),
  `eclipse.trigger.widget.text` (**opt-in**, see below), `eclipse.selection.class`
  and `eclipse.selection.size` (workbench selection, command spans only), plus
  `eclipse.selection.label` (**opt-in**, first element's `toString`).
  `traces.jsonl` and the README `jq` recipes depend on these exact keys — rename
  in lockstep.
- **Widget/selection context is read reflectively** in `CommandExecuteAdvice` /
  `ActionRunAdvice` — SWT and JFace-viewer selection APIs are deliberately
  off-classpath, so the advice reflects on `Event.widget`, `widget.getText()` and
  the eval-context `selection` variable, tolerating every failure (empty attr).
  `ActionRunAdvice` binds the SWT event via `@Advice.Argument(0, optional=true)`
  (null for `run()`, the `Event` for `runWithEvent`). Widget *text* is PII, so it
  is off by default: enable with `-Dosgi.capture.widget.text=true`, cap length
  with `-Dosgi.capture.widget.text.max=N` (default 64). The selection element's
  label (`eclipse.selection.label`, the first element's `toString()`) is likewise
  domain data and off by default: `-Dosgi.capture.selection.label=true`, capped by
  `-Dosgi.capture.selection.label.max=N` (default 64). No helper classes — the
  logic stays inline in the advice because this extension has no muzzle/helper
  injection.
- **`hibernate-stack` needs no Hibernate dependency.** It matches
  `org.hibernate.Transaction` only as a string (`implementsInterface(named(...))`)
  and `TransactionStackAdvice` touches no Hibernate type — it just reads
  `Thread.currentThread().getStackTrace()` and emits a `hibernate.tx.<begin|commit>`
  span with `code.function` + `code.stacktrace`. `frames[1]` is the instrumented
  method (advice is inlined), so the render starts at `frames[1]`. Depth cap:
  `-Dosgi.capture.stack.max=N` (default 32). Same inline-only rule (no muzzle).
- **Eclipse UI instrumentation compiles against `provided` Eclipse deps**
  (`org.eclipse.platform:org.eclipse.core.commands` + `…:org.eclipse.jface`, SWT
  excluded — it is a platform-specific transitive the advice never touches).
  Pinned to Java-11-bytecode versions so the module builds on any JDK ≥ 11; the
  host supplies its own versions at runtime. The `eclipse-ui` module self-disables
  when the target classes are absent, and can be turned off with
  `-Dotel.instrumentation.eclipse-ui.enabled=false`.
- **Filter config is read once at class-load time** (`BundleSpanFilter`, system
  properties `osgi.filter.cross-bundle-only` / `.bundle.include` / `.bundle.exclude`).
  A JVM restart is required to change it; there is no live reconfiguration.
- **Two distinct env-var namespaces.** Launcher-script variables are prefixed
  `OSGI_OTEL_` deliberately, to avoid collisions with the OTel SDK's own `OTEL_*`
  / `otel.*` properties. Keep them separate.
- **License header** (Apache 2.0, "Copyright 2026 Gerd Aschemann") goes on every
  Java file — match the existing 4-line header.
- `README.adoc` is AsciiDoc: one sentence per line, `*`/`**` lists, `[source,…]`
  blocks — not Markdown.

## Git

- **No commits exist yet** — the whole tree is still untracked. First commit will
  establish history.
- This is a **personal** project (`net.aschemann.otel`). Commit author is
  **Gerd Aschemann <gerd@aschemann.net>** (matches `<developers>` in the parent
  pom) — *not* the Apache identity used for jQAssistant/Maven work.
- **Never commit or push without an explicit "Go".** Stage, show the diff, wait
  for approval.
