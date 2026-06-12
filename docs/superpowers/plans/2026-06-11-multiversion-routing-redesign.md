# Multi-Version Routing Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace duplicated multiversion routing with filter-based CDI-discovered version routing, eliminating 6 modules.

**Architecture:** Two `@PreMatching` JAX-RS filters in `http-common` (renamed from `common`) discover available protocol versions via CDI and rewrite request URIs to transport+version-prefixed internal paths (e.g., `/a2a_rest_v1.0/message:send`). Each version module provides `A2AVersionProvider` beans and registers its resources under the internal prefix. The multiversion modules are deleted entirely.

**Tech Stack:** Jakarta REST (JAX-RS), CDI, Maven multi-module

**Spec:** `docs/superpowers/specs/2026-06-11-multiversion-routing-redesign.md`

---

## Key design decisions

**One `A2AVersionProvider` per transport per version.** Each filter builds its own `A2AVersionResolver` from the providers it discovers. The JSON-RPC filter only uses providers whose `getInternalPathPrefix()` starts with `/a2a_jsonrpc_`. The REST filter only uses providers whose `getRestBasePath()` is non-null. This avoids a JSON-RPC filter accidentally using a REST prefix or vice versa.

**`A2A_VERSION` header name.** Use `A2AHeaders.A2A_VERSION` from the SDK (`org.a2aproject.sdk.common.A2AHeaders`) rather than hardcoding the string. The `common` module already depends on `a2a-java-sdk-server-common` which provides this constant.

**REST filter prepends, never strips.** The filter prepends the resolved version's internal prefix to the full incoming path — it does NOT strip the existing base path. This means v0.3 REST resources keep `/v1` in their `@Path` (e.g., `@Path("/a2a_rest_v0.3/v1")`). A request to `/v1/message:send` becomes `/a2a_rest_v0.3/v1/message:send`. This avoids complex base-path-stripping logic and makes cross-version misuse (e.g., `/v1/...` with `A2A_VERSION: 1.0`) correctly 404.

**REST filter activation for root base path.** When v1.0 (base path `"/"`) is deployed, the filter activates for all non-JSON-RPC, non-agent-card requests — even without an `A2A_VERSION` header. In a single-version deployment, the filter's default version resolution handles this correctly.

**Multiversion REST test transport URL.** Currently `http://localhost:8080/v1` — changes to `http://localhost:8080`. The v1.0 SDK client appends endpoint paths to the transport URL directly. The v0.3 SDK client internally adds `/v1`. So both versions can share a root transport URL.

---

### Task 1: Add interfaces and version resolution to `common` module

**Files:**
- Create: `common/src/main/java/org/wildfly/a2a/jakarta/common/A2AVersionProvider.java`
- Create: `common/src/main/java/org/wildfly/a2a/jakarta/common/A2AJsonRpcMethodProvider.java`
- Create: `common/src/main/java/org/wildfly/a2a/jakarta/common/A2AVersionResolver.java`
- Modify: `common/pom.xml` (add Jakarta API dependencies)

This task is additive — nothing breaks, no existing behavior changes.

- [ ] **Step 1: Add dependencies to `common/pom.xml`**

Add to the `<dependencies>` section:

```xml
<dependency>
    <groupId>jakarta.ws.rs</groupId>
    <artifactId>jakarta.ws.rs-api</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>jakarta.inject</groupId>
    <artifactId>jakarta.inject-api</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>jakarta.enterprise</groupId>
    <artifactId>jakarta.enterprise.cdi-api</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <scope>provided</scope>
</dependency>
```

Note: `a2a-java-sdk-transport-rest` is NOT needed here. `AgentCardResource` (Task 4) injects `AgentCard` directly via CDI, not through `RestHandler`.

- [ ] **Step 2: Create `A2AVersionProvider` interface**

```java
package org.wildfly.a2a.jakarta.common;

public interface A2AVersionProvider {

    String getVersion();

    boolean isDefaultVersion();

    String getInternalPathPrefix();

    /**
     * The client-facing REST base path for this version.
     * "/" for v1.0, "/v1" for v0.3.
     * Return null for JSON-RPC-only providers.
     */
    String getRestBasePath();
}
```

- [ ] **Step 3: Create `A2AJsonRpcMethodProvider` interface**

```java
package org.wildfly.a2a.jakarta.common;

import java.util.Set;

public interface A2AJsonRpcMethodProvider {

    Set<String> getStreamingMethodNames();

    Set<String> getNonStreamingMethodNames();
}
```

- [ ] **Step 4: Create `A2AVersionResolver` utility**

This encapsulates the version resolution logic used by both filters. Each filter creates its own instance with a filtered subset of providers.

```java
package org.wildfly.a2a.jakarta.common;

import java.util.HashMap;
import java.util.Map;

public class A2AVersionResolver {

    private final Map<String, A2AVersionProvider> providersByVersion = new HashMap<>();
    private A2AVersionProvider defaultProvider;

    public A2AVersionResolver(Iterable<A2AVersionProvider> providers) {
        for (A2AVersionProvider provider : providers) {
            providersByVersion.put(provider.getVersion(), provider);
            if (provider.isDefaultVersion()) {
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
        return providersByVersion.keySet().toString();
    }
}
```

- [ ] **Step 5: Verify build**

Run: `mvn -pl common compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add common/
git commit -m "Add version provider interfaces and resolver to common module"
```

---

### Task 2: Add JSON-RPC Accept filter to `common`

**Files:**
- Create: `common/src/main/java/org/wildfly/a2a/jakarta/common/A2AJsonRpcAcceptFilter.java`

This filter reads the request body for JSON-RPC POST requests and sets the Accept header based on the method name. It also prepends the JSON-RPC version prefix to the URI. It replaces the three duplicated `A2ARequestFilter` / `A2ARequestFilter_v0_3` / `MultiVersionA2ARequestFilter` classes.

The filter only uses `A2AVersionProvider` beans whose `getInternalPathPrefix()` starts with `/a2a_jsonrpc_` — this prevents it from accidentally using a REST provider's prefix.

