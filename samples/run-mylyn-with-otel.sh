#!/usr/bin/env bash
#
# Launches an Eclipse instance (with Mylyn installed) and attaches the
# OpenTelemetry Java Agent together with the osgi-otel-extension, so that
# OSGi BundleContext.getService calls are exported as spans.
#
# Usage:
#   ./run-mylyn-with-otel.sh                                        # default: file exporter
#   OSGI_OTEL_EXPORTER=otlp ./run-mylyn-with-otel.sh                # send to Jaeger
#   OSGI_OTEL_EXPORTER=file OSGI_OTEL_TRACES_FILE=/tmp/foo.jsonl ./run-mylyn-with-otel.sh
#
# Exporter modes (OSGI_OTEL_EXPORTER):
#   file          (default) write OTLP-shaped JSON Lines to OSGI_OTEL_TRACES_FILE
#   otlp          send via OTLP HTTP to Jaeger at OSGI_OTEL_JAEGER_HOST:OSGI_OTEL_JAEGER_PORT
#   logging-otlp  built-in OTel exporter that prints OTLP-JSON to stdout
#                 (mixed with Eclipse logs - usually you want "file" instead)
#
# All script-specific environment variables are prefixed OSGI_OTEL_ to avoid
# collisions with OpenTelemetry's own OTEL_* environment variables.
#
set -euo pipefail

# --- Configuration (override via env vars) ---------------------------------

OSGI_OTEL_ECLIPSE_BIN="${OSGI_OTEL_ECLIPSE_BIN:-/Applications/Eclipse.app/Contents/MacOS/eclipse}"
# Project root (holds dist/); this script lives in samples/, so default one level up.
OSGI_OTEL_HOME="${OSGI_OTEL_HOME:-$(cd "$(dirname "$0")/.." && pwd)}"
OSGI_OTEL_WORKSPACE="${OSGI_OTEL_WORKSPACE:-${HOME}/.mylyn-otel/workspace}"
OSGI_OTEL_CONFIGURATION="${OSGI_OTEL_CONFIGURATION:-${HOME}/.mylyn-otel/configuration}"
OSGI_OTEL_SERVICE_NAME="${OSGI_OTEL_SERVICE_NAME:-mylyn}"
OSGI_OTEL_EXTRA_VMARGS="${OSGI_OTEL_EXTRA_VMARGS:-}"

OSGI_OTEL_EXPORTER="${OSGI_OTEL_EXPORTER:-file}"
OSGI_OTEL_TRACES_FILE="${OSGI_OTEL_TRACES_FILE:-${OSGI_OTEL_HOME}/traces.jsonl}"
OSGI_OTEL_TRACES_APPEND="${OSGI_OTEL_TRACES_APPEND:-true}"
OSGI_OTEL_JAEGER_HOST="${OSGI_OTEL_JAEGER_HOST:-localhost}"
OSGI_OTEL_JAEGER_PORT="${OSGI_OTEL_JAEGER_PORT:-4318}"

# Bundle filtering (all optional). Regexes are Java Pattern.matches()-style.
OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY="${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY:-}"
OSGI_OTEL_FILTER_BUNDLE_INCLUDE="${OSGI_OTEL_FILTER_BUNDLE_INCLUDE:-}"
OSGI_OTEL_FILTER_BUNDLE_EXCLUDE="${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE:-}"

# Diagnostics (opt-in): trace the Eclipse event cascade (EMF eNotify + JavaBeans
# PropertyChange). Firehose - only enable while investigating.
OSGI_OTEL_TRACE_EVENTS="${OSGI_OTEL_TRACE_EVENTS:-}"

# Diagnostics (opt-in): capture the call stack at Hibernate Transaction
# begin/commit (code.stacktrace), to reveal what triggered an orphan transaction.
OSGI_OTEL_TRACE_TX_STACK="${OSGI_OTEL_TRACE_TX_STACK:-}"

# Application layer spans (opt-in): span every method of the classes matched by
# the rules, to make layer boundaries visible when calls bypass the OSGi
# service registry. Format: prefix:matcher,... - a matcher without any of the
# regex metacharacters *+?()[]{}|^\ is a base class FQCN (that class and all
# its subtypes), anything else is a regex searched in the class name. The
# module stays off while this is empty.
OSGI_OTEL_APP_SPAN_RULES="${OSGI_OTEL_APP_SPAN_RULES:-}"
# Method-name regex (full match) excluded from instrumentation; the extension
# defaults to ^(toString|hashCode|equals|getClass)$ when unset.
OSGI_OTEL_APP_SPAN_EXCLUDE_METHODS="${OSGI_OTEL_APP_SPAN_EXCLUDE_METHODS:-}"
# false drops the osgi.caller.bundle attribute and the per-call stack walk that
# resolves it - the escape hatch when a broad rule costs too much.
OSGI_OTEL_APP_SPAN_CALLER_BUNDLE="${OSGI_OTEL_APP_SPAN_CALLER_BUNDLE:-}"

