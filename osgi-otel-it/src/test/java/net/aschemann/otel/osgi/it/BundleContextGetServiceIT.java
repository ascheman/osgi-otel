/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.it;

import io.opentelemetry.javaagent.testing.common.AgentTestingExporterAccess;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class BundleContextGetServiceIT {

    private Framework framework;

    @BeforeEach
    void startFramework() throws Exception {
        AgentTestingExporterAccess.reset();

        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        Map<String, String> config = new HashMap<>();
        File storage = Files.createTempDirectory("equinox-it-").toFile();
        config.put("org.osgi.framework.storage", storage.getAbsolutePath());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");

        framework = factory.newFramework(config);
        framework.init();
        framework.start();
    }

    @AfterEach
    void stopFramework() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10_000);
        }
    }

    @Test
    void getServiceCallProducesSpan() throws Exception {
        BundleContext systemContext = framework.getBundleContext();

        String bundlePath = System.getProperty("test.bundle.path");
        assertThat(bundlePath).as("test.bundle.path system property").isNotNull();

        Bundle testBundle = systemContext.installBundle(new File(bundlePath).toURI().toString());
        testBundle.start();

        ServiceReference<?> reference = systemContext.getServiceReference(
                "net.aschemann.otel.osgi.testbundle.api.HelloService");
        assertThat(reference).as("HelloService reference").isNotNull();

        Object service = systemContext.getService(reference);
        assertThat(service).as("resolved HelloService").isNotNull();

        List<SpanData> spans = AgentTestingExporterAccess.getExportedSpans();

        SpanData helloServiceSpan = spans.stream()
                .filter(s -> s.getName().contains("HelloService"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No span for HelloService found. Captured spans: "
                                + spans.stream().map(SpanData::getName).reduce("", (a, b) -> a + "\n  " + b)));

        assertThat(helloServiceSpan.getName())
                .isEqualTo("BundleContext.getService net.aschemann.otel.osgi.testbundle.api.HelloService");
        assertThat(helloServiceSpan.getAttributes().asMap())
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("osgi.service.class"),
                        "net.aschemann.otel.osgi.testbundle.api.HelloService")
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("osgi.provider.bundle"),
                        "net.aschemann.otel.osgi.testbundle")
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("osgi.caller.bundle"),
                        "org.eclipse.osgi");
    }
}