Note: The existing v1.0 `A2ARequestFilter` activates for paths `/`, `/agent/*`, and `/.well-known/*`. This filter only activates for `/` (JSON-RPC POST to root). The `/agent/` paths are not JSON-RPC endpoints in the current protocol, and `/.well-known/` is GET-only (agent card). If `/agent/` JSON-RPC support is added later, the activation check can be extended.

- [ ] **Step 1: Create `A2AJsonRpcAcceptFilter`**

```java
package org.wildfly.a2a.jakarta.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import org.a2aproject.sdk.common.A2AHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Provider
@PreMatching
@Priority(100)
public class A2AJsonRpcAcceptFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(A2AJsonRpcAcceptFilter.class);
    private static final String JSONRPC_PREFIX = "/a2a_jsonrpc_";

    @Inject
    Instance<A2AVersionProvider> allVersionProviders;

    @Inject
    Instance<A2AJsonRpcMethodProvider> methodProviders;

    private volatile boolean initialized;
    private Set<String> allStreamingMethods;
    private Set<String> allNonStreamingMethods;
    private A2AVersionResolver versionResolver;

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    allStreamingMethods = new HashSet<>();
                    allNonStreamingMethods = new HashSet<>();
                    for (A2AJsonRpcMethodProvider provider : methodProviders) {
                        allStreamingMethods.addAll(provider.getStreamingMethodNames());
                        allNonStreamingMethods.addAll(provider.getNonStreamingMethodNames());
                    }
                    List<A2AVersionProvider> jsonRpcProviders = new ArrayList<>();
                    for (A2AVersionProvider provider : allVersionProviders) {
                        if (provider.getInternalPathPrefix().startsWith(JSONRPC_PREFIX)) {
                            jsonRpcProviders.add(provider);
                        }
                    }
                    versionResolver = new A2AVersionResolver(jsonRpcProviders);
                    initialized = true;
                }
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isJsonRpcRequest(requestContext)) {
            return;
        }

        ensureInitialized();

        if (!versionResolver.hasProviders()) {
            return;
        }

        try (InputStream entityInputStream = requestContext.getEntityStream()) {
            byte[] requestBodyBytes = entityInputStream.readAllBytes();
            String requestBody = new String(requestBodyBytes);

            if (isStreamingRequest(requestBody)) {
                LOGGER.debug("Handling request as streaming: {}", requestBody);
                putAcceptHeader(requestContext, MediaType.SERVER_SENT_EVENTS);
            } else if (isNonStreamingRequest(requestBody)) {
                LOGGER.debug("Handling request as non-streaming: {}", requestBody);
                putAcceptHeader(requestContext, MediaType.APPLICATION_JSON);
            }

            requestContext.setEntityStream(new ByteArrayInputStream(requestBodyBytes));
        } catch (IOException e) {
            throw new RuntimeException("Unable to read the request body");
        }

        String versionHeader = requestContext.getHeaderString(A2AHeaders.A2A_VERSION);
        A2AVersionProvider provider = versionResolver.resolve(versionHeader);
        if (provider == null) {
            requestContext.abortWith(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Protocol version '"
                                    + versionHeader + "' is not supported. Supported versions: "
                                    + versionResolver.supportedVersionsString() + "\"},\"id\":null}")
                            .type(MediaType.APPLICATION_JSON)
                            .build());
            return;
        }

        URI baseUri = requestContext.getUriInfo().getBaseUri();
        URI requestUri = requestContext.getUriInfo().getRequestUri();
        String path = requestContext.getUriInfo().getPath();
        String newPath = provider.getInternalPathPrefix() + (path.startsWith("/") ? path : "/" + path);
        URI newRequestUri = UriBuilder.fromUri(requestUri).replacePath(baseUri.getPath() + newPath).build();
        requestContext.setRequestUri(baseUri, newRequestUri);
    }

    private boolean isJsonRpcRequest(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath().trim();
        return (path.equals("/") || path.isEmpty())
                && requestContext.getMethod().equals("POST")
                && requestContext.hasEntity();
    }

    private boolean isStreamingRequest(String requestBody) {
        for (String method : allStreamingMethods) {
            if (requestBody.contains(method)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNonStreamingRequest(String requestBody) {
        for (String method : allNonStreamingMethods) {
            if (requestBody.contains(method)) {
                return true;
            }
        }
        return false;
    }

    private static void putAcceptHeader(ContainerRequestContext requestContext, String mediaType) {
        requestContext.getHeaders().putSingle("Accept", mediaType);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `mvn -pl common compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add common/
