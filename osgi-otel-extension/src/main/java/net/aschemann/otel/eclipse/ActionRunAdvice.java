/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.eclipse;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import org.eclipse.jface.action.IAction;

public class ActionRunAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.eclipse";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This IAction action,
            @Advice.Argument(value = 0, optional = true) Object triggerEvent,
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

        // --- business context (A/C): triggering SWT widget from runWithEvent(Event) ---
        // triggerEvent is bound only for runWithEvent(Event) (null for the no-arg run()).
        // SWT is off the compile classpath, so read Event.widget / widget.getText()
        // reflectively. C (text) is opt-in via -Dosgi.capture.widget.text=true.
        String widgetClass = "";
        String widgetText = "";
        if (triggerEvent != null) {
            Object widget = null;
            try {
                Field widgetField = triggerEvent.getClass().getField("widget");
                widget = widgetField.get(triggerEvent);
            } catch (Throwable ignored) {
                // argument is not an SWT Event, or has no accessible widget field
            }
            if (widget != null) {
                widgetClass = widget.getClass().getName();
                if (Boolean.getBoolean("osgi.capture.widget.text")) {
                    try {
                        Object text = widget.getClass().getMethod("getText").invoke(widget);
                        if (text instanceof String && !((String) text).isEmpty()) {
                            String value = (String) text;
                            int max = Integer.getInteger("osgi.capture.widget.text.max", 64);
                            if (max > 0 && value.length() > max) {
                                value = value.substring(0, max);
                            }
                            widgetText = value;
                        }
                    } catch (Throwable ignored) {
                        // widget has no getText(), or reading it failed
                    }
                }
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
        SpanBuilder builder = tracer.spanBuilder("action " + label)
                .setAttribute("eclipse.action.id", actionId)
                .setAttribute("eclipse.action.text", actionText)
                .setAttribute("eclipse.action.class", actionClass);
        if (!widgetClass.isEmpty()) {
            builder.setAttribute("eclipse.trigger.widget.class", widgetClass);
        }
        if (!widgetText.isEmpty()) {
            builder.setAttribute("eclipse.trigger.widget.text", widgetText);
        }
        span = builder.startSpan();
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
