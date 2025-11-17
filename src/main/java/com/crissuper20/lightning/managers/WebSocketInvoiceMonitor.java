package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.util.DebugLogger;
import com.google.gson.*;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketInvoiceMonitor {

    public interface InvoiceListener {
        void onInvoicePaid(String checkingId, String paymentHash, long amountMsat, String walletId);
    }

    private final LightningPlugin plugin;
    private final InvoiceListener listener;
    private final HttpClient client;
    private final String websocketBaseUrl;
    private final DebugLogger debug;

    // walletId -> monitor session
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WebSocketInvoiceMonitor(LightningPlugin plugin, String restBaseUrl, InvoiceListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.debug = plugin.getDebugLogger().withPrefix("InvoiceWS");
        this.websocketBaseUrl = deriveWebSocketBase(restBaseUrl);

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Main method used by WalletManager to register new wallets.
     */
    public void watchWallet(String walletId, String invoiceKey) {
        if (walletId == null || invoiceKey == null || walletId.isBlank() || invoiceKey.isBlank()) {
            plugin.getLogger().warning("Cannot watch wallet: walletId or invoiceKey is empty.");
            return;
        }

        debug.info("Registering websocket watcher wallet=" + walletId + " invoiceKey=" + maskKey(invoiceKey));
        sessions.computeIfAbsent(walletId, id -> {
            WebSocketSession s = new WebSocketSession(walletId, invoiceKey);
            s.connect();
            return s;
        });
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public void forceReconnectNow() {
        sessions.values().forEach(WebSocketSession::forceReconnect);
    }

    /**
     * Stop all monitors (plugin disable)
     */
    public void shutdown() {
        for (WebSocketSession s : sessions.values()) {
            s.close();
        }
        sessions.clear();
    }

// ============================================================
//                INTERNAL SESSION PER WALLET
// ============================================================

    private class WebSocketSession implements WebSocket.Listener {

        private final String walletId;
        private final String invoiceKey;
        private volatile WebSocket socket;
        private final AtomicBoolean connecting = new AtomicBoolean(false);
        private final ScheduledExecutorService scheduler;

        public WebSocketSession(String walletId, String invoiceKey) {
            this.walletId = walletId;
            this.invoiceKey = invoiceKey;

            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("InvoiceWS-" + walletId);
                return t;
            });
        }

        public void connect() {
            if (connecting.getAndSet(true)) return;

            scheduler.execute(() -> {
                try {
                    String url = websocketBaseUrl + "/api/v1/ws/" + invoiceKey;
                    debug.info("Connecting WebSocket for wallet=" + walletId + " url=" + url);

                    socket = client.newWebSocketBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .buildAsync(URI.create(url), this)
                            .join();

                    connecting.set(false);
                } catch (CompletionException e) {
                    logHandshakeFailure(e);
                    connecting.set(false);
                    scheduleReconnect();
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to connect WebSocket for wallet " + walletId + ": " + e.getMessage());
                    connecting.set(false);
                    scheduleReconnect();
                }
            });
        }

        private void logHandshakeFailure(CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof WebSocketHandshakeException handshake) {
                HttpResponse<?> response = handshake.getResponse();
                if (response != null) {
                    plugin.getLogger().warning(
                            "Handshake failed for wallet " + walletId +
                                    " (HTTP " + response.statusCode() + ") body=" + response.body()
                    );
                    return;
                }
            }
            plugin.getLogger().warning("WebSocket handshake failed for wallet " + walletId + ": " + exception.getMessage());
        }

        public void close() {
            try {
                if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin_shutdown");
            } catch (Exception ignored) {
            }

            scheduler.shutdownNow();
        }

        public void forceReconnect() {
            scheduler.execute(() -> {
                try {
                    if (socket != null) {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "force_reconnect");
                    }
                } catch (Exception ignored) {
                } finally {
                    connecting.set(false);
                    connect();
                }
            });
        }

        private void scheduleReconnect() {
            scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
        }

// ============================================================
//                    LISTENER CALLBACKS
// ============================================================

        @Override
        public void onOpen(WebSocket webSocket) {
            debug.info("WebSocket opened for wallet=" + walletId);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence message, boolean last) {
            if (debug.isDebugEnabled()) {
                debug.debug("WS message wallet=" + walletId + " payload=" + trimPayload(message));
            }
            handleMessage(message.toString());
            return WebSocket.Listener.super.onText(webSocket, message, last);
        }

        private void handleMessage(String json) {
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                if (!root.has("payment")) {
                    return; // Ignore non-payment events
                }

                JsonObject pay = root.getAsJsonObject("payment");

                String checkingId = pay.get("checking_id").getAsString();
                String paymentHash = pay.has("payment_hash") ? pay.get("payment_hash").getAsString() : checkingId;
                long amountMsat = pay.get("amount").getAsLong();

                // Dispatch to Bukkit main thread
                Bukkit.getScheduler().runTask(plugin, () ->
                    listener.onInvoicePaid(checkingId, paymentHash, amountMsat, walletId)
                );

            } catch (Exception e) {
                debug.error("Failed to parse LNbits WebSocket message", e);
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            debug.error("WebSocket error for wallet=" + walletId, error);
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            debug.warn("WebSocket closed wallet=" + walletId + " status=" + status + " reason=" + reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, status, reason);
        }
    }

    private String deriveWebSocketBase(String restBaseUrl) {
        String base = (restBaseUrl == null || restBaseUrl.isBlank()) ? "http://127.0.0.1:5000" : restBaseUrl;
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }

        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        try {
            URI uri = URI.create(base);
            String scheme = uri.getScheme().equalsIgnoreCase("https") ? "wss" : "ws";
            String authority = uri.getAuthority();
            String path = uri.getPath();
            if (path == null) {
                path = "";
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String derived = scheme + "://" + authority + path;
            debug.info("Derived websocket base=" + derived + " from rest=" + restBaseUrl);
            return derived;
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid LNbits base URL '" + restBaseUrl + "', falling back to ws://127.0.0.1:5000");
            debug.error("Base URL parse failure", ex);
            return "ws://127.0.0.1:5000";
        }
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "<none>";
        }
        if (key.length() <= 8) {
            return key.charAt(0) + "***" + key.charAt(key.length() - 1);
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    private String trimPayload(CharSequence payload) {
        if (payload == null) {
            return "<null>";
        }
        String text = payload.toString();
        return text.length() > 256 ? text.substring(0, 256) + "…" : text;
    }
}
