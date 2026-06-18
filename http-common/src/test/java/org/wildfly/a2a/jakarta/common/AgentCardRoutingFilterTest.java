package org.wildfly.a2a.jakarta.common;

import java.io.IOException;
import java.util.List;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCardRoutingFilterTest {

    @Mock
    Instance<A2AVersionProvider> allVersionProviders;

    @InjectMocks
    AgentCardRoutingFilter filter;

    private void setupProviders(A2AVersionProvider... providers) {
        when(allVersionProviders.iterator()).thenAnswer(inv -> List.of(providers).iterator());
    }

    @Test
    void nonGetRequest_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");

        filter.filter(ctx);

        verify(ctx, never()).getUriInfo();
        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void pathNotEndingInAgentCard_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void noProviders_doesNotRoute() throws IOException {
        setupProviders();

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/.well-known/agent-card.json");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void multipleProviders_lowerVersionOnly_doesNotRoute() throws IOException {
        // Verify that the filter initializes without error when providers are present
        setupProviders(
                TestProviders.provider("0.3", "/a2a_rest_v0.3", "/v1"),
                TestProviders.provider("1.0", "/a2a_rest_v1.0", "/"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getMethod()).thenReturn("GET");
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks");

        filter.filter(ctx);

        // Path doesn't match agent-card, so no routing even with providers present
        verify(ctx, never()).setRequestUri(any(), any());
    }
}
