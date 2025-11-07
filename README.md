# LightningMC

> Bitcoin Lightning Network payments in Minecraft

A Minecraft plugin that integrates the Lightning Network as an economy system. Create and pay Lightning invoices directly from your Minecraft server

![Minecraft](https://img.shields.io/badge/Minecraft-1.16+-brightgreen)
![Status](https://img.shields.io/badge/status-development-yellow)
![License](https://img.shields.io/badge/license-MIT-blue)

## Features

 ** Lightning Wallets** - Automatic wallet creation for each player
 ** QR Code Invoices** - Generate scannable payment QR codes on in-game maps
 ** Real Bitcoin** - Use actual Lightning Network payments (testnet/mainnet)
 ** Tor Support** - Connect via Tor for privacy
 ** Configurable** - Rate limits, transaction limits, and monitoring settings

## Quick Start

1. Download the latest `.jar` from [Releases](../../releases)
2. Drop it in your `plugins/` folder
3. Edit `plugins/LightningMC/config.yml` with your LNbits credentials
4. Restart your server

```yaml
lnbits:
  host: "demo.lnbits.com"
  api_key: "your_admin_key_here"
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/wallet` | View/create your wallet | `lightning.wallet` |
| `/balance` | Check your balance | `lightning.balance` |
| `/invoice <amount> <memo>` | Create invoice with QR code | `lightning.invoice` |
| `/pay <bolt11>` | Pay a Lightning invoice | `lightning.pay` |
| `/lnadmin` | Admin tools | `lightning.admin` |

**Example:**
```
/invoice 100 memo text
```

## 🔧 Server Support

| Minecraft Version | Status | API Level |
|-------------------|--------|-----------|
| 1.16 - 1.21+ | ✅ Supported | 1.16 |
| Folia | 📋 Planned | - |

Minimum: **Minecraft 1.16** (api-version: 1.16)

## Configuration Highlights

```yaml
# Invoice monitoring
invoice_monitor:
  check_interval_seconds: 3
  expiry_minutes: 60

# Rate limits
rate_limits:
  invoices_per_minute: 5
  payments_per_minute: 10

# Transaction limits
limits:
  min_invoice_amount: 1
  max_invoice_amount: 1000000
```

## Contributing

Please contribute if you want!

## License

MIT License 

## Credits

Built with [LNbits](https://lnbits.com/) • [ZXing](https://github.com/zxing/zxing) 

---

