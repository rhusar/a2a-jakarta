package org.wildfly.a2a.jakarta.common;

public interface A2AVersionProvider {

    String getVersion();

    /**
     * Returns {@code true} if this is the default version for its transport.
     * <p>
     * Each {@link A2AVersionResolver} is scoped to a single transport (JSON-RPC or REST),
     * so a JSON-RPC provider and a REST provider may both return {@code true} without
     * conflicting — they are registered in separate resolvers.
     */
    boolean isDefaultVersion();

    String getInternalPathPrefix();

    /**
     * The client-facing REST base path for this version (e.g. {@code "/"} or {@code "/v1"}).
     * Return {@code null} for JSON-RPC-only providers.
     */
    String getRestBasePath();
}
