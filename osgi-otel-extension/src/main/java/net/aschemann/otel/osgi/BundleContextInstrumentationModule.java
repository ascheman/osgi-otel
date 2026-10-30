/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Collections;
import java.util.List;

@AutoService(InstrumentationModule.class)
public class BundleContextInstrumentationModule extends InstrumentationModule {

    public BundleContextInstrumentationModule() {
        super("osgi-bundle-context", "osgi");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new BundleContextInstrumentation());
    }

    /**
     * Marks the module's own classes as helper classes (in addition to the
     * agent's defaults), so that they are never treated as required library
     * classes by the muzzle.
     */
    @Override
    public boolean isHelperClass(String className) {
        return className.startsWith("net.aschemann.otel.osgi.");
    }

    /**
     * Names the class referenced from the inlined advice
     * ({@link BundleSpanFilter}). The agent injects this helper class into
     * the classloader of the instrumented library; without this, the inlined
     * code cannot resolve it at runtime in classloaders that do not share
     * the agent's classpath (OSGi bundle classloaders) and every span fails
     * silently with a {@link NoClassDefFoundError}.
     */
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Collections.singletonList(
                "net.aschemann.otel.osgi.BundleSpanFilter");
    }
}
