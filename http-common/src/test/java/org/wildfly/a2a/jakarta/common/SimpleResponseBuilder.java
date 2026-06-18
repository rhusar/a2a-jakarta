package org.wildfly.a2a.jakarta.common;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

class SimpleResponseBuilder extends Response.ResponseBuilder {

    private int status;
    private Object entity;

    @Override
    public Response build() {
        return new SimpleResponse(status, entity);
    }

    @Override
    public Response.ResponseBuilder clone() {
        SimpleResponseBuilder copy = new SimpleResponseBuilder();
        copy.status = this.status;
        copy.entity = this.entity;
        return copy;
    }

    @Override public Response.ResponseBuilder status(int status) { this.status = status; return this; }
    @Override public Response.ResponseBuilder status(int status, String reasonPhrase) { this.status = status; return this; }
    @Override public Response.ResponseBuilder entity(Object entity) { this.entity = entity; return this; }
    @Override public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) { this.entity = entity; return this; }
    @Override public Response.ResponseBuilder type(MediaType type) { return this; }
    @Override public Response.ResponseBuilder type(String type) { return this; }
    @Override public Response.ResponseBuilder allow(String... methods) { return this; }
    @Override public Response.ResponseBuilder allow(Set<String> methods) { return this; }
    @Override public Response.ResponseBuilder cacheControl(CacheControl cacheControl) { return this; }
    @Override public Response.ResponseBuilder encoding(String encoding) { return this; }
    @Override public Response.ResponseBuilder header(String name, Object value) { return this; }
    @Override public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) { return this; }
    @Override public Response.ResponseBuilder language(String language) { return this; }
    @Override public Response.ResponseBuilder language(Locale language) { return this; }
    @Override public Response.ResponseBuilder variant(Variant variant) { return this; }
    @Override public Response.ResponseBuilder contentLocation(URI location) { return this; }
    @Override public Response.ResponseBuilder cookie(NewCookie... cookies) { return this; }
    @Override public Response.ResponseBuilder expires(Date expires) { return this; }
    @Override public Response.ResponseBuilder lastModified(Date lastModified) { return this; }
    @Override public Response.ResponseBuilder location(URI location) { return this; }
    @Override public Response.ResponseBuilder tag(EntityTag tag) { return this; }
    @Override public Response.ResponseBuilder tag(String tag) { return this; }
    @Override public Response.ResponseBuilder variants(Variant... variants) { return this; }
    @Override public Response.ResponseBuilder variants(List<Variant> variants) { return this; }
    @Override public Response.ResponseBuilder links(Link... links) { return this; }
    @Override public Response.ResponseBuilder link(URI uri, String rel) { return this; }
    @Override public Response.ResponseBuilder link(String uri, String rel) { return this; }
}
