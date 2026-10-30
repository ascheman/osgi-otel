/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppSpanRuleTest {

    interface Marker {
    }

    static class Base implements Marker {
    }

    static class Sub extends Base {
    }

    static class Unrelated {
    }

    @Test
    void plainClassNamesAreNotRegexMatchers() {
        assertThat(AppSpanRule.isRegexMatcher("com.example.Foo")).isFalse();
        // '$' separates inner classes and must stay usable in a base-class FQCN
        assertThat(AppSpanRule.isRegexMatcher("com.example.Outer$Inner")).isFalse();
    }

    @Test
    void escapedDotsMakeARegexMatcher() {
        // Regression: a backslash cannot occur in a class name, so an
        // exact-class regex written with escaped dots used to be taken for a
        // base class of that literal name and silently matched nothing.
        assertThat(AppSpanRule.isRegexMatcher("com\\.example\\.Foo")).isTrue();
    }

    @Test
    void everyMetacharacterMakesARegexMatcher() {
        for (String metacharacter : new String[]{"*", "+", "?", "(", ")", "[", "]", "{", "}", "|", "^", "\\"}) {
            assertThat(AppSpanRule.isRegexMatcher("com.example.Foo" + metacharacter))
                    .as("matcher containing '%s'", metacharacter)
                    .isTrue();
        }
    }

    @Test
    void baseClassRuleMatchesTheClassItselfAndItsSubtypes() {
        AppSpanRule rule = AppSpanRule.parse("service", Base.class.getName());

        assertThat(rule.isRegex()).isFalse();
        assertThat(rule.matchesClass(Base.class)).isTrue();
        assertThat(rule.matchesClass(Sub.class)).isTrue();
        assertThat(rule.matchesClass(Unrelated.class)).isFalse();
    }

    @Test
    void baseClassRuleMatchesThroughInterfaces() {
        AppSpanRule rule = AppSpanRule.parse("service", Marker.class.getName());

        assertThat(rule.matchesClass(Sub.class)).isTrue();
        assertThat(rule.matchesClass(Unrelated.class)).isFalse();
    }

    @Test
    void regexRuleIsSearchedInTheClassName() {
        AppSpanRule rule = AppSpanRule.parse("ctrl", "^com\\.example\\.controller\\..*");

        assertThat(rule.isRegex()).isTrue();
        assertThat(rule.matchesName("com.example.controller.FooController")).isTrue();
        assertThat(rule.matchesName("com.example.service.FooService")).isFalse();
    }

    @Test
    void regexRuleUsesFindSoItNeedsNoAnchors() {
        AppSpanRule rule = AppSpanRule.parse("ctrl", "controller\\..*");

        assertThat(rule.matchesName("com.example.controller.FooController")).isTrue();
    }

    @Test
    void aBaseClassRuleNeverMatchesByName() {
        AppSpanRule rule = AppSpanRule.parse("service", Base.class.getName());

        // matchesName() is the advice-side regex path only; a base-class rule
        // has no pattern and must not report a match through it.
        assertThat(rule.matchesName(Base.class.getName())).isFalse();
    }

    @Test
    void anInvalidRegexIsRejected() {
        assertThat(AppSpanRule.parse("broken", "com.example.[")).isNull();
    }
}
