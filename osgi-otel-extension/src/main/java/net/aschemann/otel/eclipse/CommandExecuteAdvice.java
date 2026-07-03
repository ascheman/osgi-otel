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

        String label = (commandName != null && !commandName.isEmpty()) ? commandName : commandId;
        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        span = tracer.spanBuilder("command " + label)
                .setAttribute("eclipse.command.id", commandId)
                .setAttribute("eclipse.command.name", commandName == null ? "" : commandName)
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
