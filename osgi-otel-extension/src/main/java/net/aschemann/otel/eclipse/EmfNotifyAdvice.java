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
import org.eclipse.emf.common.notify.Notification;

public class EmfNotifyAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.eclipse.events";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) Notification notification,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        if (notification == null || notification.isTouch()) {
            return; // skip no-op notifications
        }

        // IMPORTANT: never touch the EMF metamodel here. getFeature()/getFeatureID()
        // reach into EClass and can re-enter EcorePackage while it is still being
        // class-initialized (eNotify fires during that init), which poisons
        // EcorePackage$Literals JVM-wide. Only the notifier's runtime class and the
        // primitive event type are safe to read.
        String sourceClass = "unknown";
        Object notifier = notification.getNotifier();
        if (notifier != null) {
            sourceClass = notifier.getClass().getName();
        }
        String simpleSource = sourceClass;
        int dot = sourceClass.lastIndexOf('.');
        if (dot >= 0 && dot < sourceClass.length() - 1) {
            simpleSource = sourceClass.substring(dot + 1);
        }

        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        span = tracer.spanBuilder("emf.notify " + simpleSource)
                .setAttribute("eclipse.event.kind", "emfNotify")
                .setAttribute("eclipse.event.source", sourceClass)
                .setAttribute("eclipse.event.type", (long) notification.getEventType())
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
