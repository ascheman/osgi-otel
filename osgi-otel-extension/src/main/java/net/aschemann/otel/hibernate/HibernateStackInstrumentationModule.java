/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.hibernate;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.util.Collections;
import java.util.List;

/**
 * DIAGNOSTIC, opt-in module: captures the call stack at the start of a Hibernate
 * transaction ({@code org.hibernate.Transaction.begin} and {@code commit}) and
 * emits it as a span carrying {@code code.stacktrace}.
 *
 * Motivation: transactions (and their {@code Transaction.commit} spans) often
 * appear as orphan root spans, because the gesture that triggered them ran on
 * another thread (an Eclipse {@code Job}, {@code Display.asyncExec}, an auto-save)
 * across which the trace context is not yet propagated. The call stack is
 * thread-local and therefore reveals the caller chain even when the trace link
 * was lost - {@code begin} shows who opened the unit of work, {@code commit} shows
 * who forced the write.
 *
 * Disabled by default. Enable with
 * {@code -Dotel.instrumentation.hibernate-stack.enabled=true}
 * (env {@code OTEL_INSTRUMENTATION_HIBERNATE_STACK_ENABLED=true}), or via the
 * launcher scripts' {@code OSGI_OTEL_TRACE_TX_STACK=true}. Cap the recorded stack
 * depth with {@code -Dosgi.capture.stack.max=N} (default 32).
 */
@AutoService(InstrumentationModule.class)
public class HibernateStackInstrumentationModule extends InstrumentationModule {

    public HibernateStackInstrumentationModule() {
        super("hibernate-stack");
    }

    @Override
    public boolean defaultEnabled(ConfigProperties config) {
        return false;
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new TransactionInstrumentation());
    }
}
