/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.eclipse;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Instruments {@code org.eclipse.emf.common.notify.impl.BasicNotifierImpl.eNotify} -
 * the point every EMF model change notifies its adapters (and, via EMF
 * data-binding, dependent UI).
 */
public class EmfNotifyInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("org.eclipse.emf.common.notify.Notification");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.eclipse.emf.common.notify.impl.BasicNotifierImpl");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(named("eNotify"))
                        .and(takesArguments(1))
                        .and(takesArgument(0, named("org.eclipse.emf.common.notify.Notification"))),
                EmfNotifyAdvice.class.getName());
    }
}