git commit -m "Add JSON-RPC Accept filter with version routing to common module"
```

---

### Task 3: Add REST version routing filter to `common`

**Files:**
- Create: `common/src/main/java/org/wildfly/a2a/jakarta/common/A2ARestVersionRoutingFilter.java`

This filter handles REST transport version routing. It prepends the resolved version's internal prefix to the incoming path (no base-path stripping). It only uses `A2AVersionProvider` beans whose `getRestBasePath()` is non-null. The `getRestBasePath()` values are used solely for activation (deciding whether to intercept), not for path manipulation.

When the root base path (`"/"`) is registered (v1.0), the filter activates for all non-JSON-RPC, non-agent-card requests. This ensures v1.0 standalone deployments work without requiring clients to send the version header.

- [ ] **Step 1: Create `A2ARestVersionRoutingFilter`**

```java
package org.wildfly.a2a.jakarta.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import org.a2aproject.sdk.common.A2AHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Provider
@PreMatching
@Priority(200)
public class A2ARestVersionRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARestVersionRoutingFilter.class);
    private static final String A2A_INTERNAL_PREFIX = "/a2a_";

    @Inject
    Instance<A2AVersionProvider> allVersionProviders;

    private volatile boolean initialized;
    private A2AVersionResolver versionResolver;
    private Set<String> knownRestBasePaths;
    private boolean hasRootBasePath;

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    List<A2AVersionProvider> restProviders = new ArrayList<>();
                    knownRestBasePaths = new TreeSet<>(Comparator.comparingInt(String::length).reversed());
                    for (A2AVersionProvider provider : allVersionProviders) {
                        String basePath = provider.getRestBasePath();
                        if (basePath != null) {
                            restProviders.add(provider);
                            knownRestBasePaths.add(basePath);
                        }
                    }
                    versionResolver = new A2AVersionResolver(restProviders);
                    hasRootBasePath = knownRestBasePaths.contains("/");
                    initialized = true;
                }
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath().trim();

        if (path.startsWith("/.well-known/")) {
            return;
        }
        if (path.startsWith(A2A_INTERNAL_PREFIX)) {
            return;
        }

        ensureInitialized();

        if (!versionResolver.hasProviders()) {
            return;
        }

        String versionHeader = requestContext.getHeaderString(A2AHeaders.A2A_VERSION);

        boolean hasKnownBasePath = false;
        if (hasRootBasePath) {
            hasKnownBasePath = true;
        } else {
            for (String basePath : knownRestBasePaths) {
                if (path.startsWith(basePath + "/") || path.equals(basePath)) {
                    hasKnownBasePath = true;
                    break;
                }
            }
        }

        if (versionHeader == null && !hasKnownBasePath) {
            return;
        }

        A2AVersionProvider provider = versionResolver.resolve(versionHeader);
        if (provider == null) {
            requestContext.abortWith(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":{\"code\":-32001,\"message\":\"Protocol version '"
                                    + versionHeader + "' is not supported. Supported versions: "
                                    + versionResolver.supportedVersionsString() + "\"}}")
                            .type(MediaType.APPLICATION_JSON)
                            .build());
            return;
        }

        String newPath = provider.getInternalPathPrefix() + (path.startsWith("/") ? path : "/" + path);

        LOGGER.debug("REST version routing: {} -> {}", path, newPath);

        URI baseUri = requestContext.getUriInfo().getBaseUri();
        URI requestUri = requestContext.getUriInfo().getRequestUri();
        URI newRequestUri = UriBuilder.fromUri(requestUri).replacePath(baseUri.getPath() + newPath).build();
        requestContext.setRequestUri(baseUri, newRequestUri);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `mvn -pl common compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add common/
git commit -m "Add REST version routing filter to common module"
```

---

### Task 4: Add `AgentCardResource` to `common`

**Files:**
- Create: `common/src/main/java/org/wildfly/a2a/jakarta/common/AgentCardResource.java`

The agent card endpoint is served at `/.well-known/agent-card.json`, outside version routing. It injects the `AgentCard` directly via CDI using the `@PublicAgentCard` qualifier from `a2a-java-sdk-server-common` (which `common` already depends on). This is transport-agnostic — no dependency on `RestHandler` or `JSONRPCHandler`.

- [ ] **Step 1: Create `AgentCardResource`**

```java
package org.wildfly.a2a.jakarta.common;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCard;

@Path("/")
public class AgentCardResource {

    @Inject
    @PublicAgentCard
    AgentCard agentCard;

    @GET
    @Path(".well-known/agent-card.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgentCard() {
        String etag = "\"" + Integer.toHexString(agentCard.hashCode()) + "\"";

        return Response.ok(agentCard)
                .header("Cache-Control", "max-age=3600")
                .header("ETag", etag)
                .build();
    }
}
```

- [ ] **Step 2: Verify build**

Run: `mvn -pl common compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add common/
git commit -m "Add AgentCardResource to common module for version-independent agent card serving"
```

---

### Task 5: Merge `impl/rest-web` into `impl/rest` and add version provider

**Files:**
- Move: `impl/rest-web/src/main/java/org/wildfly/a2a/jakarta/rest/web/A2ARestServerResource.java` → `impl/rest/src/main/java/org/wildfly/a2a/jakarta/rest/A2ARestServerResource.java`
- Create: `impl/rest/src/main/java/org/wildfly/a2a/jakarta/rest/RestVersionProvider_v1_0.java`
- Modify: `pom.xml` (root — remove `impl/rest-web` module from `<modules>` AND `<dependencyManagement>`)
- Delete: `impl/rest-web/` (entire directory)

- [ ] **Step 1: Move `A2ARestServerResource` into `impl/rest` and update it**

Copy from `impl/rest-web/src/main/java/org/wildfly/a2a/jakarta/rest/web/A2ARestServerResource.java` to `impl/rest/src/main/java/org/wildfly/a2a/jakarta/rest/A2ARestServerResource.java`.

Changes:
- Package: `org.wildfly.a2a.jakarta.rest.web` → `org.wildfly.a2a.jakarta.rest`
- `@Path("/")` → `@Path("/a2a_rest_v1.0")`
- Remove `getAgentCard()` method and `@GET @Path(".well-known/agent-card.json")` endpoint
- Remove `@ExtendedAgentCard Instance<AgentCard> extendedAgentCard` field and its imports
- Remove `@Internal Executor executor` field and its imports
- Keep `setStreamingIsSubscribedRunnable()` method — tests use it

- [ ] **Step 2: Create `RestVersionProvider_v1_0`**

```java
package org.wildfly.a2a.jakarta.rest;

import jakarta.enterprise.context.ApplicationScoped;

import org.wildfly.a2a.jakarta.common.A2AVersionProvider;

@ApplicationScoped
public class RestVersionProvider_v1_0 implements A2AVersionProvider {

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public boolean isDefaultVersion() {
        return false;
    }

    @Override
    public String getInternalPathPrefix() {
        return "/a2a_rest_v1.0";
    }

    @Override
    public String getRestBasePath() {
        return "/";
    }
}
```

- [ ] **Step 3: Update root `pom.xml`**

Remove `<module>impl/rest-web</module>` from `<modules>`.

Remove the `a2a-jakarta-rest-web` entry from `<dependencyManagement>` (lines ~165-169).

- [ ] **Step 4: Delete `impl/rest-web/` directory**

```bash
rm -rf impl/rest-web
```

- [ ] **Step 5: Verify build**

Run: `mvn -pl common,impl/rest compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Merge impl/rest-web into impl/rest with version routing prefix"
```

---

### Task 6: Merge `impl/jsonrpc-web` into `impl/jsonrpc` and add version/method providers

