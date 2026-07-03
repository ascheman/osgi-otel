/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.testbundle;

import net.aschemann.otel.osgi.testbundle.api.HelloService;
import net.aschemann.otel.osgi.testbundle.internal.HelloServiceImpl;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    private ServiceRegistration<HelloService> registration;

    @Override
    public void start(BundleContext context) {
        registration = context.registerService(HelloService.class, new HelloServiceImpl(), null);
    }

    @Override
    public void stop(BundleContext context) {
        if (registration != null) {
            registration.unregister();
            registration = null;
        }
    }
}
