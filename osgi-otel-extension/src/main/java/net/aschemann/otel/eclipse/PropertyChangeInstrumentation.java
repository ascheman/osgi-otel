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
 * Instruments {@code java.beans.PropertyChangeSupport.firePropertyChange(PropertyChangeEvent)} -
 * the point every JavaBeans property change (including JFace data-binding bean
 * observables) fans out to its listeners.
 *
 * Note: this is a JDK class; if the agent cannot instrument it in a given host,
 * no property-change spans appear and only the EMF hook is effective.
 */
public class PropertyChangeInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("java.beans.PropertyChangeSupport");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("java.beans.PropertyChangeSupport");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(named("firePropertyChange"))
                        .and(takesArguments(1))
                        .and(takesArgument(0, named("java.beans.PropertyChangeEvent"))),
                PropertyChangeAdvice.class.getName());
    }
}
