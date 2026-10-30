/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.it;

import io.opentelemetry.api.common.AttributeKey;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class AppSpanIT {

    private static final AttributeKey<String> RULE = AttributeKey.stringKey("app.span.rule");
    private static final AttributeKey<String> FUNCTION = AttributeKey.stringKey("code.function");
    private static final AttributeKey<String> NAMESPACE = AttributeKey.stringKey("code.namespace");
    private static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("osgi.provider.bundle");
    private static final AttributeKey<String> CALLER = AttributeKey.stringKey("osgi.caller.bundle");

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
    void matchedClassesProduceRuleSpansNestedUnderTheCallerSpan() throws Exception {
        BundleContext systemContext = framework.getBundleContext();

        String bundlePath = System.getProperty("test.bundle.path");
        assertThat(bundlePath).as("test.bundle.path system property").isNotNull();

        Bundle testBundle = systemContext.installBundle(new File(bundlePath).toURI().toString());
        testBundle.start();

        ServiceReference<?> controllerReference = systemContext.getServiceReference(
                "net.aschemann.otel.osgi.testbundle.controller.FakeController");
        assertThat(controllerReference).as("FakeController reference").isNotNull();
        Object controller = systemContext.getService(controllerReference);
        assertThat(controller).as("resolved FakeController").isNotNull();

        // Invoke via reflection: the test bundle is not on this classpath.
        Method handle = controller.getClass().getMethod("handle", String.class);
        String result = (String) handle.invoke(controller, "ping");
        assertThat(result).isEqualTo("worked:ping");

        // Excluded via otel.app.span.exclude-methods - must not produce a span.
        controller.getClass().getMethod("getVersion").invoke(controller);

        List<SpanData> spans = AgentTestingExporterAccess.getExportedSpans();

        SpanData controllerSpan = findSpan(spans, "ctrl FakeController.handle");
        assertThat(controllerSpan)
                .as("ctrl span for FakeController.handle. Captured spans: %s", spanNames(spans))
                .isNotNull();
        assertThat(controllerSpan.getAttributes().asMap())
                .containsEntry(RULE, "ctrl")
                .containsEntry(FUNCTION, "net.aschemann.otel.osgi.testbundle.controller.FakeController.handle")
                .containsEntry(NAMESPACE, "net.aschemann.otel.osgi.testbundle.controller")
                .containsEntry(PROVIDER, "net.aschemann.otel.osgi.testbundle");

        SpanData serviceSpan = findSpan(spans, "service AppServiceImpl.doWork");
        assertThat(serviceSpan)
                .as("service span for AppServiceImpl.doWork. Captured spans: %s", spanNames(spans))
                .isNotNull();
        assertThat(serviceSpan.getAttributes().asMap())
                .containsEntry(RULE, "service")
                .containsEntry(FUNCTION, "net.aschemann.otel.osgi.testbundle.internal.AppServiceImpl.doWork")
                .containsEntry(NAMESPACE, "net.aschemann.otel.osgi.testbundle.internal")
                .containsEntry(PROVIDER, "net.aschemann.otel.osgi.testbundle")
                .containsEntry(CALLER, "net.aschemann.otel.osgi.testbundle");

        // doWork is called inside handle(): the service span must nest under the controller span.
        assertThat(serviceSpan.getParentSpanId()).isEqualTo(controllerSpan.getSpanId());

        // The method excluded via otel.app.span.exclude-methods must not produce a span.
        assertThat(spans)
                .as("no span for the excluded getVersion method")
                .noneMatch(span -> span.getName().contains("getVersion"));
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) {
                return span;
            }
        }
        return null;
    }

    private static String spanNames(List<SpanData> spans) {
        StringBuilder names = new StringBuilder();
        for (SpanData span : spans) {
            names.append('\n').append("  ").append(span.getName());
        }
        return names.toString();
    }
}