**Files:**
- Move: `impl/jsonrpc-web/.../A2AServerResource.java` → `impl/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/A2AServerResource.java`
- Create: `impl/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/JsonRpcVersionProvider_v1_0.java`
- Create: `impl/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/JsonRpcMethodProvider_v1_0.java`
- Modify: `pom.xml` (root — remove `impl/jsonrpc-web` from `<modules>` AND `<dependencyManagement>`)
- Delete: `impl/jsonrpc-web/` (entire directory — includes old `A2ARequestFilter.java` which is replaced by common filter)

- [ ] **Step 0: Add `a2a-jakarta-common` dependency to `impl/jsonrpc/pom.xml`**

`impl/jsonrpc` does NOT currently depend on `a2a-jakarta-common`. Add it — needed for the `A2AVersionProvider` interface:

```xml
<dependency>
    <groupId>org.wildfly.a2a</groupId>
    <artifactId>a2a-jakarta-common</artifactId>
</dependency>
```

- [ ] **Step 1: Move `A2AServerResource` into `impl/jsonrpc` and update it**

Changes:
- Package: `org.wildfly.a2a.jakarta.jsonrpc.web` → `org.wildfly.a2a.jakarta.jsonrpc`
- `@Path("/")` → `@Path("/a2a_jsonrpc_v1.0")`
- Remove `getAgentCard()` method and `@GET @Path("/.well-known/agent-card.json")` endpoint
- Remove `@ExtendedAgentCard Instance<AgentCard>` and `@Internal Executor executor` fields
- Do NOT move `A2ARequestFilter` — it is replaced by `A2AJsonRpcAcceptFilter` in common

- [ ] **Step 2: Create `JsonRpcVersionProvider_v1_0`**

```java
package org.wildfly.a2a.jakarta.jsonrpc;

import jakarta.enterprise.context.ApplicationScoped;

import org.wildfly.a2a.jakarta.common.A2AVersionProvider;

@ApplicationScoped
public class JsonRpcVersionProvider_v1_0 implements A2AVersionProvider {

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public boolean isDefaultVersion() {
        return false;
    }

    @Override
    public String getInternalPathPrefix() {
        return "/a2a_jsonrpc_v1.0";
    }

    @Override
    public String getRestBasePath() {
        return null;
    }
}
```

- [ ] **Step 3: Create `JsonRpcMethodProvider_v1_0`**

```java
package org.wildfly.a2a.jakarta.jsonrpc;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.a2aproject.sdk.spec.A2AMethods;
import org.wildfly.a2a.jakarta.common.A2AJsonRpcMethodProvider;

@ApplicationScoped
public class JsonRpcMethodProvider_v1_0 implements A2AJsonRpcMethodProvider {

    @Override
    public Set<String> getStreamingMethodNames() {
        return Set.of(
                A2AMethods.SEND_STREAMING_MESSAGE_METHOD,
                A2AMethods.SUBSCRIBE_TO_TASK_METHOD);
    }

    @Override
    public Set<String> getNonStreamingMethodNames() {
        return Set.of(
                A2AMethods.GET_TASK_METHOD,
                A2AMethods.CANCEL_TASK_METHOD,
                A2AMethods.SEND_MESSAGE_METHOD,
                A2AMethods.LIST_TASK_METHOD,
                A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
                A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
                A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
                A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
                A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD);
    }
}
```

- [ ] **Step 4: Update root `pom.xml` and delete directory**

Remove `<module>impl/jsonrpc-web</module>` from `<modules>`.
Remove `a2a-jakarta-jsonrpc-web` from `<dependencyManagement>`.

```bash
rm -rf impl/jsonrpc-web
```

- [ ] **Step 5: Verify build**

Run: `mvn -pl common,impl/jsonrpc compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Merge impl/jsonrpc-web into impl/jsonrpc with version routing prefix"
```

---

### Task 7: Merge `compat-0.3/rest-web` into `compat-0.3/rest` and add version provider

**Files:**
- Move: `compat-0.3/rest-web/.../A2ARestServerResource_v0_3.java` → `compat-0.3/rest/src/main/java/org/wildfly/a2a/jakarta/rest/compat03/A2ARestServerResource_v0_3.java`
- Create: `compat-0.3/rest/src/main/java/org/wildfly/a2a/jakarta/rest/compat03/RestVersionProvider_v0_3.java`
- Modify: `compat-0.3/pom.xml` (remove `rest-web` module)
- Modify: root `pom.xml` (remove `a2a-jakarta-compat-0.3-rest-web` from `<dependencyManagement>`)
- Delete: `compat-0.3/rest-web/`

- [ ] **Step 1: Move `A2ARestServerResource_v0_3` and update it**

Changes:
- Package: `org.wildfly.a2a.jakarta.rest.web.compat03` → `org.wildfly.a2a.jakarta.rest.compat03`
- `@Path("/v1")` → `@Path("/a2a_rest_v0.3/v1")` (keep the `/v1` — the filter just prepends the internal prefix, no stripping)
- Keep all endpoint methods as-is
- Do NOT move `AgentCardResource_v0_3` — it is replaced by the common `AgentCardResource` (Task 4)

- [ ] **Step 2: Create `RestVersionProvider_v0_3`**

```java
package org.wildfly.a2a.jakarta.rest.compat03;

import jakarta.enterprise.context.ApplicationScoped;

import org.wildfly.a2a.jakarta.common.A2AVersionProvider;

@ApplicationScoped
public class RestVersionProvider_v0_3 implements A2AVersionProvider {

    @Override
    public String getVersion() {
        return "0.3";
    }

    @Override
    public boolean isDefaultVersion() {
        return true;
    }

    @Override
    public String getInternalPathPrefix() {
        return "/a2a_rest_v0.3";
    }

    @Override
    public String getRestBasePath() {
        return "/v1";
    }
}
```

- [ ] **Step 3: Remove `rest-web` from `compat-0.3/pom.xml`, update root pom, and delete directory**

Remove `<module>rest-web</module>` from `compat-0.3/pom.xml`.
Remove `a2a-jakarta-compat-0.3-rest-web` from root `pom.xml` `<dependencyManagement>`.

```bash
rm -rf compat-0.3/rest-web
```

