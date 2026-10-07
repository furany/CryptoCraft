# Changelog

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
- Board cleanup after explosions, pistons, and external anchor changes; safer teleports and authoritative permission checks.
- Configuration validation, legacy board migration, and fallback translations for existing custom message files.
- Reproducible builds with a pinned Gradle wrapper and 42 automated tests.

### Upgrading from 1.0.0

1. Back up `plugins/CryptoCraft/` and stop the server.
2. Replace the old JAR with `CryptoCraft-1.1.0.jar` and restart on Java 25 with Spigot, Paper, or Purpur 26.3.
3. Existing boards and messages are preserved. Missing new configuration and message keys use bundled defaults.
4. Add `portfolio.enabled: true` or `prices.backfill.enabled: true` to `config.yml` if you want those optional features. Both are disabled by default.

Screenshot evidence covers charts and board menus/editing; the automated suite covers the core logic. Verify optional integration and redstone behavior on your chosen server build.
