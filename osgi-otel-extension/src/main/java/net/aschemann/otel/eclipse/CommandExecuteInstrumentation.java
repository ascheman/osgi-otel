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
 * Instruments {@code org.eclipse.core.commands.Command.executeWithChecks} - the
 * single chokepoint every command (menu, toolbar, key binding) passes through.
 */
public class CommandExecuteInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("org.eclipse.core.commands.Command");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.eclipse.core.commands.Command");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(named("executeWithChecks"))
                        .and(takesArguments(1))
                        .and(takesArgument(0, named("org.eclipse.core.commands.ExecutionEvent"))),
                CommandExecuteAdvice.class.getName());
    }
}