# OpenTelemetry Java agent version (only referenced in the build hint below;
# keep in sync with otel.javaagent.version in the parent pom). Prefixed
# OSGI_OTEL_ per the namespace convention above (never bare OTEL_*).
OSGI_OTEL_AGENT_VERSION="2.32.0"

AGENT_JAR="${OSGI_OTEL_HOME}/dist/opentelemetry-javaagent.jar"
INSTRUMENTATION_JAR="${OSGI_OTEL_HOME}/dist/osgi-otel-extension-0.1.0-SNAPSHOT.jar"
FILE_EXPORTER_JAR="${OSGI_OTEL_HOME}/dist/osgi-otel-file-exporter-0.1.0-SNAPSHOT.jar"
OTLP_ENDPOINT="http://${OSGI_OTEL_JAEGER_HOST}:${OSGI_OTEL_JAEGER_PORT}"

# --- Sanity checks ---------------------------------------------------------

die() { echo "ERROR: $*" >&2; exit 1; }

[[ -x "${OSGI_OTEL_ECLIPSE_BIN}" ]] || die "Eclipse binary not found or not executable: ${OSGI_OTEL_ECLIPSE_BIN}"
[[ -f "${AGENT_JAR}" ]] || die "OTel agent JAR not found: ${AGENT_JAR}
Run from ${OSGI_OTEL_HOME}:
  ./mvnw -B install -DskipTests
  ./mvnw -B dependency:copy -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:${OSGI_OTEL_AGENT_VERSION} -DoutputDirectory=dist -Dmdep.stripVersion=true
  cp osgi-otel-extension/target/osgi-otel-extension-0.1.0-SNAPSHOT.jar dist/
  cp osgi-otel-file-exporter/target/osgi-otel-file-exporter-0.1.0-SNAPSHOT.jar dist/"
[[ -f "${INSTRUMENTATION_JAR}" ]] || die "Instrumentation extension JAR not found: ${INSTRUMENTATION_JAR}"
[[ -f "${FILE_EXPORTER_JAR}" ]] || die "File-exporter extension JAR not found: ${FILE_EXPORTER_JAR}"

mkdir -p "${OSGI_OTEL_WORKSPACE}" "${OSGI_OTEL_CONFIGURATION}"

# Both extension JARs are loaded by the OTel agent (comma-separated).
EXTENSIONS="${INSTRUMENTATION_JAR},${FILE_EXPORTER_JAR}"

# --- Exporter-specific configuration ---------------------------------------

case "${OSGI_OTEL_EXPORTER}" in
    file)
        EXPORTER_VMARGS=(
            "-Dotel.traces.exporter=file"
            "-Dotel.exporter.file.path=${OSGI_OTEL_TRACES_FILE}"
            "-Dotel.exporter.file.append=${OSGI_OTEL_TRACES_APPEND}"
        )
        EXPORTER_INFO="JSON Lines file: ${OSGI_OTEL_TRACES_FILE} (append=${OSGI_OTEL_TRACES_APPEND})"
        ;;
    otlp)
        EXPORTER_VMARGS=(
            "-Dotel.traces.exporter=otlp"
            "-Dotel.exporter.otlp.endpoint=${OTLP_ENDPOINT}"
        )
        EXPORTER_INFO="OTLP HTTP -> ${OTLP_ENDPOINT}"
        if command -v nc >/dev/null 2>&1; then
            if ! nc -z -G 1 "${OSGI_OTEL_JAEGER_HOST}" "${OSGI_OTEL_JAEGER_PORT}" 2>/dev/null; then
                cat >&2 <<EOF
WARNING: OTLP endpoint ${OTLP_ENDPOINT} is not reachable.
Spans will be buffered and eventually dropped. Start Jaeger first:

  docker run -d --rm --name jaeger \\
    -p 16686:16686 -p 4317:4317 -p 4318:4318 \\
    jaegertracing/all-in-one:latest

UI: http://localhost:16686

EOF
            fi
        fi
        ;;
    logging-otlp|logging)
        EXPORTER_VMARGS=("-Dotel.traces.exporter=${OSGI_OTEL_EXPORTER}")
        EXPORTER_INFO="${OSGI_OTEL_EXPORTER} -> stdout (mixed with Eclipse logs)"
        ;;
    *)
        die "Unknown OSGI_OTEL_EXPORTER='${OSGI_OTEL_EXPORTER}'. Use one of: file, otlp, logging-otlp, logging"
        ;;
esac

# --- Bundle filter VM args (only set when configured) ----------------------

