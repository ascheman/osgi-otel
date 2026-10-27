/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuration for the {@code app-span} instrumentation module.
 *
 * <p>Span rules are configured with the {@code otel.app.span.rules} system
 * property as a comma-separated list of {@code prefix:matcher} entries.
 * A matcher containing none of the regex metacharacters
 * {@code * + ? ( ) [ ] { } | ^} is treated as a base class: the rule matches
 * that class and all of its subtypes.
 * (Dots and {@code $} are legal in fully qualified class names and are not
 * counted as metacharacters.) Any other matcher is compiled as a Java regex
 * and searched ({@code find()}) in the fully qualified class name.
 * The first matching rule wins.
 *
 * <p>{@code otel.app.span.exclude-methods} (Java regex, full match, default
 * {@code ^(toString|hashCode|equals|getClass)$}) names methods that are not
 * instrumented at all.
 *
 * <p>The module is enabled only when at least one rule is configured
 * (see {@link #hasRules()}).
 *
 * <p>Configuration is read once at class load time. Restart required to apply
 * changes.
 */
public final class AppSpanConfig {

    private static final String RULES_PROPERTY = "otel.app.span.rules";
    private static final String EXCLUDE_METHODS_PROPERTY = "otel.app.span.exclude-methods";
    private static final String DEFAULT_EXCLUDE_METHODS = "^(toString|hashCode|equals|getClass)$";

    private static final List<AppSpanRule> RULES = parseRules(System.getProperty(RULES_PROPERTY));
    private static final Pattern EXCLUDE_METHODS =
            compileExclude(System.getProperty(EXCLUDE_METHODS_PROPERTY, DEFAULT_EXCLUDE_METHODS));

    /**
     * Declaring type name to span-rule prefix, resolved on first use and
     * cached. The resolution must be self-contained: the agent injects this
     * class into the classloader of the instrumented library, and the
     * inlined advice sees that copy, which is a different class instance
     * from the one the type matcher (agent side) uses. Both copies parse the
     * same {@code otel.app.span.rules} property, so they agree.
     */
    private static final ConcurrentMap<String, String> TYPE_TO_PREFIX = new ConcurrentHashMap<>();

    /** Sentinel so that a negative resolution result can be cached, too. */
    private static final String NO_RULE = "";

    private AppSpanConfig() {
    }

    /**
     * @return true when at least one span rule is configured; the module's
     * default-enabled state is derived from this.
     */
    public static boolean hasRules() {
        return !RULES.isEmpty();
    }

    public static List<AppSpanRule> rules() {
        return RULES;
    }

    public static boolean hasRegexRules() {
        for (AppSpanRule rule : RULES) {
            if (rule.isRegex()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the base-class FQCNs of all non-regex rules (used to narrow the
     * classloader optimization).
     */
    public static List<String> baseClasses() {
        List<String> baseClasses = new ArrayList<>();
        for (AppSpanRule rule : RULES) {
            if (!rule.isRegex()) {
                baseClasses.add(rule.getMatcher());
            }
        }
        return baseClasses;
    }

    public static Pattern excludeMethods() {
        return EXCLUDE_METHODS;
    }

    /**
     * Resolves the span-rule prefix for a declaring type; called from the
     * inlined advice, which runs in the class of the instrumented method -
     * hence public, because the access check applies across packages.
     *
     * @return the span-rule prefix that applies to the declaring type with
     * the given fully qualified name, or {@code null} when no rule matches
     */
    public static String prefixFor(String typeName) {
        String prefix = TYPE_TO_PREFIX.computeIfAbsent(typeName, AppSpanConfig::resolvePrefix);
        return NO_RULE.equals(prefix) ? null : prefix;
    }

    /**
     * Re-evaluates the configured rules (first match wins) against a
     * declaring type name: regex rules match the name, base-class rules
     * match the class itself and all of its subtypes (through the loaded
     * declaring class, when it is resolvable).
     */
    private static String resolvePrefix(String typeName) {
        for (AppSpanRule rule : RULES) {
            if (rule.isRegex()) {
                if (rule.matchesName(typeName)) {
                    return rule.getPrefix();
                }
            } else {
                Class<?> declaring = declaringClass(typeName);
                if (typeName.equals(rule.getMatcher())
                        || (declaring != null && rule.matchesClass(declaring))) {
                    return rule.getPrefix();
                }
            }
        }
        return NO_RULE;
    }

    private static Class<?> declaringClass(String typeName) {
        try {
            return Class.forName(typeName, false, AppSpanConfig.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<AppSpanRule> parseRules(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<AppSpanRule> rules = new ArrayList<>();
        for (String part : raw.split(",")) {
            String rule = part.trim();
            if (rule.isEmpty()) {
                continue;
            }
            int separator = rule.indexOf(':');
            String prefix = separator > 0 ? rule.substring(0, separator).trim() : "";
            String matcher = (separator > 0 && separator < rule.length() - 1)
                    ? rule.substring(separator + 1).trim()
                    : "";
            if (prefix.isEmpty() || matcher.isEmpty()) {
                System.err.println("[osgi-otel] app-span: ignoring malformed rule '" + rule
                        + "' (expected format prefix:matcher)");
                continue;
            }
            AppSpanRule parsed = AppSpanRule.parse(prefix, matcher);
            if (parsed != null) {
                rules.add(parsed);
            }
        }
        return Collections.unmodifiableList(rules);
    }

    private static Pattern compileExclude(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            System.err.println("[osgi-otel] app-span: invalid exclude-methods regex '" + regex
                    + "': " + e.getMessage() + " - falling back to the default");
            return Pattern.compile(DEFAULT_EXCLUDE_METHODS);
        }
    }
}
