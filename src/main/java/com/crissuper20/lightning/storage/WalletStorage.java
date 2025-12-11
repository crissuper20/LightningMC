package com.crissuper20.lightning.storage;

import com.crissuper20.lightning.managers.WalletManager.WalletData;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface WalletStorage {
    void init();
    void shutdown();
    
    CompletableFuture<Void> saveWallet(WalletData data);
    CompletableFuture<WalletData> loadWallet(UUID playerUuid);
    CompletableFuture<Boolean> hasWallet(UUID playerUuid);
    CompletableFuture<List<WalletData>> loadAllWallets();
    
    // For migration
    void saveWalletSync(WalletData data);
}
