# CryptoCraft

CryptoCraft displays cryptocurrency prices above ordinary Minecraft blocks on Paper and Purpur. The block remains a normal vanilla block, so players can still interact with it as usual. A floating text display is attached to the block and is removed when the anchor block is broken.

[![Build](https://github.com/furany/CryptoCraft/actions/workflows/build.yml/badge.svg)](https://github.com/furany/CryptoCraft/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/furany/CryptoCraft)](https://github.com/furany/CryptoCraft/releases/latest)

## Download

Download the latest compiled JAR from the [Releases page](https://github.com/furany/CryptoCraft/releases/latest). Place the JAR in your server's `plugins` directory and restart Paper or Purpur.

## Requirements

- Paper or Purpur 26.3
- Java 25
- Gradle (for local builds)

## Build locally

Install a Java 25 JDK and Gradle, then run this from the project directory:

```sh
gradle clean build
```

The JAR is written to `build/libs/` (currently `CryptoCraft-1.0.0.jar`). Copy it into your server's `plugins` directory and restart the server.

## Build with GitHub Actions

The workflow in `.github/workflows/build.yml` builds the JAR on every push and pull request. It can also be started manually from the repository's **Actions** tab using **Build CryptoCraft → Run workflow**.

When a workflow run finishes, open that run in **Actions**, then download the **CryptoCraft** artifact. The downloaded ZIP contains the plugin JAR.

Pushing a version tag such as `v1.0.0` starts `.github/workflows/release.yml`, which builds the plugin and attaches the JAR to a public GitHub Release.

## Use in game

Look at a vanilla block and run:

```text
/crypto place BTC EUR
```

The board appears above that block. Its normal use is unchanged. Breaking the anchor block removes the board and frees the owner's slot. `/crypto remove` removes only the floating display and leaves the block in place.

Commands:

- `/crypto place <coin> [currency]` — place a board on the block you are looking at
- `/crypto remove` — remove your board from the block you are looking at
- `/crypto list` — list your boards
- `/crypto price <coin> [currency]` — show the cached price
- `/crypto reload` — reload the configuration (admin permission required)

BTC, ETH, and SOL are configured in EUR by default. All active boards share one price request per refresh interval.

## Configuration

Edit `plugins/CryptoCraft/config.yml`, then run `/crypto reload`.

```yaml
prices:
  refresh-interval-seconds: 300
  minimum-request-interval-seconds: 60
  request-timeout-seconds: 20
  stale-after-minutes: 10
  retry:
    initial-delay-seconds: 60
    maximum-delay-seconds: 3600

boards:
  default-limit-per-player: 1
  admin-bypass-limit: true
  admin-permission: cryptocraft.admin
  unlimited-permission: cryptocraft.limit.unlimited
  permission-limits:
    cryptocraft.limit.vip: 3
    cryptocraft.limit.elite: 5
  display-height: 1.35

api:
  url: "https://api.coingecko.com/api/v3/simple/price"
  demo-key: ""
  query:
    coin-ids-parameter: ids
    currencies-parameter: vs_currencies
    include-24hr-change: true
    include-last-updated-at: true
    additional-parameters: {}

coins:
  BTC: bitcoin
  ETH: ethereum
  SOL: solana

currencies:
  - EUR

default-currency: EUR
```

`refresh-interval-seconds` controls automatic polling. `default-currency` is used when a command omits its optional currency argument. `minimum-request-interval-seconds` is a global floor for all requests, including a refresh triggered when a board is placed. Retry delays grow exponentially after HTTP 429 responses and stop growing at `maximum-delay-seconds`. Cached prices are marked stale after `stale-after-minutes`.

Set `api.url` to a CoinGecko Simple Price compatible endpoint. The query parameter names for coin IDs and currencies, the optional 24-hour-change and update-time flags, and extra string-valued query parameters can be changed under `api.query`. Coin symbols map to CoinGecko coin IDs under `coins`; currencies are listed under `currencies`.

## Player limits and permissions

Players may place one board by default. A matching permission in `boards.permission-limits` sets that player's limit; if more than one configured permission matches, the highest matching limit applies. Players with the configured unlimited permission bypass the limit. Admins bypass it as well while `boards.admin-bypass-limit` is enabled.

LuckPerms examples:

```text
/lp group vip permission set cryptocraft.limit.vip true
/lp group elite permission set cryptocraft.limit.elite true
/lp group staff permission set cryptocraft.limit.unlimited true
/lp group admin permission set cryptocraft.admin true
```

The plugin uses Bukkit permissions directly and has no API dependency on LuckPerms, EssentialsX, or Vault. EssentialsX can remain installed; CryptoCraft uses the `/crypto` command.

## CoinGecko API

With an empty `api.demo-key`, CryptoCraft uses CoinGecko's keyless Public API. CoinGecko documents a dynamic shared limit of roughly 10–30 calls per minute per public IP, but says the keyless API is not intended for scheduled polling in production. CryptoCraft batches all active boards into one request every five minutes by default and backs off after HTTP 429 responses. Servers sharing one public IP also share CoinGecko's IP-based limit. For more reliable polling, set a Demo API key in `api.demo-key`.

See CoinGecko's [keyless Public API documentation](https://docs.coingecko.com/docs/keyless-public-api) for current limits and usage guidance.
