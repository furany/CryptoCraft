# Changelog

## 1.2.0 - 2026-11-03

### Added

- Vault-compatible server-money trading, separate from the existing paper portfolio, with an unlimited broker, reserve-funded broker, and FIFO player matching at external provider prices.
- Budget buys, quantity buys with a confirmed maximum budget, quantity/percentage sells, price guards, previews, confirmations, depot, order status/cancellation, and history.
- SQLite balances, full order reservations, partial fills, offline execution, expiry, reserve, and audit journal. Uncertain Vault movements freeze the affected account and require explicit administrative reconciliation without replaying the call.
- Admin-approved vanilla computer terminals with map screens, display-entity housing/stand/keyboard, six-block access checks, and optional labeled/anywhere modes.
- Market boards with one to six ordered coins in a shared currency, default 2 × 2 maps, plus coin catalog search and separate display/trading controls.
- Default coins XRP, ADA, DOGE, LTC, BNB, AVAX, and LINK for new installations, alongside BTC, ETH, and SOL.
- `/crypto trade`, `/crypto depot`, `/crypto orders`, `/crypto terminal`, `/crypto market`, `/crypto economy`, and `/crypto coin`.

### Improved

- Trading requires a fresh, verifiable provider timestamp after order acceptance, and after both orders for player matching. Cached history and reception timestamps cannot authorize fills.
- Searchable/paginated inventory flows for boards, watchlists, alarms, trading, and holdings, with German and English messages.
- Chart and market layouts for 1 × 1 through 3 × 3 maps, full terminal geometry protection, neighboring-chunk cleanup, and tests for legacy/market persistence and entity lifecycle.
- Mode changes reject open orders/unclear transfers; reference currency, factor, precision, and provider identity are protected after economic activity. Disabled coins keep their holdings.
- Legacy configurations without `trading.reference-currency` inherit their valid existing `default-currency`, including USD-only setups; new installations default to EUR, and reloads without that key retain the active trading currency.
- Trading setup, defaults, permissions, migrations, exact rounding behavior, and manual reconciliation are documented in [the trading guide](docs/TRADING.md).

### Upgrading from 1.1.0

1. Stop the server and back up the complete CryptoCraft directory and any economy provider's data.
2. Replace the JAR with `CryptoCraft-1.2.0.jar` and restart on Java 25. Install Vault and a compatible economy provider to enable server-money transfers.
3. Existing board/configuration/message data and the paper portfolio are retained. Existing boards remain nonterminal until administratively approved; existing coin lists are not replaced.
4. Review the mode, reference currency, factor, fees, and access before approving terminals. New installations default to `unlimited`, EUR, factor 1, zero fees, and terminal-only access. The SQLite depot is separate from the old paper portfolio.

## 1.1.0 - 2026-10-07

### Added

- Paginated board menu and an editor for coin, currency, name, facing, height, time window, theme, view, wall size, and high/low display.
- Dark, light, and ocean themes; chart, price, and compact views; square displays up to 3 x 3 maps.
- Per-board history windows from 1 to 168 hours.
- Watchlists, percentage comparisons, one-shot/repeating price alerts, and queued offline notifications.
- Redstone threshold signals using an existing lever directly above a board's anchor.
- Optional virtual portfolio with cash, holdings, acquisition cost, realized profit, and trade history.
- Optional initial historical imports, WorldGuard protection, and PlaceholderAPI values.
- `/crypto status` with API request, freshness, and storage diagnostics.
- Six new Minecraft 26.3 screenshots documenting chart boards, menus, editing controls, and large walls.

### Improved

- Asynchronous, coalesced data writes with atomic replacement, backups, and startup recovery.
- Shared chart-image rendering/cache and chunk/entity indexes.
- JSON number parsing, tiny-price formatting, provider timestamps, quote requests without boards, and stale-data handling.
- Global request throttling, HTTP `Retry-After`, API error backoff, and rejection of old responses after reload/shutdown.
- Board cleanup after explosions, pistons, and external anchor changes; safer teleports and permission/ownership checks.
- Configuration validation, legacy board migration, and fallback translations for existing custom message files.
- Pinned Gradle wrapper and 42 automated tests.

### Upgrading from 1.0.0

1. Back up `plugins/CryptoCraft/` and stop the server.
2. Replace the old JAR with `CryptoCraft-1.1.0.jar` and restart on Java 25 with Spigot, Paper, or Purpur 26.3.
3. Existing boards and messages are preserved. Missing new configuration and message keys use bundled defaults.
4. Add `portfolio.enabled: true` or `prices.backfill.enabled: true` to `config.yml` if you want those optional features. Both are disabled by default.