- [ ] **Step 4: Verify build and commit**

```bash
mvn -pl common,compat-0.3/rest compile -q
git add -A
git commit -m "Merge compat-0.3/rest-web into compat-0.3/rest with version routing prefix"
```

---

### Task 8: Merge `compat-0.3/jsonrpc-web` into `compat-0.3/jsonrpc` and add version/method providers

**Files:**
- Move: `compat-0.3/jsonrpc-web/.../A2AServerResource_v0_3.java` → `compat-0.3/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/compat03/A2AServerResource_v0_3.java`
- Create: `compat-0.3/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/compat03/JsonRpcVersionProvider_v0_3.java`
- Create: `compat-0.3/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/compat03/JsonRpcMethodProvider_v0_3.java`
- Modify: `compat-0.3/pom.xml` (remove `jsonrpc-web` module)
- Modify: root `pom.xml` (remove `a2a-jakarta-compat-0.3-jsonrpc-web` from `<dependencyManagement>`)
- Delete: `compat-0.3/jsonrpc-web/`

- [ ] **Step 0: Add `a2a-jakarta-common` dependency to `compat-0.3/jsonrpc/pom.xml`**

`compat-0.3/jsonrpc` does NOT currently depend on `a2a-jakarta-common`. Add it — needed for `A2AVersionProvider` and `A2AJsonRpcMethodProvider` interfaces:

```xml
<dependency>
    <groupId>org.wildfly.a2a</groupId>
    <artifactId>a2a-jakarta-common</artifactId>
</dependency>
```

- [ ] **Step 1: Move `A2AServerResource_v0_3` and update it**

Changes:
- Package: `org.wildfly.a2a.jakarta.jsonrpc.web.compat03` → `org.wildfly.a2a.jakarta.jsonrpc.compat03`
- `@Path("/")` → `@Path("/a2a_jsonrpc_v0.3")`
- Do NOT move `A2ARequestFilter_v0_3` — replaced by common filter

- [ ] **Step 2: Create `JsonRpcVersionProvider_v0_3`**

```java
package org.wildfly.a2a.jakarta.jsonrpc.compat03;

import jakarta.enterprise.context.ApplicationScoped;

import org.wildfly.a2a.jakarta.common.A2AVersionProvider;

@ApplicationScoped
public class JsonRpcVersionProvider_v0_3 implements A2AVersionProvider {

    @Override
    public String getVersion() {
        return "0.3";
    }

    @Override
    public boolean isDefaultVersion() {
        return true;
    }

    @Override
    public String getInternalPathPrefix() {
        return "/a2a_jsonrpc_v0.3";
    }

    @Override
    public String getRestBasePath() {
        return null;
    }
}
```

- [ ] **Step 3: Create `JsonRpcMethodProvider_v0_3`**

```java
package org.wildfly.a2a.jakarta.jsonrpc.compat03;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.a2aproject.sdk.compat03.spec.CancelTaskRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.DeleteTaskPushNotificationConfigRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.GetAuthenticatedExtendedCardRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.GetTaskPushNotificationConfigRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.GetTaskRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.ListTaskPushNotificationConfigRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.SendMessageRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.SetTaskPushNotificationConfigRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.TaskResubscriptionRequest_v0_3;
import org.wildfly.a2a.jakarta.common.A2AJsonRpcMethodProvider;

@ApplicationScoped
public class JsonRpcMethodProvider_v0_3 implements A2AJsonRpcMethodProvider {

    @Override
    public Set<String> getStreamingMethodNames() {
        return Set.of(
                SendStreamingMessageRequest_v0_3.METHOD,
                TaskResubscriptionRequest_v0_3.METHOD);
    }

    @Override
    public Set<String> getNonStreamingMethodNames() {
        return Set.of(
                SendMessageRequest_v0_3.METHOD,
                GetTaskRequest_v0_3.METHOD,
                CancelTaskRequest_v0_3.METHOD,
                SetTaskPushNotificationConfigRequest_v0_3.METHOD,
                GetTaskPushNotificationConfigRequest_v0_3.METHOD,
                ListTaskPushNotificationConfigRequest_v0_3.METHOD,
                DeleteTaskPushNotificationConfigRequest_v0_3.METHOD,
                GetAuthenticatedExtendedCardRequest_v0_3.METHOD);
    }
}
```

- [ ] **Step 4: Remove module, update poms, delete directory and commit**

```bash
rm -rf compat-0.3/jsonrpc-web
mvn -pl common,compat-0.3/jsonrpc compile -q
git add -A
git commit -m "Merge compat-0.3/jsonrpc-web into compat-0.3/jsonrpc with version routing prefix"
```

---

### Task 9: Update standalone tests and non-test dependents (TCK, examples)

**Files:**
- Modify: `tests/rest/pom.xml`, `tests/rest/.../JakartaA2AServerTest.java`, `tests/rest/.../A2ATestResource.java`
- Modify: `tests/jsonrpc/pom.xml`, `tests/jsonrpc/.../JakartaA2AServerTest.java`, `tests/jsonrpc/.../A2ATestResource.java`
- Modify: `tests/compat-0.3/rest/pom.xml`, `tests/compat-0.3/rest/...` test classes
- Modify: `tests/compat-0.3/jsonrpc/pom.xml`, `tests/compat-0.3/jsonrpc/...` test classes
- Modify: `tests/compat-0.3/grpc/pom.xml` (references `a2a-jakarta-compat-0.3-jsonrpc-web`)
- Modify: `tck/pom.xml` (references `a2a-jakarta-jsonrpc-web`, `a2a-jakarta-rest-web`)
- Modify: `compat-0.3/tck/pom.xml` (references `a2a-jakarta-compat-0.3-jsonrpc-web`, `a2a-jakarta-compat-0.3-rest-web`)
- Modify: `examples/simple/server/pom.xml` (references `a2a-jakarta-jsonrpc-web`, `a2a-jakarta-rest-web`)

- [ ] **Step 1: Update standalone REST test (`tests/rest`)**

In `tests/rest/pom.xml`:
- Replace dependency `a2a-jakarta-rest-web` → `a2a-jakarta-rest` (if not already a dependency)
- Ensure `a2a-jakarta-common` is a dependency

