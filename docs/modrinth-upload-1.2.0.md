# Modrinth upload: CryptoCraft 1.2.0

Project: [CryptoCraft](https://modrinth.com/plugin/cryptocraft-furany)

| Field | Value |
| --- | --- |
| Version name | CryptoCraft 1.2.0 |
| Version number | 1.2.0 |
| Release date | 2026-11-03 |
| Release channel | Release |
| Minecraft version | 26.3 |
| Loaders | Spigot, Paper, Purpur |
| Primary file | `CryptoCraft-1.2.0.jar` from `build/libs/` |
| Installation | Server only; Java 25 |
| Client support | No client mod or resource pack required |
| Required dependencies | None for the base plugin |
| Optional dependency relations | Vault, EssentialsX, WorldGuard, PlaceholderAPI |
| Server-money trading requirement | Vault plus one compatible economy provider; EssentialsX is one supported choice |

Vault and EssentialsX are **optional project dependencies**, not requirements for charts, watchlists, alerts, or the independent paper portfolio. Vault alone does not provide an economy balance. Server-money trading needs both Vault and a registered compatible economy provider; a provider other than EssentialsX can be used. WorldGuard and PlaceholderAPI are optional integrations.

## Project description and media

Use [modrinth.md](modrinth.md) as the project description. Its image and documentation URLs point to the `v1.2.0` GitHub tag; publish that tag before applying the description so the links resolve. Keep earlier plugin versions available as separate releases.

Upload the production JAR as the primary version file. The QA companion JAR is not part of the release. The media archive `build/CryptoCraft-1.2.0-media.zip` supplies the six original PNGs and their caption README for the project gallery.

[English/German gallery titles and captions](../screenshots/v1.2.0/README.md):

| Order | Image | English gallery title |
| --- | --- | --- |
| 1 | `terminal-sizes.png` | Computer terminals in three sizes |
| 2 | `market-terminal.png` | Six-coin market terminal |
| 3 | `bitcoin-chart-terminal.png` | Bitcoin chart at the trading terminal |
| 4 | `trade-confirmation.png` | Review a buy order before confirming |
| 5 | `filled-order.png` | Filled Bitcoin order |
| 6 | `server-money-depot.png` | Server-money coin holdings |

All six files are unedited 1920 × 1080 Minecraft F2 captures from Minecraft 26.3 on Paper 26.3. No resource pack or shaders were used. Retain the caption disclosure that the prices, history, and balances are controlled demo data, rather than evidence of live market prices.

## Version changelog

Use the following Markdown in the version changelog field:

```markdown
## New in 1.2.0

- Server-money trading through Vault: unlimited broker, reserve-funded broker, or FIFO player matching at external provider prices.
- Budget/quantity buys, quantity/percentage sells, price guards, confirmations, depot, orders, cancellation, and history.
- Fresh provider timestamps required after order acceptance; no fills from missing timestamps or startup history.
- SQLite reservations and journal, offline execution, partial fills, expiry, and manual reconciliation of uncertain Vault movements without repeating the original call.
- Vanilla computer terminals and six-coin market boards, with layouts from 1 × 1 to 3 × 3 maps and no resource pack.
- Ten default coins on new installations, separate display/trading controls, searchable menus, and English/German text.

## Requirements and upgrade

- Java 25 and Spigot, Paper, or Purpur 26.3.
- Vault plus a compatible economy provider, such as EssentialsX, is needed only for server-money trading. Charts and the independent paper portfolio work without them.
- Stop the server and back up CryptoCraft and your economy provider's data before replacing the JAR.
- Existing boards, custom configuration/messages, coin lists, and paper portfolios are retained. Existing boards need explicit approval before becoming trading terminals.
- Legacy configurations without `trading.reference-currency` inherit their valid existing `default-currency`, including USD-only setups; new installations default to EUR, and reloads without that key retain the active trading currency.
- Review the mode, reference currency, factor, fees, and access before trading starts. New installations default to unlimited mode, EUR, factor 1, zero fees, and terminal-only access. The new SQLite depot is separate from the paper portfolio.

[Trading setup and recovery](https://github.com/furany/CryptoCraft/blob/v1.2.0/docs/TRADING.md) · [Full changelog](https://github.com/furany/CryptoCraft/blob/v1.2.0/CHANGELOG.md)
```
