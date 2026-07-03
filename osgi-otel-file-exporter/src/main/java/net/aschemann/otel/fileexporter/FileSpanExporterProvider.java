/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.fileexporter;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@AutoService(ConfigurableSpanExporterProvider.class)
public class FileSpanExporterProvider implements ConfigurableSpanExporterProvider {

    private static final String CONFIG_PATH = "otel.exporter.file.path";
    private static final String CONFIG_APPEND = "otel.exporter.file.append";
    private static final String DEFAULT_PATH = "traces.jsonl";

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public SpanExporter createExporter(ConfigProperties config) {
        String pathStr = config.getString(CONFIG_PATH, DEFAULT_PATH);
        Boolean appendOpt = config.getBoolean(CONFIG_APPEND);
        boolean append = appendOpt == null || appendOpt;

        Path path = Paths.get(pathStr).toAbsolutePath();
        try {
            FileSpanExporter exporter = new FileSpanExporter(path, append);
            System.err.println("[osgi-otel-file-exporter] writing spans to " + exporter.getFile()
                    + " (append=" + append + ")");
            return exporter;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open trace file: " + path, e);
        }
    }
}
