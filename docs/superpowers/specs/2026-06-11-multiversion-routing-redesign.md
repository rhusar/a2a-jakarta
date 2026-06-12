# Multi-Version Routing Redesign

## Problem

The current multi-version protocol support duplicates logic across 6 modules (`impl/rest-web`, `impl/jsonrpc-web`, `compat-0.3/rest-web`, `compat-0.3/jsonrpc-web`, `compat-0.3/multiversion-rest`, `compat-0.3/multiversion-jsonrpc`). The multiversion resource classes contain if-else version routing in every endpoint method, and request filters are ~95% duplicated. Adding a new protocol version (e.g., 1.1) would require modifying existing multiversion modules and creating additional duplicated filter/resource classes.

## Solution Overview

Replace the multiversion routing layer with:

1. Two CDI-discoverable interfaces for version metadata
2. Two `@PreMatching` JAX-RS filters that route requests based on the `A2A_VERSION` header
3. Transport+version path prefixes on resource `@Path` annotations (e.g., `/a2a_rest_v1.0`)
4. Module consolidation: merge `-web` modules into their parents, delete multiversion modules entirely

Adding a future protocol version means adding new modules with their own resources and version provider beans. Zero changes to existing modules or filters.

## Interfaces

Both interfaces live in the `http-common` module.

### A2AVersionProvider

Implemented by each version module (one per transport per version). Discovered via CDI `Instance<A2AVersionProvider>`.

```java
public interface A2AVersionProvider {
    /** Protocol version string, e.g. "1.0", "0.3" */
    String getVersion();

    /** Whether this is the default when no A2A_VERSION header is sent */
    boolean isDefaultVersion();

    /** Internal JAX-RS path prefix, e.g. "/a2a_rest_v1.0" or "/a2a_jsonrpc_v0.3" */
    String getInternalPathPrefix();

    /** Client-facing REST base path, e.g. "/" for v1.0, "/v1" for v0.3. Not used by JSON-RPC providers. */
    String getRestBasePath();
}
```

Each REST module provides an `A2AVersionProvider` bean. Each JSON-RPC module also provides one.

### A2AJsonRpcMethodProvider

Implemented by each JSON-RPC version module. Discovered via CDI `Instance<A2AJsonRpcMethodProvider>`.

```java
public interface A2AJsonRpcMethodProvider {
    /** JSON-RPC method names that require SSE streaming */
    Set<String> getStreamingMethodNames();

    /** JSON-RPC method names that return regular JSON responses */
    Set<String> getNonStreamingMethodNames();
}
```

## Filters

Both filters live in `http-common`. Both are `@Provider @PreMatching` JAX-RS `ContainerRequestFilter` implementations.

### A2AJsonRpcAcceptFilter (runs first, higher `@Priority`)

Handles JSON-RPC transport routing and Accept header setting.

**Activation**: POST to exactly `/` with a request body.

**Logic**:
1. Read request body (buffer and reset entity stream, same as current filters)
2. Check body against all streaming method names (collected from all `A2AJsonRpcMethodProvider` beans at startup) → set Accept to `text/event-stream`
3. Otherwise check against all non-streaming method names → set Accept to `application/json`
4. Read `A2A_VERSION` header → resolve version (see Version Resolution below)
5. Prepend the resolved version's internal prefix (e.g., `/a2a_jsonrpc_v1.0`) to the request URI via `requestContext.setRequestUri()`

### A2ARestVersionRoutingFilter (runs second, lower `@Priority`)

Handles REST transport routing.

**Activation**:
- Path starts with a known REST base path collected from all `A2AVersionProvider` beans (e.g., `/v1/`)
- OR `A2A_VERSION` header is present
- AND path has not already been rewritten by the JSON-RPC filter (does not start with `/a2a_`)
- AND path does not start with `/.well-known/` (agent card, not version-routed)

**Logic**:
1. Read `A2A_VERSION` header → resolve version (see Version Resolution below)
2. Prepend the resolved version's internal prefix to the incoming path (e.g., `/v1/message:send` → `/a2a_rest_v0.3/v1/message:send`, or `/message:send` → `/a2a_rest_v1.0/message:send`)
3. Call `requestContext.setRequestUri()` with the rewritten URI

## Version Resolution

The same logic applies in both filters:

1. If `A2A_VERSION` header is present → find the `A2AVersionProvider` with matching version string. If no match → return error response ("version not supported").
2. If no header and multiple versions are deployed → use the provider marked `isDefaultVersion() == true` (currently v0.3, per A2A spec: "if no version header is specified, 0.3 is assumed").
3. If no header and only one version is deployed → use that version (no negotiation needed, matches current standalone behavior).

## Resource Path Changes

Each version's JAX-RS resource classes change their `@Path` annotation to use the internal prefix. Sub-paths remain unchanged.

| Resource | Current `@Path` | New `@Path` |
|----------|----------------|-------------|
| v1.0 REST | `"/"` | `"/a2a_rest_v1.0"` |
| v0.3 REST | `"/v1"` | `"/a2a_rest_v0.3/v1"` |
| v1.0 JSON-RPC | `"/"` | `"/a2a_jsonrpc_v1.0"` |
| v0.3 JSON-RPC | `"/"` | `"/a2a_jsonrpc_v0.3"` |

