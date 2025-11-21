package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNDHubManager;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.QRMapGenerator;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced /wallet command with LNDHub support
 * 
 * Commands:
 *   /wallet - Show wallet info
 *   /wallet givelogin - Get LNDHub connection info
 *   /wallet givelogin qr - Get LNDHub QR code
 *   /wallet givelogin copy - Copy LNDHub URI to clipboard
 *   /wallet givelogin test - Test LNDHub connection
 */
public class WalletCommand implements CommandExecutor, TabCompleter {
    
    private final LightningPlugin plugin; 
    private final WalletManager walletManager;
    private final LNDHubManager lndhubManager;

    public WalletCommand(LightningPlugin plugin, WalletManager walletManager) { 
        this.plugin = plugin;
        this.walletManager = walletManager;
        this.lndhubManager = new LNDHubManager(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        // Handle subcommands
        if (args.length > 0) {
            String subcommand = args[0].toLowerCase();
            
            if (subcommand.equals("givelogin")) {
                return handleGiveLogin(player, args);
            }
        }

        // Default: show wallet info
        if (walletManager.hasWallet(player)) {
            player.sendMessage(plugin.formatMessage("§7Fetching wallet information..."));
            displayWalletInfo(player);
        } else {
            player.sendMessage(plugin.formatMessage("§7Creating your Lightning wallet... please wait."));
            
            walletManager.getOrCreateWallet(player).thenAccept(wallet -> {
                if (wallet.has("id")) {
                    String walletId = wallet.get("id").getAsString();
                    plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                        p.sendMessage(plugin.formatSuccess("§6Wallet created successfully!"));
                        p.sendMessage(plugin.formatMessage("§7Wallet ID: §f" + walletId));
                        p.sendMessage(plugin.formatMessage("§7Use §f/wallet §7to check your info"));
                        p.sendMessage(plugin.formatMessage("§7Use §f/wallet givelogin §7to connect Zeus wallet"));
                    });
                } else {
                    plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p ->
                        p.sendMessage(plugin.formatError("Failed to create your wallet. Check logs."))
                    );
                }
            }).exceptionally(ex -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p ->
                    p.sendMessage(plugin.formatError("Error creating wallet: " + ex.getMessage()))
                );
                plugin.getDebugLogger().error("Wallet creation failed for " + player.getName(), ex);
                return null;
            });
        }

        return true;
    }
    
    /**
     * Handle /wallet givelogin subcommands
     */
    private boolean handleGiveLogin(Player player, String[] args) {
        if (!walletManager.hasWallet(player)) {
            player.sendMessage("§cYou need to create a wallet first!");
            player.sendMessage("§7Use §f/invoice §7to create your first invoice");
            return true;
        }
        
        // Determine action
        String action = args.length > 1 ? args[1].toLowerCase() : "copy";
        
        switch (action) {
            case "qr":
                return handleGiveLoginQR(player);
            default:
                return handleGiveLoginCopy(player);
        }
    }
    
    /**
     * Show LNDHub connection info
     */
    private boolean handleGiveLoginInfo(Player player) {
        player.sendMessage("§eGenerating Zeus wallet connection info...");
        
        lndhubManager.generateLNDHubURI(player)
            .thenAccept(uri -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§6§l=== Zeus Wallet Connection ===");
                    p.sendMessage("");
                    p.sendMessage("§7Connect your Zeus wallet to use your");
                    p.sendMessage("§7in-game Lightning wallet on your phone!");
                    p.sendMessage("");
                    p.sendMessage("§eQuick Actions:");
                    
                    // Copy button with hover showing full URI
                    Component copyButton = Component.text()
                        .append(Component.text("  [Copy Login Info]", NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD)
                            .clickEvent(ClickEvent.copyToClipboard(uri))
                            .hoverEvent(HoverEvent.showText(
                                Component.text()
                                    .append(Component.text("Click to copy to clipboard\n", NamedTextColor.GRAY))
                                    .append(Component.text("\nConnection String:\n", NamedTextColor.YELLOW))
                                    .append(Component.text(uri, NamedTextColor.WHITE, TextDecoration.ITALIC))
                                    .append(Component.text("\n\n⚠ Keep private!", NamedTextColor.RED, TextDecoration.BOLD))
                                    .build()
                            ))
                        )
                        .build();
                    
                    p.sendMessage(copyButton);
                    p.sendMessage("");
                    
                    p.sendMessage("§eSteps:");
                    p.sendMessage("§71. Install Zeus from https://zeusln.com");
                    p.sendMessage("§72. Open Zeus → Add Node → LNDHub");
                    p.sendMessage("§73. Click the copy button above");
                    p.sendMessage("§74. Paste into Zeus");
                    p.sendMessage("");
                    p.sendMessage("§7Or use: §f/wallet givelogin qr §7for QR code");
                    p.sendMessage("");
                    p.sendMessage("§c Never share your login info!!");
                });
            })
            .exceptionally(ex -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§cFailed to generate connection info: " + ex.getMessage());
                    plugin.getDebugLogger().error("LNDHub info generation failed", ex);
                });
                return null;
            });
        
        return true;
    }
    
    /**
     * Generate LNDHub QR code
     */
    private boolean handleGiveLoginQR(Player player) {
        player.sendMessage("§eGenerating Zeus wallet QR code...");
        
        lndhubManager.generateLNDHubURI(player)
            .thenAccept(uri -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    // Generate QR code map
                    try {
                        boolean success = QRMapGenerator.giveMap(p, uri, "lndhub");
                        
                        if (success) {
                            p.sendMessage("§a✓ LNDHub QR code added to inventory!");
                            p.sendMessage("");
                            p.sendMessage("§7Scan this QR code with Zeus wallet:");
                            p.sendMessage("§71. Open Zeus app");
                            p.sendMessage("§72. Tap 'Add Node'");
                            p.sendMessage("§73. Select 'Scan LNDHub QR'");
                            p.sendMessage("§74. Scan the map in your inventory");
                            p.sendMessage("");
                            p.sendMessage("§c⚠ Keep this QR code private!");
                        } else {
                            p.sendMessage("§c✗ Failed to generate QR code");
                            p.sendMessage("§7Use §f/wallet givelogin copy §7instead");
                        }
                    } catch (Exception e) {
                        p.sendMessage("§c✗ Error generating QR code");
                        plugin.getDebugLogger().error("QR generation error", e);
                    }
                });
            })
            .exceptionally(ex -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§cFailed to generate QR code: " + ex.getMessage());
                });
                return null;
            });
        
        return true;
    }
    
    /**
     * Copy LNDHub URI to clipboard
     */
    private boolean handleGiveLoginCopy(Player player) {
        player.sendMessage("§eGenerating connection string...");
        
        lndhubManager.generateLNDHubURI(player)
            .thenAccept(uri -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§a✓ Connection string generated!");
                    p.sendMessage("");
                    
                    // Create clickable copy button with hover showing full URI
                    Component copyButton = Component.text()
                        .append(Component.text("[Copy Login Info]", NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD)
                            .clickEvent(ClickEvent.copyToClipboard(uri))
                            .hoverEvent(HoverEvent.showText(
                                Component.text()
                                    .append(Component.text("Click to copy\n", NamedTextColor.GRAY))
                                    .append(Component.text("\nFull URI:\n", NamedTextColor.YELLOW))
                                    .append(Component.text(uri, NamedTextColor.WHITE))
                                    .append(Component.text("\n\n⚠ Keep this private!", NamedTextColor.RED))
                                    .build()
                            ))
                        )
                        .build();
                    
                    p.sendMessage(copyButton);
                    
                    p.sendMessage("");
                    p.sendMessage("§7Paste this into Zeus:");
                    p.sendMessage("§71. Open Zeus → Add Node");
                    p.sendMessage("§72. Select 'LNDHub'");
                    p.sendMessage("§73. Paste the connection string");
                    p.sendMessage("");
                    p.sendMessage("§c⚠ Keep this private! Anyone with this");
                    p.sendMessage("§c   can access your wallet!");
                });
            })
            .exceptionally(ex -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§cFailed to generate connection string: " + ex.getMessage());
                });
                return null;
            });
        
        return true;
    }
    
    /**
     * Test LNDHub connection
     */
    private boolean handleGiveLoginTest(Player player) {
        player.sendMessage("§eTesting LNDHub connection...");
        
        lndhubManager.testLNDHubConnection(player)
            .thenAccept(success -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    if (success) {
                        p.sendMessage("§a✓ LNDHub connection test successful!");
                        p.sendMessage("§7Your wallet is ready to use with Zeus");
                    } else {
                        p.sendMessage("§c✗ LNDHub connection test failed");
                        p.sendMessage("§7Please check:");
                        p.sendMessage("§7  • LNbits server is reachable");
                        p.sendMessage("§7  • Your wallet is properly created");
                        p.sendMessage("§7  • Network connectivity");
                    }
                });
            })
            .exceptionally(ex -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§c✗ Connection test error: " + ex.getMessage());
                });
                return null;
            });
        
        return true;
    }
    
    /**
     * Display wallet information
     */
    private void displayWalletInfo(Player player) {
        CompletableFuture<JsonObject> walletFuture = walletManager.getOrCreateWallet(player);
        CompletableFuture<Long> balanceFuture = walletManager.getBalance(player);
        
        walletFuture.thenCombine(balanceFuture, (wallet, balance) -> {
            String adminKey = walletManager.getPlayerAdminKey(player);
            String walletId = wallet.get("id").getAsString();
            String formattedBalance = walletManager.formatBalance(balance);

            plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                p.sendMessage(plugin.formatMessage("§6§l=== Your Lightning Wallet ==="));
                p.sendMessage(plugin.formatMessage("§7Wallet ID: §f" + walletId));
                p.sendMessage(plugin.formatMessage("§7Balance: §f" + formattedBalance));
                p.sendMessage("");
                p.sendMessage(plugin.formatMessage("§eCommands:"));
                p.sendMessage(plugin.formatMessage("§f/balance §7- Check balance"));
                p.sendMessage(plugin.formatMessage("§f/invoice <amount> §7- Create invoice"));
                p.sendMessage(plugin.formatMessage("§f/pay <bolt11> §7- Send payment"));
                p.sendMessage(plugin.formatMessage("§f/wallet givelogin §7- Connect Zeus wallet"));
                p.sendMessage(plugin.formatMessage("§6========================"));
            });
            return null;
            
        }).exceptionally(ex -> {
            plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                p.sendMessage(plugin.formatError("Error fetching wallet data: " + ex.getMessage()));
            });
            plugin.getDebugLogger().error("Wallet info display failed for " + player.getName(), ex);
            return null;
        });
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }
        
        if (args.length == 1) {
            return Arrays.asList("givelogin");
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("givelogin")) {
            return Arrays.asList("qr", "copy", "test");
        }
        
        return Collections.emptyList();
    }
}