FILTER_VMARGS=()
FILTER_INFO="off"
if [[ -n "${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY}" ]]; then
    FILTER_VMARGS+=("-Dosgi.filter.cross-bundle-only=${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY}")
    FILTER_INFO="cross-bundle-only=${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY}"
fi
if [[ -n "${OSGI_OTEL_FILTER_BUNDLE_INCLUDE}" ]]; then
    FILTER_VMARGS+=("-Dosgi.filter.bundle.include=${OSGI_OTEL_FILTER_BUNDLE_INCLUDE}")
    FILTER_INFO="${FILTER_INFO}, include=${OSGI_OTEL_FILTER_BUNDLE_INCLUDE}"
fi
if [[ -n "${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE}" ]]; then
    FILTER_VMARGS+=("-Dosgi.filter.bundle.exclude=${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE}")
    FILTER_INFO="${FILTER_INFO}, exclude=${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE}"
fi

EVENTS_VMARG=""
if [[ "${OSGI_OTEL_TRACE_EVENTS}" == "true" ]]; then
    EVENTS_VMARG="-Dotel.instrumentation.eclipse-events.enabled=true"
fi

TX_STACK_VMARG=""
if [[ "${OSGI_OTEL_TRACE_TX_STACK}" == "true" ]]; then
    TX_STACK_VMARG="-Dotel.instrumentation.hibernate-stack.enabled=true"
fi

# --- Application layer span VM args (only set when rules are configured) ---

# The app-span module is enabled by its rules, so the two refinements are only
# worth passing when rules are actually set.
APP_SPAN_VMARGS=()
APP_SPAN_INFO="off"
if [[ -n "${OSGI_OTEL_APP_SPAN_RULES}" ]]; then
    APP_SPAN_VMARGS+=("-Dotel.app.span.rules=${OSGI_OTEL_APP_SPAN_RULES}")
    APP_SPAN_INFO="rules=${OSGI_OTEL_APP_SPAN_RULES}"
    if [[ -n "${OSGI_OTEL_APP_SPAN_EXCLUDE_METHODS}" ]]; then
        APP_SPAN_VMARGS+=("-Dotel.app.span.exclude-methods=${OSGI_OTEL_APP_SPAN_EXCLUDE_METHODS}")
        APP_SPAN_INFO="${APP_SPAN_INFO}, exclude-methods=${OSGI_OTEL_APP_SPAN_EXCLUDE_METHODS}"
    fi
    if [[ -n "${OSGI_OTEL_APP_SPAN_CALLER_BUNDLE}" ]]; then
        APP_SPAN_VMARGS+=("-Dotel.app.span.caller-bundle=${OSGI_OTEL_APP_SPAN_CALLER_BUNDLE}")
        APP_SPAN_INFO="${APP_SPAN_INFO}, caller-bundle=${OSGI_OTEL_APP_SPAN_CALLER_BUNDLE}"
    fi
fi

# --- Summary --------------------------------------------------------------

cat <<EOF
==============================================================================
Launching Eclipse (Mylyn) with OpenTelemetry instrumentation

  Eclipse:       ${OSGI_OTEL_ECLIPSE_BIN}
  Workspace:     ${OSGI_OTEL_WORKSPACE}
  Configuration: ${OSGI_OTEL_CONFIGURATION}

  Agent:         ${AGENT_JAR}
  Extensions:    ${INSTRUMENTATION_JAR}
                 ${FILE_EXPORTER_JAR}

  Service name:  ${OSGI_OTEL_SERVICE_NAME}
  Exporter:      ${EXPORTER_INFO}
  Bundle filter: ${FILTER_INFO}
  App spans:     ${APP_SPAN_INFO}
==============================================================================
EOF

# --- Launch ---------------------------------------------------------------

# Note: -vmargs must be the LAST eclipse-level argument. Everything after it
# goes straight to the JVM.
exec "${OSGI_OTEL_ECLIPSE_BIN}" \
    -data "${OSGI_OTEL_WORKSPACE}" \
    -configuration "${OSGI_OTEL_CONFIGURATION}" \
    -consoleLog \
    -vmargs \
    -Xms256m \
    -Xmx2g \
    -javaagent:"${AGENT_JAR}" \
    -Dotel.javaagent.extensions="${EXTENSIONS}" \
    -Dotel.service.name="${OSGI_OTEL_SERVICE_NAME}" \
    "${EXPORTER_VMARGS[@]}" \
    ${FILTER_VMARGS[@]+"${FILTER_VMARGS[@]}"} \
    ${EVENTS_VMARG:+"${EVENTS_VMARG}"} \
    ${TX_STACK_VMARG:+"${TX_STACK_VMARG}"} \
    ${APP_SPAN_VMARGS[@]+"${APP_SPAN_VMARGS[@]}"} \
    -Dotel.metrics.exporter=none \
    -Dotel.logs.exporter=none \
    ${OSGI_OTEL_EXTRA_VMARGS}
