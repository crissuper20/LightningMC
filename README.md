# LightningMC

> Bitcoin Lightning Network payments in Minecraft

Integrate the Lightning Network as your Minecraft server's economy. Create and pay Lightning invoices directly in-game

![Minecraft](https://img.shields.io/badge/Minecraft-1.16+-brightgreen)
![Status](https://img.shields.io/badge/status-development-yellow)
![License](https://img.shields.io/badge/license-MIT-blue)

## Features

- **Auto Wallets** - Every player gets their own Lightning wallet
- **QR Invoices** - Scannable QR codes rendered on in-game maps
- **Real Lightning network** - Actual Lightning payments
- **LNDHub** - For wallet integration on your phone*

## Quick Start

1. Download latest `.jar` from Releases tab
2. Place in `plugins/` folder
3. Configure `plugins/LightningMC/config.yml`:

```yaml
lnbits:
  host: "demo.lnbits.com"
  api_key: "your_admin_key_here"
```

4. Start server

## Building from Source

```bash
# Clone the repository
git clone https://github.com/crissuper20/LightningMC.git
cd LightningMC

# Linux/Mac
chmod +x ./gradlew
./gradlew build

# Windows
gradlew.bat build
```

The compiled `.jar` will be in `build/libs/`

## Commands

| Command | Description |
|---------|-------------|
| `/wallet` | View/create your Lightning wallet |
| `/balance` | Check your balance |
| `/invoice <amount> [memo]` | Create invoice with QR code |
| `/pay <bolt11>` | Pay a Lightning invoice |
| `/invoice split` | Create split payment invoices |
| `/lnadmin` | Admin controls (op only) |

**Example:**
```
/invoice 1000 memo
```

## Requirements

- **Minecraft:** 1.16+
- **LNbits:** Instance with admin API key, you can use [demo.lnbits.com](https://demo.lnbits.com) for testing

## Developer API

Want to integrate LightningMC with your plugin? See the **[API Documentation](API.md)** for:

- Listening to `PaymentReceivedEvent` for payment notifications
- Creating invoices and processing payments programmatically
- Managing player wallets and balances
 
## Contributing

Contributions welcome! Open an issue or PR pls!

## License

MIT License

---

**Built with** [LNbits](https://lnbits.com) • [ZXing](https://github.com/zxing/zxing),
And beware, parts of this plugin were made with AI (spooky), expect unexpected behavior or results, DO NOT use real coins!!!

*Whoever controls or has access to the LNBits instance, controls the wallets. 
