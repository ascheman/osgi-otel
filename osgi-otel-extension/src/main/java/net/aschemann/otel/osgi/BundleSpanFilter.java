/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether a span should be emitted based on caller/provider bundle
 * symbolic names. Configuration via system properties (set as -D... or via
 * environment variables OTEL-style):
 *
 *   osgi.filter.cross-bundle-only   (boolean, default false)
 *     If true, drop spans where caller and provider are the same bundle.
 *
 *   osgi.filter.bundle.include      (regex, default unset)
 *     If set, only emit spans where caller OR provider matches this regex.
 *
 *   osgi.filter.bundle.exclude      (regex, default unset)
 *     If set, drop spans where caller AND provider both match this regex.
 *     (Half-matches survive: e.g. excluding ^org\.eclipse\.osgi$ keeps
 *     calls from osgi to user bundles, but drops osgi-to-osgi noise.)
 *
 * Configuration is read once at class load time. Restart required to apply
 * changes.
 */
public final class BundleSpanFilter {

    private static final boolean CROSS_BUNDLE_ONLY =
            Boolean.parseBoolean(System.getProperty("osgi.filter.cross-bundle-only", "false"));
    private static final Pattern INCLUDE = compileOrNull(System.getProperty("osgi.filter.bundle.include"));
    private static final Pattern EXCLUDE = compileOrNull(System.getProperty("osgi.filter.bundle.exclude"));

    private BundleSpanFilter() {
    }

    public static boolean shouldEmit(String callerBundle, String providerBundle) {
        if (CROSS_BUNDLE_ONLY && Objects.equals(callerBundle, providerBundle)) {
            return false;
        }
        if (EXCLUDE != null
                && EXCLUDE.matcher(callerBundle).matches()
                && EXCLUDE.matcher(providerBundle).matches()) {
            return false;
        }
        if (INCLUDE != null
                && !INCLUDE.matcher(callerBundle).matches()
                && !INCLUDE.matcher(providerBundle).matches()) {
            return false;
        }
        return true;
    }

    private static Pattern compileOrNull(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            System.err.println("[osgi-otel] invalid regex '" + regex + "': " + e.getMessage());
            return null;
        }
    }
}
