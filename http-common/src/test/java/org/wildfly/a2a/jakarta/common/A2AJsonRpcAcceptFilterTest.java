package org.wildfly.a2a.jakarta.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.a2aproject.sdk.common.A2AHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, RuntimeDelegateExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class A2AJsonRpcAcceptFilterTest {

    private static final String STREAMING_METHOD = "tasks/sendSubscribe";
    private static final String NON_STREAMING_METHOD = "tasks/send";

    @Mock
    Instance<A2AVersionProvider> allVersionProviders;

    @Mock
    Instance<A2AJsonRpcMethodProvider> methodProviders;

    @InjectMocks
    A2AJsonRpcAcceptFilter filter;

    private void setupProvider(A2AVersionProvider... providers) {
        when(allVersionProviders.iterator()).thenAnswer(inv -> List.of(providers).iterator());
        A2AJsonRpcMethodProvider methodProvider = new A2AJsonRpcMethodProvider() {
            @Override public Set<String> getStreamingMethodNames() { return Set.of(STREAMING_METHOD); }
            @Override public Set<String> getNonStreamingMethodNames() { return Set.of(NON_STREAMING_METHOD); }
        };
        when(methodProviders.iterator()).thenAnswer(inv -> List.of(methodProvider).iterator());
    }

    private static ContainerRequestContext mockJsonRpcContext(String body) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(ctx.getMethod()).thenReturn("POST");
        when(ctx.hasEntity()).thenReturn(true);
        when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(ctx.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        return ctx;
    }

    @Test
    void nonPostRequest_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(ctx.getMethod()).thenReturn("GET");

        filter.filter(ctx);

        verify(ctx, never()).getEntityStream();
    }

    @Test
    void nonRootPath_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/something");
        when(ctx.getMethod()).thenReturn("POST");
        when(ctx.hasEntity()).thenReturn(true);

        filter.filter(ctx);

        verify(ctx, never()).getEntityStream();
    }

    @Test
    void noEntity_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(ctx.getMethod()).thenReturn("POST");
        when(ctx.hasEntity()).thenReturn(false);

        filter.filter(ctx);

        verify(ctx, never()).getEntityStream();
    }

    @Test
    void streamingMethod_setsSSEAcceptHeader() throws IOException {
        // Use an unknown version to abort before URI building, allowing us to verify the Accept header was set.
        setupProvider(TestProviders.jsonRpcProvider("1.0", true));

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + STREAMING_METHOD + "\",\"id\":1}";
        ContainerRequestContext ctx = mockJsonRpcContext(body);
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        when(ctx.getHeaders()).thenReturn(headers);
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        assertEquals(MediaType.SERVER_SENT_EVENTS, headers.getFirst("Accept"));
        verify(ctx).abortWith(any());
    }

    @Test
    void nonStreamingMethod_setsJsonAcceptHeader() throws IOException {
        setupProvider(TestProviders.jsonRpcProvider("1.0", true));

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + NON_STREAMING_METHOD + "\",\"id\":1}";
        ContainerRequestContext ctx = mockJsonRpcContext(body);
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        when(ctx.getHeaders()).thenReturn(headers);
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        assertEquals(MediaType.APPLICATION_JSON, headers.getFirst("Accept"));
        verify(ctx).abortWith(any());
    }

    @Test
    void unknownVersionHeader_abortsWithJsonRpcError() throws IOException {
        setupProvider(TestProviders.jsonRpcProvider("1.0", true));

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + NON_STREAMING_METHOD + "\",\"id\":42}";
        ContainerRequestContext ctx = mockJsonRpcContext(body);
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        assertEquals(400, responseCaptor.getValue().getStatus());
        String responseBody = responseCaptor.getValue().getEntity().toString();
        assertTrue(responseBody.contains("\"id\":42"), "JSON-RPC error must include the request id");
    }

    @Test
    void errorResponse_containsEscapedVersionHeader() throws IOException {
        setupProvider(TestProviders.jsonRpcProvider("1.0", true));

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + NON_STREAMING_METHOD + "\",\"id\":1}";
        ContainerRequestContext ctx = mockJsonRpcContext(body);
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("bad\"version");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        String responseBody = responseCaptor.getValue().getEntity().toString();
        assertFalse(responseBody.contains("\"version\""), "Unescaped quote must not appear in JSON body");
        assertTrue(responseBody.contains("\\\""), "Quote must be JSON-escaped in error body");
    }

    @Test
    void multipleProviders_noDefault_unknownVersion_abortsWithError() throws IOException {
        setupProvider(
                TestProviders.jsonRpcProvider("1.0", false),
                TestProviders.jsonRpcProvider("0.3", false));

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + NON_STREAMING_METHOD + "\",\"id\":1}";
        ContainerRequestContext ctx = mockJsonRpcContext(body);
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        verify(ctx).abortWith(any(Response.class));
    }

    @Test
    void multipleProviders_noDefault_nullVersionHeader_abortsWithError() throws IOException {
        setupProvider(
                TestProviders.jsonRpcProvider("1.0", false),
                TestProviders.jsonRpcProvider("0.3", false));

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + NON_STREAMING_METHOD + "\",\"id\":1}";
        ContainerRequestContext ctx = mockJsonRpcContext(body);
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn(null);

        filter.filter(ctx);

        verify(ctx).abortWith(any(Response.class));
    }
}
