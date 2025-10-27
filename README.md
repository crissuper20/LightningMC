# PaperMC/Spigot Minecraft Server Plugin Template
A template for building PaperMC/Spigot Minecraft server plugins!

## Features
### Github Actions 🎬
* Automated builds, testing, and release drafting
* [Discord notifcations](https://github.com/marketplace/actions/discord-message-notify) for snapshots and releases

### Bots 🤖
* **Probot: Stale**
    * Mark issues stale after 30 days
* **Dependabot**
    * Update GitHub Actions workflows
    * Update Gradle dependencies

### Issue Templates 📋
* Bug report template
* Feature request template

### Gradle Builds 🏗
* Shadowed [PaperLib](https://github.com/PaperMC/PaperLib) build
* [Checkstyle](https://checkstyle.org/) Google standard style check
* [SpotBugs](https://spotbugs.github.io/) code analysis
* [JUnit](https://junit.org/) testing

### Config Files 📁
* Sample plugin.yml with autofill name, version, and main class.
* Empty config.yml (just to make life \*that\* much easier)
* Gradle build config
* Simple .gitignore for common Gradle files

## Usage
In order to use this template for yourself, there are a few things that you will need to keep in mind.

### Release Info
#### PaperMC Version Mapping
Here's a list of the PaperMC versions and the versions of this latest compatible version.

| PaperMC | ExamplePlugin |
|---------|---------------|
| 1.21.10 | 4.0.17        |
| 1.21.8  | 4.0.16        |
| 1.21.7  | 4.0.15        |
| 1.21.6  | 4.0.14        |
| 1.21.5  | 4.0.12        |
| 1.21.4  | 4.0.7         |        
| 1.21.3  | 4.0.3         |
| 1.21.1  | 4.0.2         |
| 1.21    | 3.12.1        |
| 1.20.6  | 3.11.0        |
| 1.19.4  | 3.2.1         |
| 1.18.2  | 3.0.2         |
| 1.17.1  | 2.2.0         |
| 1.16.5  | 2.1.2         |

This chart would make more sense if this plugin actually did anything and people would have a reason
to be looking for older releases to run on older servers.

To use this as a template, just use the latest version of this project and update the PaperMC
version as needed. See more info on release stability below.

#### Release and Versioning Strategy
Stable versions of this repo are tagged `vX.Y.Z` and have an associated [release](https://github.com/CrimsonWarpedcraft/plugin-template/releases).

Testing versions of this repo are tagged `vX.Y.Z-RC-N` and have an associated [pre-release](https://github.com/CrimsonWarpedcraft/plugin-template/releases).

Development versions of this repo are pushed to the master branch and are **not** tagged.

| Event             | Plugin Version Format | CI Action                        | GitHub Release Draft? |
|-------------------|-----------------------|----------------------------------|-----------------------|
| PR                | yyMMdd-HHmm-SNAPSHOT  | Build and test                   | No                    |
| Cron              | yyMMdd-HHmm-SNAPSHOT  | Build, test, and notify          | No                    |
| Push to `main`    | 0.0.0-SNAPSHOT        | Build, test, release, and notify | No                    |
| Tag `vX.Y.Z-RC-N` | X.Y.Z-SNAPSHOT        | Build, test, release, and notify | Pre-release           |
| Tag `vX.Y.Z`      | X.Y.Z                 | Build, test, release, and notify | Release               |

### Discord Notifications
In order to use Discord notifications, you will need to create two GitHub secrets. `DISCORD_WEBHOOK_ID` 
should be set to the id of your Discord webhook. `DISCORD_WEBHOOK_TOKEN` will be the token for the webhook.

You can find these values by copying the Discord Webhook URL:  
`https://discord.com/api/webhooks/<DISCORD_WEBHOOK_ID>/<DISCORD_WEBHOOK_TOKEN>`

Optionally, you can also configure `DISCORD_RELEASE_WEBHOOK_ID` and `DISCORD_RELEASE_WEBHOOK_TOKEN`
to send release announcements to a separate channel.

For more information, see [Discord Message Notify](https://github.com/marketplace/actions/discord-message-notify).

---

**I've broken the rest of the changes up by their files to make things a bit easier to find.**

---

### settings.gradle
Update the line below with the name of your plugin.

```groovy
rootProject.name = 'ExamplePlugin'
```

### build.gradle
Make sure to update the `group` to your package's name in the following section.

```groovy
group = "com.crimsonwarpedcraft.exampleplugin"
```

Add any required repositories for your dependencies in the following section.

```groovy
repositories {
    maven {
        name 'papermc'
        url 'https://papermc.io/repo/repository/maven-public/'
        content {
            includeModule("io.papermc.paper", "paper-api")
            includeModule("io.papermc", "paperlib")
            includeModule("net.md-5", "bungeecord-chat")
        }
    }

    mavenCentral()
}
```

Also, update your dependencies as needed (of course).

```groovy
dependencies {
    compileOnly 'io.papermc.paper:paper-api:1.21.6-R0.1-SNAPSHOT'
    compileOnly 'com.github.spotbugs:spotbugs-annotations:4.9.3'
    implementation 'io.papermc:paperlib:1.0.8'
    spotbugsPlugins 'com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0'
    testCompileOnly 'com.github.spotbugs:spotbugs-annotations:4.9.3'
    testImplementation 'io.papermc.paper:paper-api:1.21.6-R0.1-SNAPSHOT'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.13.1'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.13.1'
}
```

### src/main/resources/plugin.yml
First, update the following with your information.

```yaml
<<<<<<< HEAD
backend: lnbits
debug: false

lnbits:
  host: "demo.lnbits.com"
  use_https: true
  wallet_id: ""
  api_key: "your_invoice_key_here"
  use_tor_proxy: false
  skip_tls_verify: false
=======
author: AUTHOR
description: DESCRIPTION
>>>>>>> parent of aa4c02e (made readme.md)
```

Next, the `commands` and `permissions` sections below should be updated as needed.

```yaml
<<<<<<< HEAD
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

=======
commands:
  ex:
    description: Base command for EXAMPLE
    usage: "For a list of commands, type /ex help"
    aliases: example
>>>>>>> parent of aa4c02e (made readme.md)
permissions:
  example.test:
    description: DESCRIPTION
    default: true
  example.*:
    description: Grants all other permissions
    default: false
    children:
      example.test: true
```

### .github/dependabot.yml
You will need to replace all instances of `leviem1`, such as the one below, with your GitHub
username.

<<<<<<< HEAD
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
=======
>>>>>>> parent of aa4c02e (made readme.md)
```yaml
reviewers:
  - "leviem1"
```

### .github/CODEOWNERS
You will need to replace `leviem1`, with your GitHub username.

```text
*   @leviem1
```

### .github/FUNDING.yml
Update or delete this file, whatever applies to you.

```yaml
github: leviem1
```

For more information see: [Displaying a sponsor button in your repository](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/displaying-a-sponsor-button-in-your-repository)

### CODE_OF_CONDUCT.md
If you chose to adopt a Code of Conduct for your project, please update line 63 with your preferred
contact method.

## Creating a Release
Below are the steps you should follow to create a release.

1. Create a tag on `main` using semantic versioning (e.g. v0.1.0)
2. Push the tag and get some coffee while the workflows run
3. Publish the release draft once it's been automatically created

## Building locally
Thanks to [Gradle](https://gradle.org/), building locally is easy no matter what platform you're on. Simply run the following command:

```text
./gradlew build
```

This build step will also run all checks and tests, making sure your code is clean.

JARs can be found in `build/libs/`.

## Contributing
See [CONTRIBUTING.md](https://github.com/CrimsonWarpedcraft/plugin-template/blob/main/CONTRIBUTING.md).

---

I think that's all... phew! Oh, and update this README! ;)
