/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.eclipse;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeSupport;

public class PropertyChangeAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.eclipse.events";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This PropertyChangeSupport support,
            @Advice.Argument(0) PropertyChangeEvent event,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        String property = "?";
        String sourceClass = "unknown";
        if (event != null) {
            if (event.getPropertyName() != null) {
                property = event.getPropertyName();
            }
            if (event.getSource() != null) {
                sourceClass = event.getSource().getClass().getName();
            }
        }
        long listeners = 0L;
        if (support != null) {
            listeners = support.getPropertyChangeListeners().length;
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        span = tracer.spanBuilder("propertyChange " + property)
                .setAttribute("eclipse.event.kind", "propertyChange")
                .setAttribute("eclipse.event.property", property)
                .setAttribute("eclipse.event.source", sourceClass)
                .setAttribute("eclipse.event.listeners", listeners)
                .startSpan();
        scope = span.makeCurrent();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Thrown Throwable throwable,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        if (scope != null) {
            scope.close();
        }
        if (span != null) {
            if (throwable != null) {
                span.recordException(throwable);
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        }
    }
}
