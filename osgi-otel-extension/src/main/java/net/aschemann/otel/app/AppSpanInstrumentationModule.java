/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Generic, rule-based instrumentation: opens a span for every call of a
 * method whose declaring class matches one of the configured span rules
 * ({@code otel.app.span.rules}).
 *
 * <p>Typical use: put a span on the layer boundaries of an application that
 * does not route its internal calls through the OSGi service registry - e.g.
 * all business-service methods (by a shared base class) and all controller
 * methods (by a package regex) - so the inter-layer data flow is visible in
 * the trace without touching the application code.
 *
 * <p>Each span is named {@code <prefix> <RuntimeClass>.<method>} (the
 * runtime class of the receiver, so overridden methods show the concrete
 * implementation) and carries {@code app.span.rule}, {@code code.function},
 * {@code code.namespace}, {@code osgi.provider.bundle} (the bundle of the
 * instrumented class) and {@code osgi.caller.bundle} (best-effort, from the
 * direct caller's class; {@code unknown} when it cannot be resolved).
 *
 * <p>Enabled only when {@code otel.app.span.rules} is set; an explicit
 * {@code -Dotel.instrumentation.app-span.enabled=false} always wins.
 */
@AutoService(InstrumentationModule.class)
public class AppSpanInstrumentationModule extends InstrumentationModule {

    public AppSpanInstrumentationModule() {
        super("app-span");
    }

    @Override
    public boolean defaultEnabled(ConfigProperties config) {
        return AppSpanConfig.hasRules();
    }

    /**
     * Marks the module's own classes as helper classes (in addition to the
     * agent's defaults), so that they are never treated as required library
     * classes by the muzzle.
     */
    @Override
    public boolean isHelperClass(String className) {
        return className.startsWith("net.aschemann.otel.app.");
    }

    /**
     * Names the classes referenced from the inlined advice
     * ({@link AppSpanConfig}, {@link AppSpanAdvice} and, through them,
     * {@link AppSpanRule}). The agent injects these helper classes into the
     * classloader of the instrumented library; without this, the inlined code
     * cannot resolve them at runtime in classloaders that do not share the
     * agent's classpath (OSGi bundle classloaders) and every span fails
     * silently with a {@link NoClassDefFoundError}.
     */
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList(
                "net.aschemann.otel.app.AppSpanAdvice",
                "net.aschemann.otel.app.AppSpanConfig",
                "net.aschemann.otel.app.AppSpanRule");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new AppSpanInstrumentation());
    }
}
