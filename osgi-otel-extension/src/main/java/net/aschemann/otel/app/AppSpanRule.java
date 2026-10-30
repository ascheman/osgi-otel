/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import net.bytebuddy.description.type.TypeDescription;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One span rule: a span-name {@code prefix} plus a class matcher. The
 * matcher is either a base class FQCN (the rule matches that class and all of
 * its subtypes) or a Java regex searched ({@code find()}) in the fully
 * qualified class name.
 */
final class AppSpanRule {

    /**
     * Metacharacters that make a matcher a regex. {@code .} and {@code $} are
     * deliberately not counted: they are legal characters in a fully
     * qualified class name. The backslash is counted, because it cannot occur
     * in a class name at all - without it, an exact-class regex written with
     * escaped dots ({@code com\.example\.Foo}) would be taken for a base
     * class of that literal name and could never match anything.
     */
    private static final String REGEX_METACHARACTERS = "*+?()[]{}|^\\";

    private final String prefix;
    private final String matcher;
    private final Pattern pattern;

    private AppSpanRule(String prefix, String matcher, Pattern pattern) {
        this.prefix = prefix;
        this.matcher = matcher;
        this.pattern = pattern;
    }

    static AppSpanRule parse(String prefix, String matcher) {
        if (isRegexMatcher(matcher)) {
            try {
                return new AppSpanRule(prefix, matcher, Pattern.compile(matcher));
            } catch (PatternSyntaxException e) {
                System.err.println("[osgi-otel] app-span: invalid regex matcher '" + matcher
                        + "' in rule '" + prefix + "': " + e.getMessage());
                return null;
            }
        }
        return new AppSpanRule(prefix, matcher, null);
    }

    static boolean isRegexMatcher(String matcher) {
        for (int i = 0; i < matcher.length(); i++) {
            if (REGEX_METACHARACTERS.indexOf(matcher.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    String getPrefix() {
        return prefix;
    }

    String getMatcher() {
        return matcher;
    }

    boolean isRegex() {
        return pattern != null;
    }

    boolean matches(TypeDescription type) {
        if (pattern != null) {
            return pattern.matcher(type.getName()).find();
        }
        // Base class rule: matches the class itself and all subtypes.
        return isTypeOrSubtype(type, matcher);
    }

    /**
     * Regex-rule match against a fully qualified type name (used by the
     * inlined advice, which only sees the type name).
     */
    boolean matchesName(String typeName) {
        return pattern != null && pattern.matcher(typeName).find();
    }

    /**
     * Base-class-rule match for a loaded class: the class itself and all of
     * its subtypes (used by the inlined advice, which cannot see a
     * {@link TypeDescription}).
     */
    boolean matchesClass(Class<?> clazz) {
        return isTypeOrSubtype(clazz, matcher);
    }

    private static boolean isTypeOrSubtype(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            if (current.getName().equals(name)) {
                return true;
            }
            for (Class<?> itf : current.getInterfaces()) {
                if (isTypeOrSubtype(itf, name)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    /**
     * Walks the superclasses and (recursively) interfaces of {@code type}
     * looking for a type whose fully qualified name equals {@code name}.
     */
    private static boolean isTypeOrSubtype(TypeDescription type, String name) {
        TypeDescription current = type;
        while (current != null) {
            if (current.getName().equals(name)) {
                return true;
            }
            for (TypeDescription.Generic itf : current.getInterfaces()) {
                if (isTypeOrSubtype(itf.asErasure(), name)) {
                    return true;
                }
            }
            TypeDescription.Generic superClass = current.getSuperClass();
            current = superClass == null ? null : superClass.asErasure();
        }
        return false;
    }
}
