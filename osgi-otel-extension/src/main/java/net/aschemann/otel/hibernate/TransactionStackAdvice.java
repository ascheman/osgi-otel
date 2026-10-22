/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.hibernate;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

public class TransactionStackAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.hibernate";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Origin("#m") String method,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        // The advice is inlined into begin()/commit(), so frames[0] is the
        // getStackTrace() call and frames[1] is that very method; everything from
        // frames[1] on is the caller chain that led here - visible even when the
        // trace context was lost across an async (Job / asyncExec) hop.
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        int max = Integer.getInteger("osgi.capture.stack.max", 32);
        StringBuilder stack = new StringBuilder();
        for (int i = 1, count = 0; i < frames.length && count < max; i++, count++) {
            stack.append("at ").append(frames[i].toString()).append('\n');
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        SpanBuilder builder = tracer.spanBuilder("hibernate.tx." + method)
                .setAttribute("code.function", method)
                .setAttribute("code.stacktrace", stack.toString());
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
