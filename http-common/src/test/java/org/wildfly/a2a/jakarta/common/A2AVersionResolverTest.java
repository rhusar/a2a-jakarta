package org.wildfly.a2a.jakarta.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2AVersionResolverTest {

    @Test
    void resolveByHeader_returnsMatchingProvider() {
        A2AVersionProvider p1 = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionProvider p2 = TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(p1, p2));

        assertSame(p1, resolver.resolve("1.0"));
        assertSame(p2, resolver.resolve("0.3"));
    }

    @Test
    void resolveNullHeader_returnsDefaultProvider() {
        A2AVersionProvider defaultProvider = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionProvider other = TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(defaultProvider, other));

        assertSame(defaultProvider, resolver.resolve(null));
    }

    @Test
    void resolveBlankHeader_returnsDefaultProvider() {
        A2AVersionProvider defaultProvider = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(defaultProvider));

        assertSame(defaultProvider, resolver.resolve("  "));
    }

    @Test
    void resolveUnknownHeader_returnsNull() {
        A2AVersionProvider p = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(p));

        assertNull(resolver.resolve("99.0"));
    }

    @Test
    void soleProvider_becomesDefault_evenIfNotMarked() {
        A2AVersionProvider p = TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(p));

        assertSame(p, resolver.resolve(null));
    }

    @Test
    void multipleDefaults_throwsException() {
        A2AVersionProvider p1 = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionProvider p2 = TestProviders.provider("0.3", true, "/a2a_rest_v0.3", "/v1");

        assertThrows(IllegalStateException.class, () -> new A2AVersionResolver(List.of(p1, p2)));
    }

    @Test
    void multipleProviders_noDefault_resolveNull_returnsNull() {
        A2AVersionProvider p1 = TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/");
        A2AVersionProvider p2 = TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(p1, p2));

        assertNull(resolver.resolve(null));
    }

    @Test
    void hasProviders_empty_returnsFalse() {
        A2AVersionResolver resolver = new A2AVersionResolver(List.of());
        assertFalse(resolver.hasProviders());
    }

    @Test
    void hasProviders_nonEmpty_returnsTrue() {
        A2AVersionProvider p = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(p));
        assertTrue(resolver.hasProviders());
    }

    @Test
    void supportedVersionsString_returnsCommaSeparatedInInsertionOrder() {
        A2AVersionProvider p1 = TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/");
        A2AVersionProvider p2 = TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1");
        A2AVersionResolver resolver = new A2AVersionResolver(List.of(p1, p2));

        assertEquals("1.0, 0.3", resolver.supportedVersionsString());
    }
}
