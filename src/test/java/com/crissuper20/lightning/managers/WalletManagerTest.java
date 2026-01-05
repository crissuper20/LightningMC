package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.storage.WalletStorage;
import com.crissuper20.lightning.util.DebugLogger;
import com.google.gson.Gson;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked") // generic type matching
class WalletManagerTest {

    @Mock private LightningPlugin plugin;
    @Mock private Server server;
    @Mock private PluginManager pluginManager;
    @Mock private FileConfiguration config;
    @Mock private DebugLogger debugLogger;
    @Mock private WalletStorage storage;
    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<String> httpResponse;

    private WalletManager walletManager;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDebugLogger()).thenReturn(debugLogger);
        when(debugLogger.withPrefix(anyString())).thenReturn(debugLogger);
        when(plugin.getDataFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir")));
        when(plugin.getGson()).thenReturn(gson);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TestLogger"));

        // Mock config values
        when(config.getString("lnbits.host", "http://127.0.0.1:5000")).thenReturn("http://localhost:5000");
        when(config.getString("lnbits.adminKey")).thenReturn("master-key");

        walletManager = new WalletManager(plugin, storage, httpClient);
    }

    @Test
    void testCreateWalletSuccess() {
        String playerId = UUID.randomUUID().toString();
        String playerName = "TestPlayer";
        String responseBody = "{\"id\":\"wallet123\",\"adminkey\":\"admin123\",\"inkey\":\"in123\",\"name\":\"TestPlayer\"}";

        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn(responseBody);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        CompletableFuture<Boolean> future = walletManager.createWalletForAsync(playerId, playerName);
        
        assertTrue(future.join());
        assertTrue(walletManager.hasWallet(playerId));
        
        verify(storage).saveWallet(any());
    }

    @Test
    void testCreateWalletFailure() {
        String playerId = UUID.randomUUID().toString();
        String playerName = "TestPlayer";

        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

        CompletableFuture<Boolean> future = walletManager.createWalletForAsync(playerId, playerName);
        
        assertFalse(future.join());
        assertFalse(walletManager.hasWallet(playerId));
    }
    
    @Test
    @SuppressWarnings("unchecked")
    void testFetchBalance() {
        String playerId = UUID.randomUUID().toString();
        String playerName = "TestPlayer";
        
        // First create a wallet so it's in the internal map
        String createResponse = "{\"id\":\"w1\",\"adminkey\":\"a1\",\"inkey\":\"i1\",\"name\":\"TestPlayer\"}";
        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn(createResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));
        
        walletManager.createWalletForAsync(playerId, playerName).join();
        assertTrue(walletManager.hasWallet(playerId));

        // Now mock balance response
        String balanceResponse = "{\"balance\": 50000}"; // 50000 msat = 50 sat
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(balanceResponse);

        // Use the public getBalance method via a Player mock
        org.bukkit.entity.Player playerMock = mock(org.bukkit.entity.Player.class);
        when(playerMock.getUniqueId()).thenReturn(UUID.fromString(playerId));
        
        CompletableFuture<Long> future = walletManager.getBalance(playerMock);
        assertEquals(50L, future.join()); // 50000 msat / 1000 = 50 sat
    }
}
