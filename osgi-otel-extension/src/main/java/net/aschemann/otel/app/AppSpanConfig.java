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
 * {@code * + ? ( ) [ ] { } | ^ \} is treated as a base class: the rule
 * matches that class and all of its subtypes.
 * (Dots and {@code $} are legal in fully qualified class names and are not
 * counted as metacharacters.) Any other matcher is compiled as a Java regex
 * and searched ({@code find()}) in the fully qualified class name.
 * The first matching rule wins.
 *
 * <p>Because the entries are comma-separated, a matcher cannot contain a
 * comma - a regex quantifier such as <code>{2,3}</code> is not expressible.
 *
 * <p>{@code otel.app.span.exclude-methods} (Java regex, full match, default
 * {@code ^(toString|hashCode|equals|getClass)$}) names methods that are not
 * instrumented at all.
 *
 * <p>{@code otel.app.span.caller-bundle} ({@code true} by default) controls
 * whether the {@code osgi.caller.bundle} attribute - and the per-call stack
 * walk it needs - is resolved at all.
 *
 * <p>The module is enabled only when at least one rule is configured
 * (see {@link #hasRules()}).
 *
 * <p>Configuration is read once at class load time from <em>system
 * properties only</em>: the advice-side copy of this class cannot reach the
 * agent's {@code ConfigProperties}, so the usual {@code OTEL_*} environment
 * variables and config files do not configure these settings. Restart
 * required to apply changes.
 */
public final class AppSpanConfig {

    private static final String RULES_PROPERTY = "otel.app.span.rules";
    private static final String EXCLUDE_METHODS_PROPERTY = "otel.app.span.exclude-methods";
    private static final String CALLER_BUNDLE_PROPERTY = "otel.app.span.caller-bundle";
    private static final String DEFAULT_EXCLUDE_METHODS = "^(toString|hashCode|equals|getClass)$";

    private static final List<AppSpanRule> RULES = parseRules(System.getProperty(RULES_PROPERTY));
    private static final Pattern EXCLUDE_METHODS =
            compileExclude(System.getProperty(EXCLUDE_METHODS_PROPERTY, DEFAULT_EXCLUDE_METHODS));
    private static final boolean CALLER_BUNDLE =
            parseCallerBundle(System.getProperty(CALLER_BUNDLE_PROPERTY));

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
     * Whether the {@code osgi.caller.bundle} attribute is resolved at all.
     * Called from the inlined advice - hence public.
     *
     * <p>Resolving the caller costs a full {@link Thread#getStackTrace()}
     * snapshot on every instrumented call, before any cache can be
     * consulted, so a broad rule puts it on the hot path. Set
     * {@code otel.app.span.caller-bundle=false} to drop the attribute and
     * the stack walk with it.
     */
    public static boolean callerBundleEnabled() {
        return CALLER_BUNDLE;
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
        String prefix = TYPE_TO_PREFIX.computeIfAbsent(typeName, name -> resolvePrefix(name, RULES));
        return NO_RULE.equals(prefix) ? null : prefix;
    }

    /**
     * Re-evaluates the given rules (first match wins) against a declaring
     * type name: regex rules match the name, base-class rules match the
     * class itself and all of its subtypes (through the loaded declaring
     * class, when it is resolvable).
     *
     * <p>Takes the rules as a parameter instead of reading {@link #RULES},
     * so that the matching semantics can be tested without depending on the
     * class-load-time system properties.
     *
     * @return the matching rule's prefix, or {@link #NO_RULE} when none matches
     */
    static String resolvePrefix(String typeName, List<AppSpanRule> rules) {
        for (AppSpanRule rule : rules) {
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

    static List<AppSpanRule> parseRules(String raw) {
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

    static Pattern compileExclude(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            System.err.println("[osgi-otel] app-span: invalid exclude-methods regex '" + regex
                    + "': " + e.getMessage() + " - falling back to the default");
            return Pattern.compile(DEFAULT_EXCLUDE_METHODS);
        }
    }

    /** Defaults to enabled; only an explicit {@code false} switches it off. */
    static boolean parseCallerBundle(String raw) {
        return raw == null || !"false".equalsIgnoreCase(raw.trim());
    }
}
