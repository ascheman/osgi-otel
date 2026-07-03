/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.eclipse;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Arrays;
import java.util.List;

/**
 * Instruments Eclipse UI actions so that each user gesture becomes a span that
 * downstream work (OSGi service lookups, HTTP, DB) nests under.
 *
 * Two chokepoints are covered:
 *   - the Commands framework ({@code Command.executeWithChecks}), and
 *   - classic JFace actions ({@code IAction.run}).
 *
 * Disable via the OpenTelemetry agent with
 * {@code -Dotel.instrumentation.eclipse-ui.enabled=false}.
 */
@AutoService(InstrumentationModule.class)
public class EclipseUiInstrumentationModule extends InstrumentationModule {

    public EclipseUiInstrumentationModule() {
        super("eclipse-ui", "eclipse");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Arrays.asList(new CommandExecuteInstrumentation(), new ActionRunInstrumentation());
    }
}