In `JakartaA2AServerTest.java` `createTestArchive()`:
- Update `getJarForClass(A2ARestServerResource.class)` import from `org.wildfly.a2a.jakarta.rest.web.A2ARestServerResource` to `org.wildfly.a2a.jakarta.rest.A2ARestServerResource`
- Add `getJarForClass(A2ARestVersionRoutingFilter.class)` (common filters JAR)
- Add `getJarForClass(AgentCardResource.class)` (common agent card JAR)
- Ensure version provider classes are included in the deployed archive

In `A2ATestResource.java`:
- Update import of `A2ARestServerResource` to new package `org.wildfly.a2a.jakarta.rest`

- [ ] **Step 2: Update standalone JSON-RPC test (`tests/jsonrpc`)**

Same pattern:
- Replace `a2a-jakarta-jsonrpc-web` → `a2a-jakarta-jsonrpc`
- Update import of `A2AServerResource` to `org.wildfly.a2a.jakarta.jsonrpc.A2AServerResource`
- Add common module JAR for filters and agent card
- Include version/method provider classes

- [ ] **Step 3: Update compat-0.3 standalone tests**

- `tests/compat-0.3/rest/pom.xml`: replace `a2a-jakarta-compat-0.3-rest-web` → `a2a-jakarta-compat-0.3-rest`
- `tests/compat-0.3/jsonrpc/pom.xml`: replace `a2a-jakarta-compat-0.3-jsonrpc-web` → `a2a-jakarta-compat-0.3-jsonrpc`
- `tests/compat-0.3/grpc/pom.xml`: replace `a2a-jakarta-compat-0.3-jsonrpc-web` → `a2a-jakarta-compat-0.3-jsonrpc`
- Update imports from `*.web.compat03.*` → `*.compat03.*`
- Add common module JAR and version provider beans to archives

- [ ] **Step 4: Update TCK modules**

In `tck/pom.xml`:
- Replace `a2a-jakarta-jsonrpc-web` → `a2a-jakarta-jsonrpc` (lines 31, 183)
- Replace `a2a-jakarta-rest-web` → `a2a-jakarta-rest` (line 62)
- Update multiversion profile references (line 183+)

In `compat-0.3/tck/pom.xml`:
- Replace `a2a-jakarta-compat-0.3-jsonrpc-web` → `a2a-jakarta-compat-0.3-jsonrpc` (lines 28, 146)
- Replace `a2a-jakarta-compat-0.3-rest-web` → `a2a-jakarta-compat-0.3-rest` (lines 36, 151)

- [ ] **Step 5: Update examples module**

In `examples/simple/server/pom.xml`:
- Replace all `a2a-jakarta-jsonrpc-web` → `a2a-jakarta-jsonrpc` (lines 118, 158, 234)
- Replace `a2a-jakarta-rest-web` → `a2a-jakarta-rest` (line 223)
- Add `a2a-jakarta-common` dependency where needed

- [ ] **Step 6: Verify build and run standalone tests**

```bash
mvn compile -q
mvn -pl tests/rest verify
mvn -pl tests/jsonrpc verify
mvn -pl tests/compat-0.3/rest verify
mvn -pl tests/compat-0.3/jsonrpc verify
```
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Update standalone tests, TCK, and examples for merged module structure"
```

---

### Task 10: Update multiversion tests and delete multiversion modules

**Files:**
- Modify: `tests/multiversion/rest/pom.xml` and all test classes
- Modify: `tests/multiversion/jsonrpc/pom.xml` and all test classes
- Modify: `tests/multiversion/grpc/pom.xml` and test classes
- Modify: `tests/multiversion/rest/.../producer/MultiVersionAgentCardProducer.java`
- Modify: `compat-0.3/pom.xml` (remove multiversion modules)
- Modify: root `pom.xml` (remove multiversion entries from `<dependencyManagement>`)
- Modify: `tck/pom.xml` and `compat-0.3/tck/pom.xml` (multiversion profile references)
- Delete: `compat-0.3/multiversion-rest/`
- Delete: `compat-0.3/multiversion-jsonrpc/`

- [ ] **Step 1: Update multiversion REST test**

In `tests/multiversion/rest/pom.xml`:
- Replace `a2a-jakarta-compat-0.3-multiversion-rest` with `a2a-jakarta-rest` + `a2a-jakarta-compat-0.3-rest`
- Add `a2a-jakarta-common` if not present

In `MultiVersionRestTest.java`:
- **Change `getTransportUrl()` from `"http://localhost:8080/v1"` to `"http://localhost:8080"`** — the v1.0 SDK appends endpoint paths directly, v0.3 SDK internally adds `/v1`
- In `createTestArchive()`: replace `getJarForClass(MultiVersionA2ARestServerResource.class)` with individual module JARs + common filter/agent card JARs
- Remove references to `MultiVersionAgentCardResource`

In `MultiVersionAgentCardProducer.java`:
- **Change transport URL from `"http://localhost:" + port + "/v1"` to `"http://localhost:" + port`** — v1.0 SDK appends paths directly, v0.3 SDK internally adds `/v1`
- **Preserve the `Compat03Fields.addCompat03FieldsIfAvailable()` call** — it is needed for v0.3 backward compatibility in the agent card

In `A2ATestResource.java`:
- Replace `MultiVersionA2ARestServerResource.setStreamingIsSubscribedRunnable(...)` with:
  ```java
  A2ARestServerResourceDelegate.setStreamingIsSubscribedRunnable(streamingSubscribedCount::incrementAndGet);
  A2ARestServerResourceDelegate_v0_3.setStreamingIsSubscribedRunnable(streamingSubscribedCount::incrementAndGet);
  ```

- [ ] **Step 2: Update multiversion JSON-RPC test**

Same pattern:
- Replace `a2a-jakarta-compat-0.3-multiversion-jsonrpc` dependency with individual modules
- Replace `MultiVersionA2AServerResource` and `MultiVersionA2ARequestFilter` references
- Update `A2ATestResource` streaming subscription setup (call both v1.0 and v0.3 delegate methods)

- [ ] **Step 3: Update multiversion gRPC test**

`tests/multiversion/grpc/pom.xml` references `a2a-jakarta-compat-0.3-multiversion-jsonrpc` (for the agent card endpoint via `MultiVersionA2AServerResource`). Replace with `a2a-jakarta-common` — the agent card is now served by the common `AgentCardResource` (which injects `AgentCard` via CDI, transport-agnostic). Add `a2a-jakarta-jsonrpc` if needed for other dependencies. Update test archive to include the common module JAR (`getJarForClass(AgentCardResource.class)`).

- [ ] **Step 4: Update TCK multiversion profiles**

The multiversion profile is now simpler — all resources have unique `@Path` prefixes, so no exclusions are needed.

In `tck/pom.xml` multiversion profile:
- Remove the exclusion of `a2a-jakarta-jsonrpc-web` (was needed because v1.0 and multiversion JSON-RPC both claimed `@Path("/")` — no longer the case)
- Replace `a2a-jakarta-compat-0.3-multiversion-jsonrpc` and `a2a-jakarta-compat-0.3-multiversion-rest` with `a2a-jakarta-compat-0.3-jsonrpc` + `a2a-jakarta-compat-0.3-rest`
- Ensure `a2a-jakarta-common` is included (may be transitively available)
- Keep `build-helper-maven-plugin` for `src/multi-version/java` sources if there are test configuration differences

In `compat-0.3/tck/pom.xml` multiversion profile: same pattern — replace multiversion module references with individual compat-0.3 module references.

- [ ] **Step 5: Delete multiversion modules**

In `compat-0.3/pom.xml`, remove:
- `<module>multiversion-rest</module>`
- `<module>multiversion-jsonrpc</module>`

In root `pom.xml`, remove from `<dependencyManagement>`:
- `a2a-jakarta-compat-0.3-multiversion-jsonrpc` entry
- `a2a-jakarta-compat-0.3-multiversion-rest` entry

```bash
rm -rf compat-0.3/multiversion-rest compat-0.3/multiversion-jsonrpc
```

- [ ] **Step 6: Verify build and run multiversion tests**

```bash
mvn compile -q
mvn -pl tests/multiversion/rest,tests/multiversion/jsonrpc,tests/multiversion/grpc verify
```
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Update multiversion tests and delete multiversion modules"
```

