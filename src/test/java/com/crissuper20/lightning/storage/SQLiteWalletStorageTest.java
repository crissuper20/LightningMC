package com.crissuper20.lightning.storage;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WalletManager.WalletData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class SQLiteWalletStorageTest {

    @Mock
    private LightningPlugin plugin;

    @TempDir
    Path tempDir;

    private SQLiteWalletStorage storage;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TestLogger"));

        storage = new SQLiteWalletStorage(plugin);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        storage.shutdown();
    }

    @Test
    void testSaveAndLoadWallet() throws ExecutionException, InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        WalletData data = new WalletData(
            "test-wallet-id",
            "test-admin-key",
            "test-invoice-key",
            "TestPlayer"
        );
        data.playerUuid = playerUuid;

        // Save wallet
        storage.saveWallet(data).get();

        // Load wallet
        WalletData loaded = storage.loadWallet(playerUuid).get();

        assertNotNull(loaded);
        assertEquals(data.walletId, loaded.walletId);
        assertEquals(data.adminKey, loaded.adminKey);
        assertEquals(data.invoiceKey, loaded.invoiceKey);
        assertEquals(data.playerUuid, loaded.playerUuid);
        assertEquals(data.ownerName, loaded.ownerName);
    }

    @Test
    void testLoadNonExistentWallet() throws ExecutionException, InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        WalletData loaded = storage.loadWallet(playerUuid).get();
        assertNull(loaded);
    }

    @Test
    void testUpdateWallet() throws ExecutionException, InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        WalletData data = new WalletData(
            "wallet-1",
            "admin-1",
            "invoice-1",
            "Player1"
        );
        data.playerUuid = playerUuid;

        storage.saveWallet(data).get();

        WalletData newData = new WalletData(
            "wallet-2",
            "admin-2",
            "invoice-2",
            "Player1"
        );
        newData.playerUuid = playerUuid;

        storage.saveWallet(newData).get();

        WalletData loaded = storage.loadWallet(playerUuid).get();
        assertEquals("wallet-2", loaded.walletId);
        assertEquals("admin-2", loaded.adminKey);
    }
}
