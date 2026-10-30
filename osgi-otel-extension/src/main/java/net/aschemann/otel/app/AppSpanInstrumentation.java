/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.List;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.not;

public class AppSpanInstrumentation implements TypeInstrumentation {

    private final ElementMatcher<TypeDescription> matcher =
            new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
                @Override
                public boolean matches(TypeDescription type) {
                    for (AppSpanRule rule : AppSpanConfig.rules()) {
                        if (rule.matches(type)) {
                            return true;
                        }
                    }
                    return false;
                }
            };

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        // A regex rule can match a class in any classloader; only base-class
        // rules allow narrowing to loaders that can see at least one base.
        if (AppSpanConfig.hasRegexRules()) {
            return ElementMatchers.any();
        }
        List<String> baseClasses = AppSpanConfig.baseClasses();
        if (baseClasses.isEmpty()) {
            return ElementMatchers.any();
        }
        // hasClassesNamed(...) is AND-combined for multiple names, so the
        // base classes must be OR-combined explicitly.
        ElementMatcher.Junction<ClassLoader> result = hasClassesNamed(baseClasses.get(0));
        for (int i = 1; i < baseClasses.size(); i++) {
            result = result.or(hasClassesNamed(baseClasses.get(i)));
        }
        return result;
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return matcher;
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(not(isConstructor()))
                        .and(not(isSynthetic()))
                        .and(not(isBridge()))
                        .and(not(methodNameExcluded())),
                AppSpanAdvice.class.getName());
    }

    /** Methods whose name fully matches the configured exclude regex. */
    private static ElementMatcher<MethodDescription> methodNameExcluded() {
        return new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
            @Override
            public boolean matches(MethodDescription method) {
                return AppSpanConfig.excludeMethods().matcher(method.getName()).matches();
            }
        };
    }
}
