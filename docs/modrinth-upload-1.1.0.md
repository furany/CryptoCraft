# Modrinth upload: CryptoCraft 1.1.0

Project: [CryptoCraft](https://modrinth.com/plugin/cryptocraft-furany)

| Field | Value |
| --- | --- |
| Version name | CryptoCraft 1.1.0 |
| Version number | 1.1.0 |
| Release channel | Release |
| Minecraft version | 26.3 |
| Loaders | Spigot, Paper, Purpur |
| Primary file | CryptoCraft-1.1.0.jar |
| Installation | Server only; Java 25 |
| Required dependencies | None |
| Optional integrations | WorldGuard, PlaceholderAPI |

Create a new version under **Versions** and upload the JAR from `build/libs/`. Keep 1.0.0 as a separate version. The project description and screenshot gallery are already updated.

Use this text in the changelog field:

```markdown
## Added
- Board menu and editor for coin, currency, name, facing, height, history window, theme, view, and wall size.
- Dark, light, and ocean themes; chart, price, and compact views; displays up to 3 x 3 maps.
- Per-board history windows from 1 to 168 hours and optional high/low values.
- Watchlists, coin comparisons, price alerts, and offline notifications.
- Redstone threshold signals using an existing lever above the board's anchor.
- Optional virtual portfolio, initial history imports, WorldGuard protection, and PlaceholderAPI support.
- /crypto status for API and storage diagnostics.

## Improved
- Background saving with atomic writes, backups, and recovery.
- Shared chart rendering and caching.
- Price parsing, stale-quote handling, API request limits, and retry delays.
- Board cleanup, teleport checks, permissions, configuration validation, and legacy data migration.

## Requirements and upgrade
- Java 25 and Spigot, Paper, or Purpur 26.3.
- Stop the server, back up plugins/CryptoCraft/, replace the old JAR, and restart.
- Existing boards and custom messages are preserved. New keys use bundled defaults.
- Virtual trading and initial history imports are disabled by default.
```
