/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.testbundle.api;

/**
 * Base class for the app-span IT: the base-class rule
 * {@code service:...api.AppServiceBase} matches this class and all of its
 * subtypes.
 */
public abstract class AppServiceBase {

    public abstract String doWork(String input);
}
