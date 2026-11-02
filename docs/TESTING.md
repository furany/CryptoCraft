# Validation of the trading update

Verified locally on 2026-11-03 with Java 25.0.5: 108 automated tests passed, followed by both server lifecycles on Paper `26.3-237-7e88a0d`, Purpur `26.3-2655-5b4c7f0`, and Spigot `4664-Spigot-bed90fc-e89124d` (Minecraft 26.3). Each server passed 19 initial checks and 10 restart/cleanup checks with Vault `1.7.3-b131` and EssentialsX `2.22.1-dev+27-e70bdb8`. The tested production JAR has SHA-256 `667d6ad51cce50ca281f205678d0d958052a8c0f7adeac53cc482e7dc76e13dc`.

The generated evidence is in `build/qa/final-server-verification.json` and the phase markers in `build/qa/paper-final4/`, `build/qa/purpur-final4/`, and `build/qa/spigot-final4/`. All three runs verified exactly four map tiles and 30 decorative terminal entities, including the case back, both before and after restart. These are local checks, not a published release or a remote CI run.

Run the regular Java 25 build with `./gradlew --no-daemon build` (Windows: `gradlew.bat`). Tests use temporary SQLite files and mocked Bukkit boundaries. They cover external timestamps, all three modes, budgets and fees, price changes, FIFO and partial fills, no counterparties/self-trades, reservations, cancellations, expiry, crash recovery, provider failures/replacement, configuration locks, legacy migration, permissions, and terminal entity cleanup.

The final regression tests also cover a valid USD-only legacy configuration: it adopts and persists USD without adding coins, explicit EUR remains unchanged, removing the reference key during reload retains the active ledger currency, and explicit currency changes remain blocked after economic activity. The three server runs also confirmed that the adopted reference key is saved during startup and survives restart.

`ChartPainterTest` writes PNGs to `build/chart-previews/` for all themes and sizes, including six-coin market boards and terminals. These allow raster/layout inspection; they are not screenshots of a running Minecraft client.

## Disposable server integration

`src/serverTest` contains an opt-in companion plugin, excluded from the production JAR. Build it with:

```powershell
.\gradlew.bat --no-daemon build serverTestJar
.\scripts\run-server-qa.ps1 `
  -ServerJar C:\test-jars\paper.jar `
  -VaultJar C:\test-jars\Vault.jar `
  -EssentialsJar C:\test-jars\EssentialsX.jar `
  -Java C:\jdk-25\bin\java.exe `
  -Name paper-test -Port 25610
```

Use official Paper/Purpur downloads or a locally built Spigot JAR. The helper requires a new directory name, creates only `build/qa/<name>`, binds to loopback, creates a disposable world/economy account, and runs two server lifecycles. It never copies production data. The companion refuses to execute without the explicit disposable-system property set by the helper.

The first lifecycle checks legacy coin configuration, real Vault/EssentialsX debit and payout, offline execution, confirmation reuse, an unverified timestamp, cancellation refunds, terminal revocation, vanilla map/frame/keyboard creation, and duplicate prevention. The second checks persisted boards and SQLite history after restart, then entity cleanup on removal. The offline fixture explicitly loads neighboring chunks in both phases, as a nearby player would; production boards do not force-load chunks. Controlled prices are injected into the same cache and service boundary; the external API is disabled in this fixture. This separates provider-response correctness from network availability.

Each successful lifecycle writes `plugins/CryptoCraftServerQA/phase1-passed.txt` or `phase2-passed.txt`; errors write `failed.txt`. Server output remains under `build/qa/<name>/`. Never install `CryptoCraft-ServerQA-*.jar` on a normal server: it deliberately changes a fixture account/world and shuts the test server down.

## Client acceptance

An isolated vanilla Minecraft 26.3 client connected to the disposable Paper server on 2026-11-02. At 1920×1080, the visual review checked south-facing 1×1, 2×2, and 3×3 market terminals, the 2×2 BTC chart, map seams, and housing/stand/keyboard alignment without a resource pack or shaders. This review found and corrected the map-frame block-centre offset; the production JAR above includes that correction. Geometry tests cover all four orientations; the native client review used south-facing terminals.

The client review and screenshots used the pre-migration-fix build with SHA-256 `c21b8aacc43d012efbfba642b996057ca6c79be640b2cde6fdba927978491820`. Comparing archive contents with the final build above shows only `CryptoCraftPlugin.class` changed for startup/reload currency migration; terminal geometry, chart/menu classes, and resources are byte-identical. The final build was subsequently verified by the November 3 automated and three-server runs; the client captures remain dated November 2.

The client also verified terminal right-click access, the budget-entry chat flow, the reference-price/fee/budget confirmation, successful execution after a new quote, order refresh, depot holdings, menu navigation, and provider-ID search (`bitcoin` filters the ten-coin catalogue to BTC). A 1,000-unit budget executed 999.99 units after quantity rounding; the order retained no reserved money after completion. The fixture supplies controlled quotes and funded test accounts, so this is a functional test, not evidence of a live provider price.

Six unedited F2 captures and bilingual gallery captions are checked in under [screenshots/v1.2.0](../screenshots/v1.2.0/README.md). The client and server use disposable data under `build/qa/`; existing launcher profiles, accounts, and worlds were not changed. A production economy and its installed protection/permission plugins still warrant a staging-server test.
