/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.fileexporter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes spans as OTLP-shaped JSON Lines to a file (one JSON object per line).
 *
 * Not byte-for-byte identical to the OTLP protobuf-JSON canonical form, but
 * close enough to load into common tools and easy to read with jq.
 */
public class FileSpanExporter implements SpanExporter {

    private final Path file;
    private final BufferedWriter writer;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public FileSpanExporter(Path file, boolean append) throws IOException {
        this.file = file;
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    public Path getFile() {
        return file;
    }

    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        if (shutdown.get()) {
            return CompletableResultCode.ofFailure();
        }
        try {
            for (SpanData span : spans) {
                writer.write(toJson(span));
                writer.newLine();
            }
            writer.flush();
            return CompletableResultCode.ofSuccess();
        } catch (IOException e) {
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public synchronized CompletableResultCode flush() {
        if (shutdown.get()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            writer.flush();
            return CompletableResultCode.ofSuccess();
        } catch (IOException e) {
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public synchronized CompletableResultCode shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            try {
                writer.close();
                return CompletableResultCode.ofSuccess();
            } catch (IOException e) {
                return CompletableResultCode.ofFailure();
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    private static String toJson(SpanData span) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendKv(sb, "trace_id", span.getTraceId()).append(',');
        appendKv(sb, "span_id", span.getSpanId()).append(',');
        if (span.getParentSpanContext().isValid()) {
            appendKv(sb, "parent_span_id", span.getParentSpanId()).append(',');
        }
        appendKv(sb, "name", span.getName()).append(',');
        appendKv(sb, "kind", span.getKind().toString()).append(',');
        sb.append("\"start_unix_nano\":").append(span.getStartEpochNanos()).append(',');
        sb.append("\"end_unix_nano\":").append(span.getEndEpochNanos()).append(',');
        sb.append("\"status\":\"").append(span.getStatus().getStatusCode()).append("\",");

        sb.append("\"resource\":");
        appendAttributesAsObject(sb, span.getResource().getAttributes());
        sb.append(',');

        sb.append("\"scope\":{");
        appendKv(sb, "name", span.getInstrumentationScopeInfo().getName());
        sb.append("},");

        sb.append("\"attributes\":");
        appendAttributesAsObject(sb, span.getAttributes());

        List<EventData> events = span.getEvents();
        if (!events.isEmpty()) {
            sb.append(",\"events\":[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) sb.append(',');
                EventData ev = events.get(i);
                sb.append('{');
                appendKv(sb, "name", ev.getName()).append(',');
                sb.append("\"time_unix_nano\":").append(ev.getEpochNanos()).append(',');
                sb.append("\"attributes\":");
                appendAttributesAsObject(sb, ev.getAttributes());
                sb.append('}');
            }
            sb.append(']');
        }

        List<LinkData> links = span.getLinks();
        if (!links.isEmpty()) {
            sb.append(",\"links\":[");
            for (int i = 0; i < links.size(); i++) {
                if (i > 0) sb.append(',');
                LinkData ln = links.get(i);
                sb.append('{');
                appendKv(sb, "trace_id", ln.getSpanContext().getTraceId()).append(',');
                appendKv(sb, "span_id", ln.getSpanContext().getSpanId());
                sb.append('}');
            }
            sb.append(']');
        }

        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder appendKv(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        appendJsonString(sb, value);
        return sb;
    }

    private static void appendAttributesAsObject(StringBuilder sb, Attributes attrs) {
        sb.append('{');
        final boolean[] first = {true};
        attrs.forEach((AttributeKey<?> k, Object v) -> {
            if (!first[0]) sb.append(',');
            first[0] = false;
            appendJsonString(sb, k.getKey());
            sb.append(':');
            appendJsonValue(sb, v);
        });
        sb.append('}');
    }

    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof List) {
            sb.append('[');
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                appendJsonValue(sb, list.get(i));
            }
            sb.append(']');
        } else {
            appendJsonString(sb, value.toString());
        }
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
