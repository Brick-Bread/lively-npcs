package dev.livelynpcs.skin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Answers requests from a script, and remembers them: web lookups tested without a network. */
final class FakeHttp implements Http {

    record Call(String method, String url, Map<String, String> headers, byte[] body) {
        String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    final List<Call> calls = new ArrayList<>();
    private final Deque<Response> answers = new ArrayDeque<>();

    FakeHttp answer(int status, String json) {
        return answer(status, json, Map.of());
    }

    FakeHttp answer(int status, String json, Map<String, String> headers) {
        Map<String, String> lower = new HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v));
        answers.add(new Response(status, lower, json.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    @Override
    public Response get(String url, Map<String, String> headers, int maxBytes) {
        return next(new Call("GET", url, headers, null));
    }

    @Override
    public Response post(String url, Map<String, String> headers, byte[] body, int maxBytes) {
        return next(new Call("POST", url, headers, body));
    }

    private Response next(Call call) {
        calls.add(call);
        if (answers.isEmpty()) throw new AssertionError("no answer scripted for " + call.method() + " " + call.url());
        return answers.poll();
    }
}
