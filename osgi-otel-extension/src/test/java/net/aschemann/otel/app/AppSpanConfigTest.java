/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the rule parsing and matching semantics through the
 * package-private seams that take their input explicitly. The public
 * entry points read system properties in a static initializer, which cannot
 * be re-run per test.
 */
class AppSpanConfigTest {

    static class Base {
    }

    static class Sub extends Base {
    }

    @Test
    void noRulesWhenUnsetOrBlank() {
        assertThat(AppSpanConfig.parseRules(null)).isEmpty();
        assertThat(AppSpanConfig.parseRules("")).isEmpty();
        assertThat(AppSpanConfig.parseRules("   ")).isEmpty();
    }

    @Test
    void malformedEntriesAreSkipped() {
        // no colon, empty prefix, empty matcher, trailing colon, stray commas
        assertThat(AppSpanConfig.parseRules("nocolon")).isEmpty();
        assertThat(AppSpanConfig.parseRules(":com.example.Foo")).isEmpty();
        assertThat(AppSpanConfig.parseRules("prefix:")).isEmpty();
        assertThat(AppSpanConfig.parseRules(",,")).isEmpty();
    }

    @Test
    void malformedEntriesDoNotDiscardTheValidOnes() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules("nocolon,service:com.example.Foo,prefix:");

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getPrefix()).isEqualTo("service");
    }

    @Test
    void entriesAndSurroundingWhitespaceAreTrimmed() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules(" service : com.example.Foo ");

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getPrefix()).isEqualTo("service");
        assertThat(rules.get(0).getMatcher()).isEqualTo("com.example.Foo");
    }

    @Test
    void onlyTheFirstColonSeparatesPrefixFromMatcher() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules("service:com.example.Foo:Bar");

        assertThat(rules.get(0).getPrefix()).isEqualTo("service");
        assertThat(rules.get(0).getMatcher()).isEqualTo("com.example.Foo:Bar");
    }

    @Test
    void theFirstMatchingRuleWins() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules(
                "first:^com\\.example\\..*,second:^com\\.example\\.Foo$");

        assertThat(AppSpanConfig.resolvePrefix("com.example.Foo", rules)).isEqualTo("first");
    }

    @Test
    void anUnmatchedTypeResolvesToNoRule() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules("service:com.example.Foo");

        assertThat(AppSpanConfig.resolvePrefix("com.other.Bar", rules)).isEmpty();
    }

    @Test
    void anExactClassRegexWithEscapedDotsResolves() {
        // Regression: this used to be classified as a base class named
        // "com\.example\.Foo", which no class can ever be called, so the rule
        // was silently dead.
        List<AppSpanRule> rules = AppSpanConfig.parseRules("escaped:com\\.example\\.Foo");

        assertThat(rules.get(0).isRegex()).isTrue();
        assertThat(AppSpanConfig.resolvePrefix("com.example.Foo", rules)).isEqualTo("escaped");
    }

    @Test
    void aBaseClassRuleResolvesForSubtypes() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules("service:" + Base.class.getName());

        assertThat(AppSpanConfig.resolvePrefix(Base.class.getName(), rules)).isEqualTo("service");
        assertThat(AppSpanConfig.resolvePrefix(Sub.class.getName(), rules)).isEqualTo("service");
    }

    @Test
    void aBaseClassRuleResolvesByNameEvenWhenTheClassIsNotLoadable() {
        List<AppSpanRule> rules = AppSpanConfig.parseRules("service:com.example.NotOnTheClasspath");

        assertThat(AppSpanConfig.resolvePrefix("com.example.NotOnTheClasspath", rules))
                .isEqualTo("service");
    }

    @Test
    void theDefaultExcludePatternCoversTheObjectMethods() {
        assertThat(AppSpanConfig.excludeMethods().matcher("toString").matches()).isTrue();
        assertThat(AppSpanConfig.excludeMethods().matcher("hashCode").matches()).isTrue();
        assertThat(AppSpanConfig.excludeMethods().matcher("equals").matches()).isTrue();
        assertThat(AppSpanConfig.excludeMethods().matcher("getClass").matches()).isTrue();
        assertThat(AppSpanConfig.excludeMethods().matcher("doWork").matches()).isFalse();
    }

    @Test
    void anInvalidExcludePatternFallsBackToTheDefault() {
        assertThat(AppSpanConfig.compileExclude("[").pattern())
                .isEqualTo("^(toString|hashCode|equals|getClass)$");
    }

    @Test
    void theCallerBundleAttributeIsOnUnlessExplicitlyDisabled() {
        assertThat(AppSpanConfig.parseCallerBundle(null)).isTrue();
        assertThat(AppSpanConfig.parseCallerBundle("true")).isTrue();
        assertThat(AppSpanConfig.parseCallerBundle("anything")).isTrue();
        assertThat(AppSpanConfig.parseCallerBundle("false")).isFalse();
        assertThat(AppSpanConfig.parseCallerBundle(" FALSE ")).isFalse();
    }
}
