/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.testbundle;

import net.aschemann.otel.osgi.testbundle.api.AppServiceBase;
import net.aschemann.otel.osgi.testbundle.api.HelloService;
import net.aschemann.otel.osgi.testbundle.controller.FakeController;
import net.aschemann.otel.osgi.testbundle.internal.AppServiceImpl;
import net.aschemann.otel.osgi.testbundle.internal.HelloServiceImpl;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    private ServiceRegistration<HelloService> helloServiceRegistration;
    private ServiceRegistration<AppServiceBase> appServiceRegistration;
    private ServiceRegistration<FakeController> controllerRegistration;

    @Override
    public void start(BundleContext context) {
        helloServiceRegistration = context.registerService(HelloService.class, new HelloServiceImpl(), null);
        appServiceRegistration = context.registerService(AppServiceBase.class, new AppServiceImpl(), null);
        controllerRegistration = context.registerService(FakeController.class, new FakeController(context), null);
    }

    @Override
    public void stop(BundleContext context) {
        if (helloServiceRegistration != null) {
            helloServiceRegistration.unregister();
            helloServiceRegistration = null;
        }
        if (appServiceRegistration != null) {
            appServiceRegistration.unregister();
            appServiceRegistration = null;
        }
        if (controllerRegistration != null) {
            controllerRegistration.unregister();
            controllerRegistration = null;
        }
    }
}
