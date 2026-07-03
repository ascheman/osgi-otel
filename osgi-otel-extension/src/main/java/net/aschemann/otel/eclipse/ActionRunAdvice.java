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
import org.eclipse.jface.action.IAction;

public class ActionRunAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.eclipse";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This IAction action,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        String actionId = "";
        String actionText = "";
        String actionClass = "unknown";
        if (action != null) {
            actionClass = action.getClass().getName();
            if (action.getId() != null) {
                actionId = action.getId();
            }
            if (action.getText() != null) {
                actionText = action.getText();
            }
        }

        String label;
        if (!actionText.isEmpty()) {
            label = actionText;
        } else if (!actionId.isEmpty()) {
            label = actionId;
        } else {
            label = actionClass;
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        span = tracer.spanBuilder("action " + label)
                .setAttribute("eclipse.action.id", actionId)
                .setAttribute("eclipse.action.text", actionText)
                .setAttribute("eclipse.action.class", actionClass)
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
