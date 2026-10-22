#!/usr/bin/env bash
#
# TEMPLATE - attach the OpenTelemetry Java Agent + osgi-otel extension to an
# arbitrary OSGi/JVM application and export inter-bundle spans.
#
# Copy this file, then set OSGI_OTEL_APP_LAUNCHER (and, if needed,
# OSGI_OTEL_LAUNCH_STYLE / OSGI_OTEL_APP_ARGS). Every OSGI_OTEL_* variable is
# OPTIONAL and has a default; the ONLY thing you must supply is how to start
# your application.
#
# A fully worked, application-specific example lives next to this file:
#   samples/run-mylyn-with-otel.sh   (Eclipse / Mylyn)
#
set -euo pipefail

# ============================================================================
# 1. REQUIRED: how to launch YOUR application
# ============================================================================
# The launcher executable, or "java". No default - you must set this.
OSGI_OTEL_APP_LAUNCHER="${OSGI_OTEL_APP_LAUNCHER:-}"        # e.g. /opt/myapp/myapp   or   java

# Arguments for the launcher (NOT the JVM/OTel args - those are built below).
OSGI_OTEL_APP_ARGS=( )                                     # e.g. ( -jar /opt/myapp/app.jar )   or   ( -consoleLog )

# Where the OTel JVM args must sit relative to the launcher:
#   jvm-before      launcher <otel-args> <app-args>              (plain java, bare Equinox: args before -jar)
#   eclipse-vmargs  launcher <app-args> -vmargs <otel-args>      (Eclipse product; -vmargs must be LAST)
OSGI_OTEL_LAUNCH_STYLE="${OSGI_OTEL_LAUNCH_STYLE:-jvm-before}"

# ============================================================================
# 2. OTel libraries (optional; defaults assume this project's dist/ layout)
# ============================================================================
OSGI_OTEL_HOME="${OSGI_OTEL_HOME:-$(cd "$(dirname "$0")/.." && pwd)}"
OSGI_OTEL_LIB_DIR="${OSGI_OTEL_LIB_DIR:-${OSGI_OTEL_HOME}/dist}"
AGENT_JAR="${OSGI_OTEL_AGENT_JAR:-${OSGI_OTEL_LIB_DIR}/opentelemetry-javaagent.jar}"
INSTRUMENTATION_JAR="${OSGI_OTEL_EXTENSION_JAR:-${OSGI_OTEL_LIB_DIR}/osgi-otel-extension-0.1.0-SNAPSHOT.jar}"
FILE_EXPORTER_JAR="${OSGI_OTEL_FILE_EXPORTER_JAR:-${OSGI_OTEL_LIB_DIR}/osgi-otel-file-exporter-0.1.0-SNAPSHOT.jar}"

# ============================================================================
# 3. Telemetry configuration (all optional)
# ============================================================================
OSGI_OTEL_SERVICE_NAME="${OSGI_OTEL_SERVICE_NAME:-osgi-app}"
OSGI_OTEL_EXPORTER="${OSGI_OTEL_EXPORTER:-file}"           # file | otlp | logging-otlp | logging
OSGI_OTEL_TRACES_FILE="${OSGI_OTEL_TRACES_FILE:-${OSGI_OTEL_HOME}/traces.jsonl}"
OSGI_OTEL_TRACES_APPEND="${OSGI_OTEL_TRACES_APPEND:-true}"
OSGI_OTEL_OTLP_ENDPOINT="${OSGI_OTEL_OTLP_ENDPOINT:-http://localhost:4318}"

# Bundle filters (all optional; leave empty to disable)
OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY="${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY:-}"
OSGI_OTEL_FILTER_BUNDLE_INCLUDE="${OSGI_OTEL_FILTER_BUNDLE_INCLUDE:-}"
OSGI_OTEL_FILTER_BUNDLE_EXCLUDE="${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE:-}"

# Diagnostics (opt-in): trace the Eclipse event cascade (EMF eNotify + JavaBeans
# PropertyChange). This is a firehose - only enable it while investigating.
OSGI_OTEL_TRACE_EVENTS="${OSGI_OTEL_TRACE_EVENTS:-}"

# Diagnostics (opt-in): capture the call stack at Hibernate Transaction
# begin/commit (code.stacktrace), to reveal what triggered an orphan transaction.
OSGI_OTEL_TRACE_TX_STACK="${OSGI_OTEL_TRACE_TX_STACK:-}"

OSGI_OTEL_EXTRA_VMARGS="${OSGI_OTEL_EXTRA_VMARGS:-}"

# ============================================================================
# Sanity checks
# ============================================================================
die() { echo "ERROR: $*" >&2; exit 1; }

