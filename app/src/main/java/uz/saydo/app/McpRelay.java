package uz.saydo.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal JSON-over-HTTPS client for the SayDo relay (no extra dependencies). */
final class McpRelay {
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        JSONObject json() {
            try {
                return body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private final McpConfig cfg;

    McpRelay(McpConfig cfg) {
        this.cfg = cfg;
    }

    Response register() throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("deviceId", cfg.deviceId());
            b.put("deviceSecret", cfg.deviceSecret());
            b.put("clientTokenHash", McpConfig.sha256(cfg.clientToken()));
            b.put("name", cfg.name());
        } catch (Exception ignored) {
        }
        return post("/api/device/register", b, false, 20000);
    }

    /** Long-poll: waits up to ~25 s on the server for a command. */
    Response poll(JSONObject status) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("status", status);
        } catch (Exception ignored) {
        }
        return post("/api/device/poll", b, true, 45000);
    }

    Response result(JSONObject result) throws IOException {
        return post("/api/device/result", result, true, 30000);
    }

    private Response post(String path, JSONObject body, boolean auth, int readTimeoutMs) throws IOException {
        URL url = new URL(cfg.relay() + path);
        if (!"https".equals(url.getProtocol())) throw new IOException("relay must use https");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("user-agent", "SayDo-Android");
            if (auth) {
                c.setRequestProperty("x-device-id", cfg.deviceId());
                c.setRequestProperty("authorization", "Bearer " + cfg.deviceSecret());
            }
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? "" : readAll(in));
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 512 * 1024) break; // relay answers are small
            }
            return out.toString("UTF-8");
        }
    }
}
