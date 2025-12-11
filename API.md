# LightningMC Developer API

## Access

```java
LightningPlugin lightning = LightningPlugin.getInstance();
LNService lnService = lightning.getLnService();
WalletManager walletManager = lightning.getWalletManager();
```

---

## Events

### PaymentReceivedEvent

Fired when a Lightning payment is received by any watched player wallet.

**Package:** `com.crissuper20.lightning.events.PaymentReceivedEvent`

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getPlayerUuid()` | `@Nullable UUID` | UUID of wallet owner |
| `getPlayer()` | `@Nullable Player` | Player if online |
| `getWalletId()` | `String` | LNbits wallet ID |
| `getPaymentHash()` | `String` | Lightning payment hash |
| `getAmountSats()` | `long` | Amount in satoshis |
| `getCheckingId()` | `String` | LNbits internal ID |

```java
@EventHandler
public void onPayment(PaymentReceivedEvent event) {
    Player player = event.getPlayer();
    long amount = event.getAmountSats();
    String hash = event.getPaymentHash();
}
```

---

## Services

### LNService

```java
LNService lnService = LightningPlugin.getInstance().getLnService();
```

| Method | Description |
|--------|-------------|
| `boolean isHealthy()` | Check if LNbits backend is reachable |
| `boolean hasWallet(Player)` | Check if player has a wallet |
| `CompletableFuture<Boolean> createWalletAsync(Player)` | Create wallet for player |
| `CompletableFuture<LNResponse<Invoice>> createInvoiceForPlayer(Player, long amountSats, String memo)` | Create invoice |
| `CompletableFuture<LNResponse<JsonObject>> payInvoiceForPlayer(Player, String bolt11)` | Pay invoice |
| `CompletableFuture<LNResponse<JsonObject>> getWalletInfoForPlayer(Player)` | Get wallet info |
| `String getWalletId(Player)` | Get player's wallet ID |
| `String getAdminKey(Player)` | Get player's admin key |
| `String getInvoiceKey(Player)` | Get player's invoice key |

#### Response Types

```java
class LNResponse<T> {
    boolean success;
    T data;
    String error;
    int statusCode;
}

class Invoice {
    String paymentHash;
    String bolt11;
    String checkingId;
}
```

---

### WalletManager

```java
WalletManager walletManager = LightningPlugin.getInstance().getWalletManager();
```

| Method | Description |
|--------|-------------|
| `boolean hasWallet(Player)` | Check wallet exists |
| `CompletableFuture<Long> getBalance(Player)` | Get balance in sats |
| `CompletableFuture<JsonObject> getOrCreateWallet(Player)` | Get or create wallet |
| `CompletableFuture<Boolean> createWalletForAsync(String playerId, String name)` | Create wallet by UUID |
| `String getWalletId(Player)` | Get wallet ID |
| `String formatBalance(long sats)` | Format for display |

---

## Thread Safety

Callbacks run async. Use scheduler for Bukkit API:

```java
LightningPlugin.getInstance().getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
    p.sendMessage("Done!");
});
```
