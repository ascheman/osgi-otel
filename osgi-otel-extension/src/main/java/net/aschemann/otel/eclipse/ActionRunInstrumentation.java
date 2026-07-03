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
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Instruments the zero-argument {@code run()} of every {@code IAction}
 * implementor - the classic JFace action framework still used alongside the
 * Commands framework in 3.x-style RCP applications.
 */
public class ActionRunInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("org.eclipse.jface.action.IAction");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return implementsInterface(named("org.eclipse.jface.action.IAction"));
    }

    @Override
    public void transform(TypeTransformer transformer) {
        // run() covers directly-invoked actions; runWithEvent(Event) covers actions
        // triggered via ActionContributionItem (the arg type is not referenced, so
        // no SWT compile dependency is needed).
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(named("run"))
                        .and(takesArguments(0)),
                ActionRunAdvice.class.getName());
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(named("runWithEvent"))
                        .and(takesArguments(1)),
                ActionRunAdvice.class.getName());
    }
}
