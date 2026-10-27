/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The agent injects this class into the classloader of the instrumented
 * library (see {@link AppSpanInstrumentationModule#getAdditionalHelperClassNames()}).
 * The inlined advice keeps {@code invokestatic}/{@code getstatic} references
 * to the helper methods and static fields below, which run in the host
 * class's classloader - hence public.
 */
public class AppSpanAdvice {

    private static final String INSTRUMENTATION_NAME = "net.aschemann.otel.app";

    /**
     * declaring type name to bundle symbolic name (or {@code unknown});
     * public because inlined advice code may reference the field directly.
     */
    public static final ConcurrentMap<String, String> PROVIDER_BSN = new ConcurrentHashMap<>();
    /**
     * caller class name to bundle symbolic name (or {@code unknown});
     * public because inlined advice code may reference the field directly.
     */
    public static final ConcurrentMap<String, String> CALLER_BSN = new ConcurrentHashMap<>();

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This(optional = true) Object instance,
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        String rule = AppSpanConfig.prefixFor(typeName);
        if (rule == null) {
            return;
        }

        String runtimeSimple = runtimeSimpleName(instance, typeName);
        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        span = tracer.spanBuilder(rule + " " + runtimeSimple + "." + methodName)
                .setAttribute("app.span.rule", rule)
                .setAttribute("code.function", typeName + "." + methodName)
                .setAttribute("code.namespace", packageName(typeName))
                .setAttribute("osgi.provider.bundle", providerBundle(typeName, instance))
                .setAttribute("osgi.caller.bundle", callerBundle(typeName, instance))
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

    public static String runtimeSimpleName(Object instance, String typeName) {
        if (instance != null) {
            return instance.getClass().getSimpleName();
        }
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    public static String packageName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot > 0 ? typeName.substring(0, dot) : "";
    }

    public static String providerBundle(String typeName, Object instance) {
        String cached = PROVIDER_BSN.get(typeName);
        if (cached != null) {
            return cached;
        }
        String bsn = "unknown";
        Class<?> declaring = loadClass(typeName, instance);
        if (declaring != null) {
            bsn = bundleOf(declaring.getClassLoader());
        }
        PROVIDER_BSN.putIfAbsent(typeName, bsn);
        return bsn;
    }

    /**
     * Resolves the bundle of the direct caller: the stack frame right above
     * the instrumented method. Best-effort by design - the caller class is
     * only resolvable when it is visible in the instrumented class's
     * classloader; anything else (including reflective calls) yields
     * {@code unknown}. Results are cached per caller class.
     */
    public static String callerBundle(String typeName, Object instance) {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (int i = 0; i < frames.length; i++) {
            if (!typeName.equals(frames[i].getClassName())) {
                continue;
            }
            if (i + 1 >= frames.length) {
                return "unknown";
            }
            String callerClass = frames[i + 1].getClassName();
            String cached = CALLER_BSN.get(callerClass);
            if (cached != null) {
                return cached;
            }
            String bsn = "unknown";
            Class<?> caller = loadClass(callerClass, instance);
            if (caller != null) {
                bsn = bundleOf(caller.getClassLoader());
            }
            CALLER_BSN.putIfAbsent(callerClass, bsn);
            return bsn;
        }
        return "unknown";
    }

    public static Class<?> loadClass(String name, Object instance) {
        try {
            ClassLoader loader = instance != null
                    ? instance.getClass().getClassLoader()
                    : Thread.currentThread().getContextClassLoader();
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolves the OSGi bundle symbolic name of a classloader, if the
     * classloader is an OSGi bundle wiring. Reflected on purpose: the advice
     * is inlined into arbitrary host classes, whose classloader may not see
     * the OSGi APIs (non-OSGi hosts, bootstrap loaders, and OSGi bundles
     * that do not import {@code org.osgi.framework.wiring}). The
     * {@code getBundle()}/{@code getSymbolicName()} methods are looked up
     * by name only, so no OSGi class has to be resolvable in the host
     * classloader.
     */
    public static String bundleOf(ClassLoader classLoader) {
        if (classLoader == null) {
            return "unknown";
        }
        try {
            Object bundle = invokeNoArg(classLoader, "getBundle");
            if (bundle == null) {
                return "unknown";
            }
            Object symbolicName = invokeNoArg(bundle, "getSymbolicName");
            return symbolicName instanceof String ? (String) symbolicName : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Invokes the public zero-argument method with the given name on the
     * receiver (public methods of superclasses and interfaces included), or
     * returns {@code null} when no such method exists or cannot be
     * accessed. Exceptions thrown by the invoked method propagate.
     */
    public static Object invokeNoArg(Object receiver, String methodName)
            throws InvocationTargetException {
        for (Method m : receiver.getClass().getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                try {
                    return m.invoke(receiver);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
