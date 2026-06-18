package org.wildfly.a2a.jakarta.common;

import java.io.IOException;
import java.util.List;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
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
class A2ARestVersionRoutingFilterTest {

    @Mock
    Instance<A2AVersionProvider> allVersionProviders;

    @InjectMocks
    A2ARestVersionRoutingFilter filter;

    private void setupProvider(A2AVersionProvider... providers) {
        when(allVersionProviders.iterator()).thenAnswer(inv -> List.of(providers).iterator());
    }

    @Test
    void wellKnownPath_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/.well-known/agent-card.json");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void internalA2aPath_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/a2a_rest_v1.0/tasks");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void noVersionHeader_onlyRootBasePath_isSkipped() throws IOException {
        setupProvider(TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void multipleProviders_noDefault_nullVersionHeader_isSkipped() throws IOException {
        setupProvider(
                TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/"),
                TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/unknown-path");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void unknownVersionHeader_abortsWithBadRequest() throws IOException {
        setupProvider(TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        assertEquals(400, responseCaptor.getValue().getStatus());
    }

    @Test
    void errorResponse_containsEscapedVersionHeader() throws IOException {
        setupProvider(TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("bad\"version");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        String body = responseCaptor.getValue().getEntity().toString();
        assertFalse(body.contains("\"version\""), "Unescaped quote must not appear in JSON body");
        assertTrue(body.contains("\\\""), "Quote must be JSON-escaped in error body");
    }

    @Test
    void multipleProviders_noDefault_unknownVersion_abortsWithBadRequest() throws IOException {
        setupProvider(
                TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/"),
                TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        verify(ctx).abortWith(any(Response.class));
    }
}
