/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.testbundle.internal;

import net.aschemann.otel.osgi.testbundle.api.AppServiceBase;

/**
 * Implementation of {@link AppServiceBase} used by the app-span IT; the span
 * must carry the *runtime* simple name {@code AppServiceImpl}.
 */
public class AppServiceImpl extends AppServiceBase {

    @Override
    public String doWork(String input) {
        return "worked:" + input;
    }
}
