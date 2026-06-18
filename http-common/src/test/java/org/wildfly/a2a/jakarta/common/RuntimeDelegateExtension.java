package org.wildfly.a2a.jakarta.common;

import jakarta.ws.rs.ext.RuntimeDelegate;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeDelegateExtension implements BeforeAllCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        RuntimeDelegate rd = mock(RuntimeDelegate.class);
        when(rd.createResponseBuilder()).thenAnswer(inv -> new SimpleResponseBuilder());
        RuntimeDelegate.setInstance(rd);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        RuntimeDelegate.setInstance(null);
    }
}
