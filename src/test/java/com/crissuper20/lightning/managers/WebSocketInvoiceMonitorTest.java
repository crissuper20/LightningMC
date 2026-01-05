package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.util.DebugLogger;
import com.crissuper20.lightning.scheduler.CommonScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSocketInvoiceMonitorTest {

    @Mock private LightningPlugin plugin;
    @Mock private DebugLogger debugLogger;
    @Mock private HttpClient httpClient;
    @Mock private WebSocket.Builder wsBuilder;
    @Mock private WebSocket webSocket;
    @Mock private WebSocketInvoiceMonitor.InvoiceListener invoiceListener;
    @Mock private CommonScheduler scheduler;

    private WebSocketInvoiceMonitor monitor;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        when(plugin.getDebugLogger()).thenReturn(debugLogger);
        when(debugLogger.withPrefix(anyString())).thenReturn(debugLogger);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TestLogger"));
        when(plugin.getScheduler()).thenReturn(scheduler);
        
        // Mock scheduler to run tasks immediately
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(scheduler).runTask(any(Runnable.class));

        // Mock HttpClient and WebSocket
        when(httpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        when(wsBuilder.connectTimeout(any())).thenReturn(wsBuilder);
        when(wsBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                .thenAnswer(invocation -> {
                    WebSocket.Listener listener = invocation.getArgument(1);
                    // Simulate onOpen
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                });

        monitor = new WebSocketInvoiceMonitor(plugin, "http://localhost:5000", invoiceListener);

        // Replace HttpClient with mock
        Field clientField = WebSocketInvoiceMonitor.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(monitor, httpClient);
    }

    @Test
    void testWatchWalletConnects() {
        monitor.watchWallet("wallet1", "invoiceKey1");
        
        // Wait a bit for async execution
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        verify(httpClient).newWebSocketBuilder();
        verify(wsBuilder).buildAsync(eq(URI.create("ws://localhost:5000/api/v1/ws/invoiceKey1")), any());
    }

    @Test
    void testInvoicePaid() throws Exception {
        // Capture the listener passed to buildAsync
        CompletableFuture<WebSocket.Listener> listenerFuture = new CompletableFuture<>();
        
        when(wsBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                .thenAnswer(invocation -> {
                    WebSocket.Listener listener = invocation.getArgument(1);
                    listenerFuture.complete(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                });

        monitor.watchWallet("wallet1", "invoiceKey1");
        
        // Wait for connection
        WebSocket.Listener listener = listenerFuture.get();
        
        // Simulate incoming message
        String json = "{\"payment\":{\"checking_id\":\"check1\",\"payment_hash\":\"hash1\",\"amount\":1000}}";
        listener.onText(webSocket, json, true);

        // Verify callback
        verify(invoiceListener).onInvoicePaid("check1", "hash1", 1000L, "wallet1");
    }
}
