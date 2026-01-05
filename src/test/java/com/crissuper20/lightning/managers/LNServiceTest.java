package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.util.DebugLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class LNServiceTest {

    @Mock private LightningPlugin plugin;
    @Mock private WalletManager walletManager;
    @Mock private WebSocketInvoiceMonitor invoiceMonitor;
    @Mock private DebugLogger debugLogger;
    @Mock private FileConfiguration config;
    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<String> httpResponse;

    private LNService lnService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(plugin.getDebugLogger()).thenReturn(debugLogger);
        when(debugLogger.withPrefix(anyString())).thenReturn(debugLogger);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TestLogger"));
        
        // Mock scheduler for health checks
        when(plugin.getScheduler()).thenReturn(mock(com.crissuper20.lightning.scheduler.CommonScheduler.class));

        when(config.getString("lnbits.host", "http://127.0.0.1:5000")).thenReturn("http://localhost:5000");
        when(config.getString("lnbits.adminKey", "")).thenReturn("adminKey");
        when(config.getInt("lnbits.invoice_expiry_seconds", 3600)).thenReturn(3600);

        // Use DI constructor
        lnService = new LNService(plugin, walletManager, invoiceMonitor, httpClient);
    }

    @Test
    void testConstructorRegistersConnectionListener() {
        verify(invoiceMonitor).addConnectionListener(any());
    }

    @Test
    void testCreateInvoiceSuccess() {
        String jsonResponse = "{\"payment_request\":\"lnbc123...\",\"payment_hash\":\"hash123\",\"checking_id\":\"check123\"}";
        
        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        LNService.LNResponse<LNService.Invoice> response = 
            lnService.createInvoice("invoiceKey123", 1000, "Test memo").join();

        assertTrue(response.success);
        assertNotNull(response.data);
        assertEquals("hash123", response.data.paymentHash);
        assertEquals("lnbc123...", response.data.bolt11);
    }

    @Test
    void testCreateInvoiceFailure() {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        LNService.LNResponse<LNService.Invoice> response = 
            lnService.createInvoice("invoiceKey123", 1000, "Test memo").join();

        assertFalse(response.success);
        assertNull(response.data);
        assertTrue(response.error.contains("500"));
    }

    @Test
    void testCreateInvoiceForPlayerSuccess() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        
        when(walletManager.hasWallet(player)).thenReturn(true);
        when(walletManager.getPlayerInvoiceKey(player)).thenReturn("playerInvoiceKey");
        when(walletManager.getWalletId(player)).thenReturn("walletId123");

        String jsonResponse = "{\"payment_request\":\"lnbc456...\",\"payment_hash\":\"hash456\",\"checking_id\":\"check456\"}";
        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        LNService.LNResponse<LNService.Invoice> response = 
            lnService.createInvoiceForPlayer(player, 500, "Player invoice").join();

        assertTrue(response.success);
        assertEquals("hash456", response.data.paymentHash);
    }

    @Test
    void testCreateInvoiceForPlayerNoWallet() {
        Player player = mock(Player.class);
        when(walletManager.hasWallet(player)).thenReturn(false);

        assertThrows(Exception.class, () -> 
            lnService.createInvoiceForPlayer(player, 500, "Test").join()
        );
    }

    @Test
    void testPayInvoiceSuccess() {
        String jsonResponse = "{\"payment_hash\":\"paid123\",\"checking_id\":\"check123\"}";
        
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        var response = lnService.payInvoice("adminKey123", "lnbc1000...").join();

        assertTrue(response.success);
        assertNotNull(response.data);
    }

    @Test
    void testPayInvoiceInsufficientFunds() {
        when(httpResponse.statusCode()).thenReturn(400);
        when(httpResponse.body()).thenReturn("Insufficient balance");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        var response = lnService.payInvoice("adminKey123", "lnbc1000...").join();

        assertFalse(response.success);
        assertTrue(response.error.contains("400"));
    }

    @Test
    void testIsHealthyInitialState() {
        assertTrue(lnService.isHealthy());
    }

    @Test
    void testGetBackendName() {
        String name = lnService.getBackendName();
        assertTrue(name.contains("LNbits"));
        assertTrue(name.contains("localhost:5000"));
    }
}
