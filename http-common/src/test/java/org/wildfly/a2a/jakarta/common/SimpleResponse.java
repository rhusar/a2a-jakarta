package org.wildfly.a2a.jakarta.common;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

class SimpleResponse extends Response {

    private final int status;
    private final Object entity;

    SimpleResponse(int status, Object entity) {
        this.status = status;
        this.entity = entity;
    }

    @Override public int getStatus() { return status; }
    @Override public StatusType getStatusInfo() { return Status.fromStatusCode(status); }
    @Override public Object getEntity() { return entity; }

    @Override public <T> T readEntity(Class<T> entityType) { throw new UnsupportedOperationException(); }
    @Override public <T> T readEntity(GenericType<T> entityType) { throw new UnsupportedOperationException(); }
    @Override public <T> T readEntity(Class<T> entityType, Annotation[] annotations) { throw new UnsupportedOperationException(); }
    @Override public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) { throw new UnsupportedOperationException(); }
    @Override public boolean hasEntity() { return entity != null; }
    @Override public boolean bufferEntity() { return false; }
    @Override public void close() {}
    @Override public MediaType getMediaType() { return null; }
    @Override public Locale getLanguage() { return null; }
    @Override public int getLength() { return -1; }
    @Override public Set<String> getAllowedMethods() { return Set.of(); }
    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public EntityTag getEntityTag() { return null; }
    @Override public Date getDate() { return null; }
    @Override public Date getLastModified() { return null; }
    @Override public URI getLocation() { return null; }
    @Override public Set<Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public Link getLink(String relation) { return null; }
    @Override public Link.Builder getLinkBuilder(String relation) { return null; }
    @Override public MultivaluedMap<String, Object> getMetadata() { return new MultivaluedHashMap<>(); }
    @Override public MultivaluedMap<String, String> getStringHeaders() { return new MultivaluedHashMap<>(); }
    @Override public String getHeaderString(String name) { return null; }
}
