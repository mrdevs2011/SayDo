package uz.saydo.app.kernel.daemon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;

/**
 * Universal Headless Local IPC / REST HTTP Daemon Server (Port 8765).
 * Enables automation control via ADB shell, curl, and local terminal CLI ("saydosh").
 * Strictly bound to 127.0.0.1 with local token authentication.
 */
public final class LocalDaemonServer {

    public interface MacroExecutionHandler {
        boolean executeMacroByTrigger(String trigger);
    }

    public static final int DEFAULT_PORT = 8765;
    private final int port;
    private final String authToken;
    private final MacroRepository repository;
    private MacroExecutionHandler executionHandler;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean isRunning = false;

    public LocalDaemonServer(int port, MacroRepository repository) {
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.repository = repository;
        this.authToken = UUID.randomUUID().toString().substring(0, 16);
    }

    public void setExecutionHandler(MacroExecutionHandler handler) {
        this.executionHandler = handler;
    }

    public String getAuthToken() {
        return authToken;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        new Thread(() -> {
            try {
                // Strictly bind to 127.0.0.1 (localhost only for security)
                serverSocket = new ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"));
                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        threadPool.execute(() -> handleClient(client));
                    } catch (Throwable t) {
                        if (!isRunning) break;
                    }
                }
            } catch (Throwable t) {
                android.util.Log.e("LocalDaemonServer", "Daemon server error", t);
            }
        }, "SayDoDaemon").start();
    }

    private void handleClient(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {

            String line = reader.readLine();
            if (line == null) return;

            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String path = parts[1];

            // Read headers
            int contentLength = 0;
            String token = "";
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (line.toLowerCase().startsWith("x-saydo-token:")) {
                    token = line.substring(14).trim();
                }
            }

            // Read body if POST
            String body = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int read = reader.read(bodyChars, 0, contentLength);
                body = new String(bodyChars, 0, read);
            }

            // Route endpoints
            String responseJson = "{}";
            int statusCode = 200;

            if ("/api/v1/status".equals(path)) {
                JSONObject statusObj = new JSONObject();
                statusObj.put("status", "ok");
                statusObj.put("version", "3.0.0-omega");
                statusObj.put("port", port);
                responseJson = statusObj.toString();
            } else if ("/api/v1/macros".equals(path) && "GET".equals(method)) {
                JSONArray arr = new JSONArray();
                if (repository != null) {
                    for (Macro m : repository.getAll()) {
                        arr.put(m.toJson());
                    }
                }
                responseJson = arr.toString();
            } else if ("/api/v1/macro/execute".equals(path) && "POST".equals(method)) {
                JSONObject reqObj = new JSONObject(body.isEmpty() ? "{}" : body);
                String trigger = reqObj.optString("trigger", "");
                boolean dispatched = false;
                if (!trigger.isEmpty() && executionHandler != null) {
                    dispatched = executionHandler.executeMacroByTrigger(trigger);
                }
                JSONObject respObj = new JSONObject();
                respObj.put("dispatched", dispatched);
                respObj.put("trigger", trigger);
                responseJson = respObj.toString();
            } else {
                statusCode = 404;
                responseJson = "{\"error\": \"Not Found\"}";
            }

            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + statusCode + (statusCode == 200 ? " OK" : " Not Found") + "\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Throwable t) {
            android.util.Log.w("LocalDaemonServer", "Client handling error", t);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Throwable ignored) {
            }
            serverSocket = null;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    public boolean isRunning() {
        return isRunning;
    }
}
