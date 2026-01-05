package com.crissuper20.lightning.storage;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WalletManager.WalletData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SQLiteWalletStorage implements WalletStorage {

    private final LightningPlugin plugin;
    private HikariDataSource dataSource;
    
    // Dedicated executor for database operations to avoid starving ForkJoinPool
    private final ExecutorService dbExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "LightningDB-Worker");
        t.setDaemon(true);
        return t;
    });

    public SQLiteWalletStorage(LightningPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(10);
        config.setPoolName("LightningWalletPool");
        
        // SQLite specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("CREATE TABLE IF NOT EXISTS wallets (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "wallet_id VARCHAR(64) NOT NULL, " +
                    "admin_key VARCHAR(64) NOT NULL, " +
                    "invoice_key VARCHAR(64) NOT NULL, " +
                    "owner_name VARCHAR(64) NOT NULL" +
                    ")");
            
            // Create index on wallet_id for faster lookups during payment notifications
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_wallet_id ON wallets(wallet_id)");
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void shutdown() {
        // Shutdown executor gracefully
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public CompletableFuture<Void> saveWallet(WalletData data) {
        return CompletableFuture.runAsync(() -> saveWalletSync(data), dbExecutor);
    }

    @Override
    public void saveWalletSync(WalletData data) {
        String sql = "INSERT OR REPLACE INTO wallets (player_uuid, wallet_id, admin_key, invoice_key, owner_name) VALUES (?, ?, ?, ?, ?)";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setString(1, data.playerUuid.toString());
            ps.setString(2, data.walletId);
            ps.setString(3, data.adminKey);
            ps.setString(4, data.invoiceKey);
            ps.setString(5, data.ownerName);
            
            ps.executeUpdate();
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save wallet for " + data.ownerName + ": " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<WalletData> loadWallet(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM wallets WHERE player_uuid = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, playerUuid.toString());
                
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        WalletData data = new WalletData(
                                rs.getString("wallet_id"),
                                rs.getString("admin_key"),
                                rs.getString("invoice_key"),
                                rs.getString("owner_name")
                        );
                        data.playerUuid = playerUuid;
                        return data;
                    }
                }
                
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load wallet for " + playerUuid + ": " + e.getMessage());
            }
            return null;
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<Boolean> hasWallet(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM wallets WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }, dbExecutor);
    }

    @Override
    public CompletableFuture<List<WalletData>> loadAllWallets() {
        return CompletableFuture.supplyAsync(() -> {
            List<WalletData> results = new ArrayList<>();
            String sql = "SELECT * FROM wallets";
            
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    WalletData data = new WalletData(
                            rs.getString("wallet_id"),
                            rs.getString("admin_key"),
                            rs.getString("invoice_key"),
                            rs.getString("owner_name")
                    );
                    data.playerUuid = UUID.fromString(rs.getString("player_uuid"));
                    results.add(data);
                }
                
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load all wallets: " + e.getMessage());
            }
            return results;
        }, dbExecutor);
    }
}
