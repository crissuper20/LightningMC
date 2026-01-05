package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WalletManager;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;

class InvoiceCommandTest {

    @Mock private LightningPlugin plugin;
    @Mock private WalletManager walletManager;
    @Mock private Player player;
    @Mock private Command command;

    private InvoiceCommand invoiceCommand;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getWalletManager()).thenReturn(walletManager);
        when(plugin.formatError(anyString())).thenAnswer(i -> "Error: " + i.getArgument(0));
        when(plugin.formatMessage(anyString())).thenAnswer(i -> "Msg: " + i.getArgument(0));

        invoiceCommand = new InvoiceCommand(plugin);
    }

    @Test
    void testNoArgs() {
        when(walletManager.hasWallet(player)).thenReturn(true);
        invoiceCommand.onCommand(player, command, "invoice", new String[]{});
        // The mock formatError adds "Error: " prefix
        verify(player).sendMessage(eq("Error: Usage: /invoice <amount> [memo]"));
    }

    @Test
    void testNoWallet() {
        when(walletManager.hasWallet(player)).thenReturn(false);
        
        invoiceCommand.onCommand(player, command, "invoice", new String[]{"100"});
        
        verify(player).sendMessage(contains("You don't have a wallet"));
    }

    @Test
    void testInvalidAmount() {
        when(walletManager.hasWallet(player)).thenReturn(true);
        
        invoiceCommand.onCommand(player, command, "invoice", new String[]{"abc"});
        
        verify(player).sendMessage(contains("Invalid amount"));
    }

    @Test
    void testNegativeAmount() {
        when(walletManager.hasWallet(player)).thenReturn(true);
        // Mock config limits
        org.bukkit.configuration.file.FileConfiguration config = mock(org.bukkit.configuration.file.FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong("limits.min_invoice_sats", 1)).thenReturn(1L);
        
        invoiceCommand.onCommand(player, command, "invoice", new String[]{"-50"});
        
        verify(player).sendMessage(contains("Minimum invoice amount"));
    }
}
