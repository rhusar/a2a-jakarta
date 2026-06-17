package org.wildfly.a2a.jakarta.common;

import java.util.LinkedHashMap;
import java.util.Map;

public class A2AVersionResolver {

    private final Map<String, A2AVersionProvider> providersByVersion = new LinkedHashMap<>();
    private A2AVersionProvider defaultProvider;

    public A2AVersionResolver(Iterable<A2AVersionProvider> providers) {
        for (A2AVersionProvider provider : providers) {
            providersByVersion.put(provider.getVersion(), provider);
            if (provider.isDefaultVersion()) {
                if (defaultProvider != null) {
                    throw new IllegalStateException("Only one default version provider should be defined. We have at least 2: %s and %s".formatted(defaultProvider, provider));
                }
                defaultProvider = provider;
            }
        }
        if (defaultProvider == null && providersByVersion.size() == 1) {
            defaultProvider = providersByVersion.values().iterator().next();
        }
    }

    public A2AVersionProvider resolve(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return providersByVersion.get(headerValue.trim());
        }
        return defaultProvider;
    }

    public boolean hasProviders() {
        return !providersByVersion.isEmpty();
    }

    public Iterable<A2AVersionProvider> allProviders() {
        return providersByVersion.values();
    }

    public String supportedVersionsString() {
        return String.join(", ", providersByVersion.keySet());
    }
}
