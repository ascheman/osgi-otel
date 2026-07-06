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
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;

public class CommandExecuteAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.eclipse";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Command command,
            @Advice.Argument(0) ExecutionEvent event,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        Command cmd = command;
        if (event != null && event.getCommand() != null) {
            cmd = event.getCommand();
        }

        String commandId = "unknown";
        String commandName = "";
        if (cmd != null) {
            if (cmd.getId() != null) {
                commandId = cmd.getId();
            }
            try {
                commandName = cmd.getName();
            } catch (Throwable ignored) {
                // Command.getName() throws NotDefinedException when the command has no name
            }
        }

        // --- business context (A): triggering SWT widget, and (C): its text ---
        // SWT is not on the compile classpath (platform-specific), so read the
        // public Event.widget field and widget.getText() reflectively, tolerating
        // any failure. C is opt-in via -Dosgi.capture.widget.text=true.
        String widgetClass = "";
        String widgetText = "";
        Object trigger = (event != null) ? event.getTrigger() : null;
        if (trigger != null) {
            Object widget = null;
            try {
                Field widgetField = trigger.getClass().getField("widget");
                widget = widgetField.get(trigger);
            } catch (Throwable ignored) {
                // trigger is not an SWT Event, or has no accessible widget field
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

        // --- business context (B): the current workbench selection (domain object) ---
        // The class/size are type-only info. The element's label (its toString) is
        // actual domain data (PII), so it is opt-in via -Dosgi.capture.selection.label=true.
        String selectionClass = "";
        long selectionSize = -1;
        String selectionLabel = "";
        if (event != null) {
            try {
                Object appCtx = event.getApplicationContext();
                if (appCtx != null) {
                    Object selection = appCtx.getClass()
                            .getMethod("getVariable", String.class)
                            .invoke(appCtx, "selection");
                    if (selection != null) {
                        selectionClass = selection.getClass().getName();
                        try {
                            Object size = selection.getClass().getMethod("size").invoke(selection);
                            if (size instanceof Integer) {
                                selectionSize = ((Integer) size).longValue();
                            }
                        } catch (Throwable ignored) {
                            // not an IStructuredSelection
                        }
                        try {
                            Object first = selection.getClass().getMethod("getFirstElement").invoke(selection);
                            if (first != null) {
                                selectionClass = first.getClass().getName();
                                if (Boolean.getBoolean("osgi.capture.selection.label")) {
                                    try {
                                        String value = String.valueOf(first);
                                        if (value != null && !value.isEmpty()) {
                                            int max = Integer.getInteger("osgi.capture.selection.label.max", 64);
                                            if (max > 0 && value.length() > max) {
                                                value = value.substring(0, max);
                                            }
                                            selectionLabel = value;
                                        }
                                    } catch (Throwable ignored) {
                                        // element toString() failed
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            // not an IStructuredSelection
                        }
                    }
                }
            } catch (Throwable ignored) {
                // no evaluation context, or no "selection" variable
            }
        }

        String label = (commandName != null && !commandName.isEmpty()) ? commandName : commandId;
        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        SpanBuilder builder = tracer.spanBuilder("command " + label)
                .setAttribute("eclipse.command.id", commandId)
                .setAttribute("eclipse.command.name", commandName == null ? "" : commandName);
        if (!widgetClass.isEmpty()) {
            builder.setAttribute("eclipse.trigger.widget.class", widgetClass);
        }
        if (!widgetText.isEmpty()) {
            builder.setAttribute("eclipse.trigger.widget.text", widgetText);
        }
        if (!selectionClass.isEmpty()) {
            builder.setAttribute("eclipse.selection.class", selectionClass);
        }
        if (selectionSize >= 0) {
            builder.setAttribute("eclipse.selection.size", selectionSize);
        }
        if (!selectionLabel.isEmpty()) {
            builder.setAttribute("eclipse.selection.label", selectionLabel);
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
