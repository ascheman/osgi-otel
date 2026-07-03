/*
 * Copyright 2026 Gerd Aschemann
 * Licensed under the Apache License, Version 2.0
 */
package net.aschemann.otel.osgi.testbundle.internal;

import net.aschemann.otel.osgi.testbundle.api.HelloService;

public class HelloServiceImpl implements HelloService {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