[[ -n "${OSGI_OTEL_APP_LAUNCHER}" ]] \
    || die "Set OSGI_OTEL_APP_LAUNCHER to your application launcher (or \"java\")."
command -v "${OSGI_OTEL_APP_LAUNCHER}" >/dev/null 2>&1 || [[ -x "${OSGI_OTEL_APP_LAUNCHER}" ]] \
    || die "Launcher not found or not executable: ${OSGI_OTEL_APP_LAUNCHER}"
for jar in "${AGENT_JAR}" "${INSTRUMENTATION_JAR}" "${FILE_EXPORTER_JAR}"; do
    [[ -f "${jar}" ]] || die "Missing JAR: ${jar}
Build and assemble dist/ first (see the project README 'Quick start'), or point
OSGI_OTEL_LIB_DIR / OSGI_OTEL_*_JAR at your own location."
done

# ============================================================================
# Build the OTel JVM args
# ============================================================================
EXTENSIONS="${INSTRUMENTATION_JAR},${FILE_EXPORTER_JAR}"

OTEL_ARGS=(
    "-javaagent:${AGENT_JAR}"
    "-Dotel.javaagent.extensions=${EXTENSIONS}"
    "-Dotel.service.name=${OSGI_OTEL_SERVICE_NAME}"
    "-Dotel.metrics.exporter=none"
    "-Dotel.logs.exporter=none"
)

case "${OSGI_OTEL_EXPORTER}" in
    file)
        OTEL_ARGS+=(
            "-Dotel.traces.exporter=file"
            "-Dotel.exporter.file.path=${OSGI_OTEL_TRACES_FILE}"
            "-Dotel.exporter.file.append=${OSGI_OTEL_TRACES_APPEND}"
        ) ;;
    otlp)
        OTEL_ARGS+=(
            "-Dotel.traces.exporter=otlp"
            "-Dotel.exporter.otlp.endpoint=${OSGI_OTEL_OTLP_ENDPOINT}"
        ) ;;
    logging-otlp|logging)
        OTEL_ARGS+=( "-Dotel.traces.exporter=${OSGI_OTEL_EXPORTER}" ) ;;
    *)
        die "Unknown OSGI_OTEL_EXPORTER='${OSGI_OTEL_EXPORTER}' (file|otlp|logging-otlp|logging)" ;;
esac

[[ -n "${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY}" ]] && OTEL_ARGS+=( "-Dosgi.filter.cross-bundle-only=${OSGI_OTEL_FILTER_CROSS_BUNDLE_ONLY}" )
[[ -n "${OSGI_OTEL_FILTER_BUNDLE_INCLUDE}" ]]     && OTEL_ARGS+=( "-Dosgi.filter.bundle.include=${OSGI_OTEL_FILTER_BUNDLE_INCLUDE}" )
[[ -n "${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE}" ]]     && OTEL_ARGS+=( "-Dosgi.filter.bundle.exclude=${OSGI_OTEL_FILTER_BUNDLE_EXCLUDE}" )
[[ "${OSGI_OTEL_TRACE_EVENTS}" == "true" ]]       && OTEL_ARGS+=( "-Dotel.instrumentation.eclipse-events.enabled=true" )
[[ "${OSGI_OTEL_TRACE_TX_STACK}" == "true" ]]     && OTEL_ARGS+=( "-Dotel.instrumentation.hibernate-stack.enabled=true" )
# shellcheck disable=SC2206  # intentional word splitting for extra -D args
[[ -n "${OSGI_OTEL_EXTRA_VMARGS}" ]]              && OTEL_ARGS+=( ${OSGI_OTEL_EXTRA_VMARGS} )

# ============================================================================
# Launch
# ============================================================================
case "${OSGI_OTEL_LAUNCH_STYLE}" in
    jvm-before)
        exec "${OSGI_OTEL_APP_LAUNCHER}" \
            "${OTEL_ARGS[@]}" \
            ${OSGI_OTEL_APP_ARGS[@]+"${OSGI_OTEL_APP_ARGS[@]}"}
        ;;
    eclipse-vmargs)
        exec "${OSGI_OTEL_APP_LAUNCHER}" \
            ${OSGI_OTEL_APP_ARGS[@]+"${OSGI_OTEL_APP_ARGS[@]}"} \
            -vmargs "${OTEL_ARGS[@]}"
        ;;
    *)
        die "Unknown OSGI_OTEL_LAUNCH_STYLE='${OSGI_OTEL_LAUNCH_STYLE}' (jvm-before|eclipse-vmargs)" ;;
esac
