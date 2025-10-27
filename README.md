#  LightningMC

A Minecraft plugin that integrates the Lightning Network as an economy system. Create and pay Lightning invoices directly from your Minecraft server.

Public release coming not-so-soon xd

![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.4-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)
![Status](https://img.shields.io/badge/status-in%20development-yellow)

## Features

- **Lightning Network Integration** - Connect your Minecraft server to the Lightning Network
- **Economy System** - Use real Bitcoin Lightning payments as your server economy
- **QR Code Invoices** - Generate in-game map items with QR codes for easy mobile payments
- **Multi-Backend Support** - Works with both LNbits and LND
- **Tor Support** - Connect to backends via Tor for enhanced privacy
- **Player Wallets** - Each player gets their own Lightning wallet

## Screenshots

*tbd*

## Version compatibility

| Minecraft Version | Plugin Status |
|-------------------|---------------|
| 1.21.4 (Paper)   |  Supported   |
| Folia Support    |  To be done  |

## Installation

1. Download the latest release from the [Releases](../../releases) page
2. Place the `.jar` file in your server's `plugins/` folder
3. Start your server to generate the default configuration
4. Configure your Lightning backend (see [Configuration](#-configuration))
5. Restart your server

## Configuration

The plugin generates a default `config.yml` on first run. Edit `plugins/LightningMC/config.yml`:

### Configuration Examples

#### Example 1: Public LNbits Testnet
```yaml
backend: lnbits
debug: false

lnbits:
  host: "demo.lnbits.com"
  use_https: true
  wallet_id: ""
  api_key: "your_invoice_key_here"
  use_tor_proxy: false
  skip_tls_verify: false
```

#### Example 2: Local LNbits (No HTTPS)
```yaml
backend: lnbits
debug: true

lnbits:
  host: "localhost:5000"
  use_https: false
  wallet_id: ""
  api_key: "your_admin_key_here"
  use_tor_proxy: false
  skip_tls_verify: false
```

#### Example 3: LNbits via Tor (.onion)
```yaml
backend: lnbits
debug: false

lnbits:
  host: "yournode.onion"
  use_https: true
  wallet_id: ""
  api_key: "your_api_key_here"
  use_tor_proxy: true
  tor_proxy_type: "socks5"
  tor_proxy_host: "127.0.0.1"
  tor_proxy_port: 9050
  skip_tls_verify: true  # Required for .onion with self-signed certs
```

#### Example 4: Local LND Testnet
```yaml
backend: lnd
debug: false

lnd:
  host: "localhost"
  port: 8080
  use_https: true
  macaroon_path: "/home/bitcoin/.lnd/data/chain/bitcoin/testnet/admin.macaroon"
  macaroon_hex: ""
  use_tor_proxy: false
  skip_tls_verify: true  # LND uses self-signed certificates
```

#### Example 5: LND via Tor (.onion) (embassyOS hosted LND still does not work :c )
```yaml
backend: lnd
debug: false

lnd:
  host: "abc123xyz.onion"
  port: 8080
  use_https: true
  macaroon_path: ""
  macaroon_hex: "0201036c6e6402f801030a10..."  # Your hex macaroon
  use_tor_proxy: true
  tor_proxy_host: "127.0.0.1"
  tor_proxy_port: 9050
  skip_tls_verify: true
```

### Getting Your LND Macaroon Hex

If using LND with `macaroon_hex`, convert your macaroon file to hex:

```bash
# On your LND server
xxd -ps -u -c 1000 ~/.lnd/data/chain/bitcoin/testnet/admin.macaroon
```

Copy the output and paste it into the `macaroon_hex` field.

### Resources

#### LNbits Instances
- [Legend.lnbits.com](https://demo.lnbits.com) - Public testnet instance
- [Self-host LNbits](https://github.com/lnbits/lnbits) - Run your own instance

#### LND Resources
- [LND REST API Docs](https://api.lightning.community/rest/)
- [LND Installation Guide](https://docs.lightning.engineering/lightning-network-tools/lnd/run-lnd)

## / Commands

### Player Commands

| Command | Description | Usage | Permission |
|---------|-------------|-------|------------|
| `/wallet` | View or create your Lightning wallet | `/wallet` | `lightning.wallet` |
| `/balance` | Check your Lightning balance | `/balance` | `lightning.balance` |
| `/invoice` | Create a Lightning invoice with QR code | `/invoice <amount> <memo>` | `lightning.invoice` |
| `/pay` | Pay a Lightning invoice | `/pay <bolt11>` | `lightning.pay` |

### Command Examples

```bash
# Create your wallet
/wallet

# Check your balance
/balance

# Create an invoice for 1000 sats with a memo
/invoice 1000 text

# Pay an invoice
/pay lnbc10n1p3...
```

### Command Details

#### `/invoice <amount> <memo>`
Creates a Lightning invoice and generates an in-game map item with a QR code.

- `amount`: Amount in satoshis (must be a positive integer)
- `memo`: Description for the invoice (can be multiple words)

**Example:**
```
/invoice 500 test
```

**Output:**
-  Invoice text in chat (for copying)
-  QR code map item in your inventory
-  Scannable with any Lightning wallet (i think)

#### `/balance`
Shows your current Lightning wallet balance in satoshis.

#### `/wallet`
Displays your Lightning wallet information or creates one if you don't have one yet.

#### `/pay <bolt11>` *(coming soon)*
Pay a Lightning invoice by pasting the BOLT11 payment request.

## Permissions

All permissions default to `true` for all players.

| Permission | Description |
|------------|-------------|
| `lightning.wallet` | Access wallet commands |
| `lightning.balance` | Check balance |
| `lightning.invoice` | Create invoices |
| `lightning.pay` | Pay invoices |

To restrict a command, use your permissions plugin:
```yaml

permissions:
  lightning.invoice:
    default: false
```

## Building from Source

### Prerequisites
- Java 21 or higher
- Gradle 8

### Build Steps
```bash
# Clone the repository
git clone https://github.com/crissuper20/LightningMC.git
cd LightningMC

# Build with the included gradle script
chmod +x ./gradlew 
./gradlew build

# The compiled .jar will be in /build/libs/
```

## Troubleshooting

### "Could not connect to backend"
- Verify your backend is running and accessible
- Check `host` and `port` in config.yml
- If using Tor, ensure Tor is running on the configured port
- Enable `debug: true` in config.yml for more logs

### "Missing required config"
- Delete `config.yml` and restart to regenerate defaults
- Ensure all required fields are filled for your chosen backend

### Certificate Errors with HTTPS (start9 xd)
For self-signed certificates or .onion addresses:
```yaml
skip_tls_verify: true
```

### Permission Denied
Ensure players have the required permissions:
```bash
/lp user <player> permission set lightning.invoice true
```

##  Roadmap

- [x] Basic Lightning integration (LNbits/LND)
- [x] Invoice creation with QR codes
- [x] Player wallets
- [x] Balance checking
- [ ] Invoice payment (`/pay` command)
- [ ] Transaction history
- [ ] Admin commands
- [ ] Folia support and all others
- [ ] Economy plugin integration (Vault)
- [ ] Automated payments for shops
- [ ] Lightning Address support

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

##  Acknowledgments

- [LNbits](https://lnbits.com/) - Lightning Network wallet and accounts system
- [LND](https://github.com/lightningnetwork/lnd) - Lightning Network Daemon
- [ZXing](https://github.com/gomania6/QRMap) - QRs on maps


---