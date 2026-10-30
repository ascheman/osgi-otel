/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.testbundle.controller;

import net.aschemann.otel.osgi.testbundle.api.AppServiceBase;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Controller fixture for the app-span IT. The regex rule
 * {@code ctrl:^...testbundle\.controller\..*} matches this class;
 * {@link #handle(String)} looks up and calls the {@link AppServiceBase}
 * service, so the service span nests under the controller span.
 */
public class FakeController {

    private final BundleContext context;

    public FakeController(BundleContext context) {
        this.context = context;
    }

    public String handle(String command) {
        ServiceReference<AppServiceBase> reference =
                context.getServiceReference(AppServiceBase.class);
        if (reference == null) {
            return "no service";
        }
        AppServiceBase service = context.getService(reference);
        return service == null ? "null service" : service.doWork(command);
    }

    /** Excluded by the IT's {@code otel.app.span.exclude-methods}; must not produce a span. */
    public String getVersion() {
        return "v1";
    }
}
