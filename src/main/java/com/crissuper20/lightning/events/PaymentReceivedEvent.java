package com.crissuper20.lightning.events;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fired when a Lightning payment is received by a watched wallet.
 * This event is fired on the main server thread.
 */
public class PaymentReceivedEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final UUID playerUuid;
    private final String walletId;
    private final String paymentHash;
    private final long amountSats;
    private final String checkingId;

    public PaymentReceivedEvent(UUID playerUuid, String walletId, String paymentHash, long amountSats, String checkingId) {
        this.playerUuid = playerUuid;
        this.walletId = walletId;
        this.paymentHash = paymentHash;
        this.amountSats = amountSats;
        this.checkingId = checkingId;
    }

    /**
     * Get the UUID of the player who owns the wallet.
     * Can be null if the wallet is not associated with a known player.
     */
    public @Nullable UUID getPlayerUuid() {
        return playerUuid;
    }

    /**
     * Get the player if they are online.
     */
    public @Nullable Player getPlayer() {
        if (playerUuid == null) return null;
        return Bukkit.getPlayer(playerUuid);
    }

    /**
     * Get the LNbits wallet ID that received the payment.
     */
    public String getWalletId() {
        return walletId;
    }

    /**
     * Get the payment hash of the invoice.
     */
    public String getPaymentHash() {
        return paymentHash;
    }

    /**
     * Get the amount received in Satoshis.
     */
    public long getAmountSats() {
        return amountSats;
    }

    /**
     * Get the checking ID (internal LNbits ID).
     */
    public String getCheckingId() {
        return checkingId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
