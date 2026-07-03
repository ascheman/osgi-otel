/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

public class BundleContextGetServiceAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.osgi";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This BundleContext callerContext,
            @Advice.Argument(0) ServiceReference<?> reference,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        String callerBsn = "unknown";
        if (callerContext != null) {
            Bundle callerBundle = callerContext.getBundle();
            if (callerBundle != null && callerBundle.getSymbolicName() != null) {
                callerBsn = callerBundle.getSymbolicName();
            }
        }

        String serviceClass = "unknown";
        String providerBsn = "unknown";
        if (reference != null) {
            Object objectClass = reference.getProperty(Constants.OBJECTCLASS);
            if (objectClass instanceof String[] && ((String[]) objectClass).length > 0) {
                serviceClass = ((String[]) objectClass)[0];
            }
            Bundle providerBundle = reference.getBundle();
            if (providerBundle != null && providerBundle.getSymbolicName() != null) {
                providerBsn = providerBundle.getSymbolicName();
            }
        }

        if (!BundleSpanFilter.shouldEmit(callerBsn, providerBsn)) {
            return;
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        span = tracer.spanBuilder("BundleContext.getService " + serviceClass)
                .setAttribute("osgi.caller.bundle", callerBsn)
                .setAttribute("osgi.provider.bundle", providerBsn)
                .setAttribute("osgi.service.class", serviceClass)
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