Sub-paths (`message:send`, `tasks/{taskId}`, POST at root for JSON-RPC, etc.) are unchanged.

### Routing examples

**REST v0.3**: Client sends `POST /v1/message:send` (no header, defaults to 0.3)
→ REST filter prepends `/a2a_rest_v0.3` → `/a2a_rest_v0.3/v1/message:send`
→ matches `@Path("/a2a_rest_v0.3/v1")` + `@Path("message:send")`

**REST v1.0**: Client sends `POST /message:send` with `A2A_VERSION: 1.0`
→ REST filter prepends `/a2a_rest_v1.0` → `/a2a_rest_v1.0/message:send`
→ matches `@Path("/a2a_rest_v1.0")` + `@Path("message:send")`

**JSON-RPC v1.0**: Client sends `POST /` with `A2A_VERSION: 1.0` and body containing `"message/send"`
→ JSON-RPC filter sets Accept header, prepends `/a2a_jsonrpc_v1.0` → `/a2a_jsonrpc_v1.0/`
→ matches `@Path("/a2a_jsonrpc_v1.0")` + POST at root

**JSON-RPC v0.3**: Client sends `POST /` (no header, defaults to 0.3) with body containing `"message/send"`
→ JSON-RPC filter sets Accept header, prepends `/a2a_jsonrpc_v0.3` → `/a2a_jsonrpc_v0.3/`
→ matches `@Path("/a2a_jsonrpc_v0.3")` + POST at root

## Agent Card

`GET /.well-known/agent-card.json` is a discovery endpoint that clients hit before they know the protocol version. It is not version-routed — the REST filter skips paths starting with `/.well-known/`.

An `AgentCardResource` in `http-common` registers at `@Path("/")` with `@Path(".well-known/agent-card.json")`. It injects the `AgentCard` directly via CDI using the `@PublicAgentCard` qualifier (from the SDK's `a2a-java-sdk-server-common`). This is transport-agnostic — it works in REST, JSON-RPC, and gRPC deployments without depending on any transport-specific handler. Both REST and JSON-RPC deployments serve the agent card through this single resource.

## Module Structure

### Deleted modules (6)

| Module | Reason |
|--------|--------|
| `impl/rest-web` | Merged into `impl/rest` |
| `impl/jsonrpc-web` | Merged into `impl/jsonrpc` |
| `compat-0.3/rest-web` | Merged into `compat-0.3/rest` |
| `compat-0.3/jsonrpc-web` | Merged into `compat-0.3/jsonrpc` |
| `compat-0.3/multiversion-rest` | Eliminated entirely |
| `compat-0.3/multiversion-jsonrpc` | Eliminated entirely |

### Renamed module

`common/` → `http-common/` (since gRPC does not use it; the name should reflect that it is HTTP-transport-specific).

### Resulting structure

```
http-common/             → interfaces, filters, AgentCardResource,
                           SSESubscriber, AsyncManagedExecutorServiceProducer
impl/
  rest/                  → v1.0 REST delegate + resource + A2AVersionProvider bean
  jsonrpc/               → v1.0 JSON-RPC delegate + resource + A2AVersionProvider bean
                           + A2AJsonRpcMethodProvider bean
  grpc/                  → (unchanged)
compat-0.3/
  rest/                  → v0.3 REST delegate + resource + A2AVersionProvider bean
  jsonrpc/               → v0.3 JSON-RPC delegate + resource + A2AVersionProvider bean
                           + A2AJsonRpcMethodProvider bean
  grpc/                  → (unchanged)
  tck/                   → (unchanged)
```

### What each module gains (from merge)

- **`impl/rest`**: `A2ARestServerResource` (from `impl/rest-web`), `A2AVersionProvider` bean for v1.0 REST
- **`impl/jsonrpc`**: `A2AServerResource` (from `impl/jsonrpc-web`), `A2AVersionProvider` bean for v1.0 JSON-RPC, `A2AJsonRpcMethodProvider` bean for v1.0
- **`compat-0.3/rest`**: `A2ARestServerResource_v0_3` (from `compat-0.3/rest-web`; `AgentCardResource_v0_3` is NOT moved — replaced by common `AgentCardResource`), `A2AVersionProvider` bean for v0.3 REST
- **`compat-0.3/jsonrpc`**: `A2AServerResource_v0_3` (from `compat-0.3/jsonrpc-web`), `A2AVersionProvider` bean for v0.3 JSON-RPC, `A2AJsonRpcMethodProvider` bean for v0.3

### Adding a future version (e.g., v1.1)

Add new modules (e.g., `impl/rest-v1.1`, `impl/jsonrpc-v1.1` or under a new `compat-1.1/` directory) with:
- Resource classes at `@Path("/a2a_rest_v1.1")` / `@Path("/a2a_jsonrpc_v1.1")`
- Delegate classes for v1.1 protocol handling
- `A2AVersionProvider` beans
- `A2AJsonRpcMethodProvider` beans (for JSON-RPC)

Zero changes to existing version modules or filters. The filters discover the new version via CDI automatically.