---

### Task 11: Rename `common` module to `http-common`

**Files:**
- Rename: `common/` → `http-common/`
- Modify: `http-common/pom.xml` (change artifactId to `a2a-jakarta-http-common`)
- Modify: `pom.xml` (root — change module name + `<dependencyManagement>` artifactId)
- Modify: All POMs that depend on `a2a-jakarta-common` → `a2a-jakarta-http-common`

- [ ] **Step 1: Rename directory and update artifactId**

```bash
git mv common http-common
```

In `http-common/pom.xml`, change `<artifactId>a2a-jakarta-common</artifactId>` to `<artifactId>a2a-jakarta-http-common</artifactId>`.

- [ ] **Step 2: Update root `pom.xml`**

Change `<module>common</module>` to `<module>http-common</module>`.
Update `a2a-jakarta-common` to `a2a-jakarta-http-common` in `<dependencyManagement>`.

- [ ] **Step 3: Update all dependent POMs**

Run `grep -rn "a2a-jakarta-common" --include="pom.xml"` to find all occurrences and replace with `a2a-jakarta-http-common`. Known files:
- `impl/rest/pom.xml`
- `compat-0.3/rest/pom.xml`
- Any test POMs that reference it

No Java import changes needed — package name stays `org.wildfly.a2a.jakarta.common`.

- [ ] **Step 4: Verify full build and run all tests**

```bash
mvn clean verify
```
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Rename common module to http-common to clarify HTTP-transport scope"
```

---

### Task 12: Final verification and cleanup

- [ ] **Step 1: Full clean build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Verify no references to deleted modules**

```bash
grep -rn "multiversion-rest\|multiversion-jsonrpc\|rest-web\|jsonrpc-web" --include="pom.xml" --include="*.java" | grep -v target | grep -v docs
```
Expected: No matches

- [ ] **Step 3: Verify no references to old package names**

```bash
grep -rn "org.wildfly.a2a.jakarta.rest.web\b\|org.wildfly.a2a.jakarta.jsonrpc.web\b\|org.wildfly.a2a.jakarta.rest.multiversion\|org.wildfly.a2a.jakarta.jsonrpc.multiversion" --include="*.java" | grep -v target
```
Expected: No matches

- [ ] **Step 4: Commit if any cleanup was needed**

```bash
git add -A
git commit -m "Final cleanup after multiversion routing redesign"
```

---

### Task 13: Replace centralized AgentCardResource with per-module endpoints and routing filter

The centralized `AgentCardResource` in `http-common` injects `@PublicAgentCard Instance<AgentCard>`. This breaks v0.3-only deployments because `AgentCard` is a record (can't be subclassed) and v0.3 produces `AgentCard_v0_3` — a different type. CDI can't satisfy `Instance<AgentCard>` with an `AgentCard_v0_3` bean, so the endpoint returns 404.

Fix: revert to per-module agent card endpoints (each using its own transport-specific handler), with a new routing filter to direct `GET /.well-known/agent-card.json` to the correct prefixed endpoint.

- [ ] **Step 1: Create `AgentCardRoutingFilter` in `http-common`**

Create `http-common/src/main/java/org/wildfly/a2a/jakarta/common/AgentCardRoutingFilter.java`

This filter ONLY handles `GET /.well-known/agent-card.json`. It picks the provider with the highest version number and prepends its internal prefix.

```java
package org.wildfly.a2a.jakarta.common;

import java.net.URI;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

@Provider
@PreMatching
@Priority(50)
public class AgentCardRoutingFilter implements ContainerRequestFilter {

    private static final String AGENT_CARD_PATH = ".well-known/agent-card.json";

    @Inject
    Instance<A2AVersionProvider> allVersionProviders;

