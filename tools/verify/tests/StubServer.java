package dev.notune.transcribe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A minimal OpenAI-compatible chat-completions server for the tests.
 *
 * Uses the JDK's own {@code com.sun.net.httpserver}, so the tests need no
 * network access, no extra dependency and no subprocess — everything runs
 * in-process on an ephemeral loopback port and is torn down per test.
 *
 * The behaviour is a lambda supplied per test, so this class never grows a mode
 * enum: a test asks for a good reply, an HTTP error, a hang, or a malformed body
 * by writing that response directly.
 *
 * Lives in {@code dev.notune.transcribe} so the tests can reach
 * {@link PostProcessClient}'s package-private helpers.
 */
final class StubServer implements AutoCloseable {

    /** What the last request carried, for asserting on what the client sent. */
    static final class Captured {
        String method;
        String body;
        String authorization;
        String contentType;
        String accept;
    }

    interface Handler {
        void handle(HttpExchange exchange, Captured captured) throws IOException;
    }

    private final HttpServer server;
    private final AtomicReference<Captured> last = new AtomicReference<>();

    StubServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            Captured c = new Captured();
            c.method = exchange.getRequestMethod();
            c.authorization = exchange.getRequestHeaders().getFirst("Authorization");
            c.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            c.accept = exchange.getRequestHeaders().getFirst("Accept");
            c.body = readAll(exchange.getRequestBody());
            last.set(c);
            try {
                handler.handle(exchange, c);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(null);
        server.start();
    }

    /** Base URL to hand to the client under test. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    Captured lastRequest() {
        return last.get();
    }

    /** Replies 200 with a normal chat-completions envelope wrapping {@code content}. */
    static void replyContent(HttpExchange exchange, String content) throws IOException {
        String json = "{\"id\":\"stub\",\"object\":\"chat.completion\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + escape(content) + "\"},\"finish_reason\":\"stop\"}]}";
        replyRaw(exchange, 200, json);
    }

    static void replyRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
