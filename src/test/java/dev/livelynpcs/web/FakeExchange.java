package dev.livelynpcs.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** A request and its answer in memory, for testing handlers without a server. */
final class FakeExchange extends HttpExchange {

    private final String method;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final InputStream body;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, Object> attributes = new HashMap<>();
    private int status = -1;
    private long length;

    FakeExchange(String method, String path, String token, String body) {
        this.method = method;
        this.uri = URI.create(path);
        if (token != null) requestHeaders.add("Authorization", "Bearer " + token);
        this.body = new ByteArrayInputStream(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    int status() {
        return status;
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    String text() {
        return out.toString(StandardCharsets.UTF_8);
    }

    JsonElement json() {
        return JsonParser.parseString(text());
    }

    JsonObject object() {
        return json().getAsJsonObject();
    }

    /** The error message of an error answer. */
    String error() {
        return object().get("error").getAsString();
    }

    long declaredLength() {
        return length;
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return method; }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public void close() { }
    @Override public InputStream getRequestBody() { return body; }
    @Override public OutputStream getResponseBody() { return out; }

    @Override
    public void sendResponseHeaders(int code, long responseLength) {
        if (status != -1) throw new IllegalStateException("headers sent twice");
        status = code;
        length = responseLength;
    }

    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 50000); }
    @Override public int getResponseCode() { return status; }
    @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8193); }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    @Override public void setStreams(InputStream i, OutputStream o) { }
    @Override public HttpPrincipal getPrincipal() { return null; }
}