    private volatile boolean initialized;
    private A2AVersionProvider selectedProvider;

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    A2AVersionProvider best = null;
                    for (A2AVersionProvider provider : allVersionProviders) {
                        if (best == null || compareVersions(provider.getVersion(), best.getVersion()) > 0) {
                            best = provider;
                        }
                    }
                    selectedProvider = best;
                    initialized = true;
                }
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!requestContext.getMethod().equals("GET")) {
            return;
        }

        String path = requestContext.getUriInfo().getPath().trim();
        if (!path.endsWith(AGENT_CARD_PATH)) {
            return;
        }

        ensureInitialized();

        if (selectedProvider == null) {
            return;
        }

        String prefix = selectedProvider.getInternalPathPrefix();
        String restBasePath = selectedProvider.getRestBasePath();
        if (restBasePath != null && !restBasePath.equals("/")) {
            prefix = prefix + restBasePath;
        }
        String newPath = prefix + (path.startsWith("/") ? path : "/" + path);

        URI baseUri = requestContext.getUriInfo().getBaseUri();
        URI requestUri = requestContext.getUriInfo().getRequestUri();
        URI newRequestUri = UriBuilder.fromUri(requestUri)
                .replacePath(baseUri.getPath() + newPath).build();
        requestContext.setRequestUri(baseUri, newRequestUri);
    }

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }
}
```

Routing behavior:
- v1.0 only → picks v1.0, prepends its prefix
- v0.3 only → picks v0.3, prepends its prefix
- Multiversion → picks v1.0 (highest version), serves v1.0 card

The existing `/.well-known/` exclusion in `A2ARestVersionRoutingFilter` stays — the path is already rewritten to `/a2a_...` before the REST filter runs, so the exclusion never fires.

- [ ] **Step 2: Delete `AgentCardResource` from `http-common`**

Delete `http-common/src/main/java/org/wildfly/a2a/jakarta/common/AgentCardResource.java`

- [ ] **Step 3: Add `getAgentCard()` back to v1.0 REST resource**

In `impl/rest/src/main/java/org/wildfly/a2a/jakarta/rest/A2ARestServerResource.java`, add:

```java
@GET
@Path(".well-known/agent-card.json")
@Produces(MediaType.APPLICATION_JSON)
public Response getAgentCard() {
    return getDelegate().getAgentCard();
}
```

The delegate method `A2ARestServerResourceDelegate.getAgentCard()` already exists (line 115) and uses `RestHandler`.

- [ ] **Step 4: Add `getAgentCard()` back to v1.0 JSON-RPC resource**

In `impl/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/A2AServerResource.java`, add:

```java
@GET
@Path(".well-known/agent-card.json")
@Produces(MediaType.APPLICATION_JSON)
public Response getAgentCard() {
    return getDelegate().getAgentCard();
}
```

The delegate method `A2AServerResourceDelegate.getAgentCard()` already exists (line 201) and uses `JSONRPCHandler`.

- [ ] **Step 5: Add `getAgentCard()` back to v0.3 REST resource**

In `compat-0.3/rest/src/main/java/org/wildfly/a2a/jakarta/rest/compat03/A2ARestServerResource_v0_3.java` (at `@Path("/a2a_rest_v0.3/v1")`), add:

```java
@GET
@Path(".well-known/agent-card.json")
@Produces(MediaType.APPLICATION_JSON)
public Response getAgentCard() {
    return getDelegate().getAgentCard();
}
```

The delegate method `A2ARestServerResourceDelegate_v0_3.getAgentCard()` already exists (line 186) and uses `RestHandler_v0_3`.

The routing filter computes the full prefix as `getInternalPathPrefix()` + `getRestBasePath()` = `/a2a_rest_v0.3` + `/v1` = `/a2a_rest_v0.3/v1`, so the agent card is routed to `/a2a_rest_v0.3/v1/.well-known/agent-card.json` — matching this resource.

- [ ] **Step 6: Add `getAgentCard()` back to v0.3 JSON-RPC resource**

In `compat-0.3/jsonrpc/src/main/java/org/wildfly/a2a/jakarta/jsonrpc/compat03/A2AServerResource_v0_3.java`, add:

```java
@GET
@Path(".well-known/agent-card.json")
@Produces(MediaType.APPLICATION_JSON)
public Response getAgentCard() {
    return getDelegate().getAgentCard();
}
```

The delegate method `A2AServerResourceDelegate_v0_3.getAgentCard()` already exists (line 231) and uses `JSONRPCHandler_v0_3`.

- [ ] **Step 7: Update test archives**

Test archives may need to include the new `AgentCardRoutingFilter` class. Check each test's `createTestArchive()` — the filter should be discovered automatically if `http-common` JAR is included (via `getJarForClass(A2ARestVersionRoutingFilter.class)` or similar). No changes needed if the common JAR is already included.

For v0.3 REST tests: the archive needs the new `AgentCardResource_v0_3` class. If the v0.3 REST JAR is included via `getJarForClass(A2ARestServerResource_v0_3.class)`, the new class should be picked up automatically (same package, same JAR).

- [ ] **Step 8: Remove `a2a-java-sdk-spec` or `a2a-java-sdk-transport-rest` from `http-common/pom.xml` if present**

Check if either was added during implementation. Neither should be needed — `http-common` depends on `a2a-java-sdk-server-common` which transitively provides what the filters need. The `AgentCard`/`@PublicAgentCard` imports are no longer used in `http-common` after removing `AgentCardResource`.

- [ ] **Step 9: Verify**

```bash
mvn clean verify
mvn -pl tests/rest verify
mvn -pl tests/jsonrpc verify
mvn -pl tests/compat-0.3/rest verify
mvn -pl tests/compat-0.3/jsonrpc verify
mvn -pl tests/multiversion/rest,tests/multiversion/jsonrpc,tests/multiversion/grpc verify
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Replace centralized AgentCardResource with per-module endpoints and routing filter

The centralized AgentCardResource injected @PublicAgentCard Instance<AgentCard>,
which fails in v0.3-only deployments where AgentCard_v0_3 (a different record type)
is produced instead. Each transport module now has its own agent card endpoint using
its own handler, and a new AgentCardRoutingFilter routes GET /.well-known/agent-card.json
to the highest-version provider's prefixed endpoint."
```
