/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.eclipse;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.Arrays;
import java.util.List;

/**
 * DIAGNOSTIC, opt-in module: turns each fired model event into a nested span,
 * so event cascades become visible - tree depth is cascade depth, span count is
 * how many events one gesture triggers, and durations show the time cost.
 *
 * Instruments the two central fan-out points:
 *   - JavaBeans {@code PropertyChangeSupport.firePropertyChange} (also the JFace
 *     data-binding bean path), and
 *   - EMF {@code BasicNotifierImpl.eNotify}.
 *
 * Disabled by default because it is a firehose. Enable with
 * {@code -Dotel.instrumentation.eclipse-events.enabled=true}
 * (env {@code OTEL_INSTRUMENTATION_ECLIPSE_EVENTS_ENABLED=true}), or via the
 * launcher scripts' {@code OSGI_OTEL_TRACE_EVENTS=true}.
 */
@AutoService(InstrumentationModule.class)
public class EclipseEventsInstrumentationModule extends InstrumentationModule {

    public EclipseEventsInstrumentationModule() {
        super("eclipse-events");
    }

    @Override
    public boolean defaultEnabled(ConfigProperties config) {
        return false;
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Arrays.asList(new PropertyChangeInstrumentation(), new EmfNotifyInstrumentation());
    }
}
