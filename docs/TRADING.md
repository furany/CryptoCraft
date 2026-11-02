# Server-money trading in CryptoCraft 1.2.0

CryptoCraft represents coins inside the Minecraft server. Vault connects it to EssentialsX or another compatible server economy. There are no real wallets, deposits of cryptocurrency, or external exchange orders. `/crypto portfolio` remains an independent paper simulation.

## Setup

1. Stop the server and back up the complete CryptoCraft directory and your economy provider's data.
2. Install the CryptoCraft JAR, Vault, and an economy provider, then start on Java 25.
3. Look at a normal block and use `/crypto market BTC,ETH,SOL EUR` or `/crypto place BTC EUR`.
4. Find the board ID with `/crypto list`, then use `/crypto terminal <id> enable` with `cryptocraft.terminal.admin`.
5. Right-click the terminal or use `/crypto trade` within six blocks. Confirm a preview to submit an order. Use `/crypto orders` to see its progress and `/crypto depot` to see holdings.

The default computer consists of map screens and vanilla block display entities for its frame, stand, and keyboard. Enabling it raises a low display to height 2.0. Vanilla item frames snap to block positions; the housing follows the actual map surface, including the half-block shift of even-sized walls. No resource pack or world-block replacement is needed. Existing board ownership/region checks still apply; terminal edits require the terminal administration permission. All terminals connect to the same market. A terminal can be a chart or a six-coin market board.

`trading.terminal-style: labeled` uses the labeled board without computer decorations. Raise labeled terminals to at least 2.0 before switching to `computer`; an incompatible reload is rejected. Set `trading.access: anywhere` to allow order submission away from terminals. Depot, history, orders, and cancellation are available anywhere regardless of access mode. Access is checked again when the player confirms, so moving away or revoking a terminal invalidates an open preview.

## Prices, previews, and execution

All three economic modes use the same external quote cache as charts and alerts:

```text
unit price in server money = external reference price × units-per-reference
trade value = coin quantity × unit price
buy debit = trade value + buy fee
sell credit = trade value − sell fee
```

For example, at an external price of 50,000 EUR and a factor of 1, a budget of 100 with no fee buys 0.002 coins if that price is still executable. A 1% buy fee reduces the amount to approximately 0.001980198019 coins. The preview shows the reference price, age, estimated quantity, fees, maximum debit or minimum unit price, and execution mode. It requires confirmation and expires after 60 seconds.

Buying defaults to a total budget including fees. The quantity alternative shows a maximum budget derived from the price guard and fees; confirming approves that ceiling. Selling accepts a quantity; the menu also offers 25%, 50%, or 100% of available coins. The default guard permits a 1% move against the player. A buy's maximum unit price or a sell's minimum unit price only decides whether execution is allowed: the actual fill always uses the external price, including when it is more favorable.

An accepted order cannot execute from the quote used for its preview. It waits for a provider timestamp strictly later than its acceptance. A player-to-player fill needs a timestamp later than **both** orders. Buy orders become executable after funding is confirmed. Receipt time never substitutes for a missing provider timestamp for trading, and startup history cannot authorize trading. A stale, future-dated, missing, or unverified quote pauses execution. Expiry and cancellation still release reservations while prices are unavailable.

“Current” means the latest sufficiently fresh provider quote obtained through the configured polling lane, not a streaming exchange price. Open orders request further snapshots subject to the global minimum interval and API retry/backoff rules. With the default 60-second request floor, a new order may wait at least until the next eligible request; unchanged provider timestamps can require additional polls. An API outage can cause an order to expire without filling. Charts have their own visual stale threshold; trading independently enforces its stricter 120-second maximum age.

## Economic modes

| `trading.mode` | Counterparty and funding | Waiting and failure behavior |
| --- | --- | --- |
| `unlimited` | The server issues purchased coins and covers valid coin sales. Purchases add to the internal reserve; sales may issue additional server money. | Waits for an eligible quote and price guard. No player counterparty required. |
| `reserve` | Purchases fund the reserve. Sales pay from the reserve only. It starts at zero on a new installation. | An eligible sale with insufficient reserve is rejected and its coins are released. Admins can fund the reserve from their Vault accounts. |
| `p2p` | Buyers and sellers share a queue for each coin. Buyers reserve their total budget; sellers reserve their coins. Fees enter the internal reserve. | Oldest executable orders match first at the external price. Partial fills retain priority. No self-trades and no fallback to the server broker. |

In `p2p`, an unmatched order stays open until a match, cancellation, or expiry. Coin holdings can come from earlier server trading or an audited admin grant. An account cannot sell more than its available holdings. Orders keep running while owners are offline. Unspent budget and remaining coins are released after completion, expiry, or cancellation. Released money and sale proceeds are paid through Vault; temporary known failures leave the amount in the internal account for a later payout attempt.

Amounts use exact decimal accounting. Coin quantities have up to 12 decimal places. Vault money precision comes from the provider, with `money-decimals` as fallback when it reports no precision. Payouts round down to that precision; subunit remainders stay in the player's internal credit and can contribute to later orders/payouts. They are never silently deleted. Executions and transfers remain traceable in SQLite.

Mode changes are rejected while orders or unclear money movements remain. Currency, conversion factor, and money precision are locked after economic activity; an ordinary reload cannot reinterpret existing balances. Existing coins survive a mode change. Changing fee or guard defaults does not rewrite an already confirmed order's terms.

## Configuration defaults

Add or edit this section in `plugins/CryptoCraft/config.yml`, then run `/crypto reload`:

```yaml
trading:
  enabled: true
  mode: unlimited
  access: terminals
  terminal-style: computer
  terminal-radius: 6
  reference-currency: EUR
  units-per-reference: "1"
  buy-fee-percent: "0"
  sell-fee-percent: "0"
  price-tolerance-percent: "1"
  order-lifetime-seconds: 300
  maximum-quote-age-seconds: 120
  maximum-open-orders: 20
  minimum-order-value: "1"
  maximum-order-value: "1000000"
  money-decimals: 2
  disabled-coins: []
  display-disabled-coins: []
```

The reference currency must be configured under `currencies`. Display currency and trading reference currency are independent: opening a USD display does not change an EUR trading account. Disabling trading cancels open orders, preserves holdings, and releases reservations. Disabling a coin cancels its open orders and preserves its holdings. Hiding a coin removes it from new display selections while keeping existing boards and stored assets.

New installations include BTC, ETH, SOL, XRP, ADA, DOGE, LTC, BNB, AVAX, and LINK. Existing coin lists are retained. Administrators can use:

```text
/crypto coin list
/crypto coin search <name|symbol|provider-id>
/crypto coin add <SYMBOL> <provider-id>
/crypto coin enable <SYMBOL>
/crypto coin disable <SYMBOL>
/crypto coin show <SYMBOL>
/crypto coin hide <SYMBOL>
```

Search queries CoinGecko's coin catalog and caches the result. Added IDs must be unique; an existing symbol cannot be silently remapped. New additions are display-only until explicitly enabled for trading. For a custom compatible price provider, add its correct ID directly; the catalog search still uses CoinGecko. Configuring an ID does not guarantee that the provider will deliver a quote for it.

## Commands and permissions

| Command | Purpose |
| --- | --- |
| `/crypto trade [coin]` | Exchange menu and search |
| `/crypto trade buy <coin> <budget>` | Budget-buy preview, fee included |
| `/crypto trade buy-quantity <coin> <quantity>` | Quantity-buy preview with maximum budget |
| `/crypto trade sell <coin> <quantity>` | Quantity-sell preview |
| `/crypto depot [history]` | Available/reserved coins, internal credit, history |
| `/crypto orders [cancel <id>]` | Order status and confirmed cancellation |
| `/crypto market <coin,coin,...> [currency]` | One to six ordered rows, initially 2 × 2 maps |
| `/crypto edit <id> coins BTC,ETH,SOL` | Replace/reorder a market board's rows |
| `/crypto terminal <id> <enable\|disable>` | Approve/revoke a terminal |
| `/crypto economy status` | Economy health, reserve, unresolved transfers |
| `/crypto economy reserve <deposit\|withdraw> <amount>` | Transfer between the invoking admin's Vault account and the reserve |
| `/crypto economy grant <online-player\|uuid> <coin> <quantity> <reason>` | Audited coin grant; use a UUID for offline players |
| `/crypto economy resolve <transfer-id> <applied\|rejected> <reason>` | Record verified outcome of an uncertain Vault transfer |

`cryptocraft.use` is required for player commands. `cryptocraft.trade` allows submitting orders and defaults to everyone. `cryptocraft.terminal.admin` defaults to operators and is required for terminal approval/editing. `cryptocraft.economy.admin` defaults to operators and allows economy/coin administration; the configured general admin permission also allows these administrative commands. Board placement/edit/limit and WorldGuard permissions still apply. Players cannot cancel another player's order.

The inventory menus provide search, pagination, confirmations, watchlists, alerts, trading, depot, and orders. Menu text follows the server's `messages.yml` language (`de` or `en`); missing keys in older/custom message files fall back to bundled translations.

## Uncertain transfers and recovery

`plugins/CryptoCraft/economy.db` stores balances, reservations, orders, reserve, trades, transfer states, and journal entries. SQLite uses committed transactions, and Vault calls run on the Minecraft main thread. The provider and ledger cannot share one atomic transaction. CryptoCraft records its intent before calling Vault and records the result afterwards.

A crash between those steps, an exception, or an unverifiable response can leave a transfer `UNCERTAIN`. The affected account is frozen and the transfer is **not automatically withdrawn or deposited again**. This also prevents normal mode changes. An administrator must determine whether the provider applied the original movement:

1. Run `/crypto economy status` and record the transfer ID, player UUID, direction, and amount.
2. Compare the economy provider's authoritative records with the transfer and journal. A current balance alone may be insufficient if other plugins have changed it.
3. If the exact transfer was applied, use `/crypto economy resolve <id> applied <reason>`.
4. If the exact transfer was not applied, use `/crypto economy resolve <id> rejected <reason>`.

Resolution records the outcome in CryptoCraft; it does not repeat the external call. Include the evidence or incident reference in the reason. If the outcome cannot be established, leave it unresolved and frozen. Do not delete `economy.db` or restore an old copy independently of Vault data to “retry” a transfer; that can duplicate or lose money. Provider replacement is likewise subject to the persisted provider binding and must not be used to bypass reconciliation.

For backups, stop the server cleanly and copy the complete plugin directory together with the economy provider's data. Do not copy only the main SQLite file while the server is writing it; SQLite may have active WAL state. The YAML `.bak` recovery mechanism does not automatically roll the economy database back.

## Upgrading from 1.1.0

Back up both systems, stop the server, and replace the JAR. Existing charts, board IDs, owners, settings, configurations, and paper portfolios remain. Legacy boards load as ordinary nonterminal charts. Bundled defaults supply missing new settings/message keys, while the existing configured coin list is preserved. No existing chart becomes a trading terminal without an administrator enabling it. The new SQLite depot starts separately from `players.yml`; paper cash and paper holdings are not imported into the server economy.

When an older configuration has no `trading.reference-currency`, the upgrade adopts its valid `default-currency` and saves that setting after configuration and ledger validation succeed; the previous file is retained as `config.yml.bak`. A USD-only server therefore remains valid without adding EUR to its currencies. New installations retain the bundled EUR reference. Removing the reference key during reload keeps the active trading currency, and changing the display default later does not change the saved trading reference. An explicit reference-currency change remains subject to the ledger migration lock.

On a server without Vault or a registered economy provider, charts and the paper portfolio remain usable; the exchange reports unavailable. Configure and verify the desired mode, reference currency, factor, and provider before economic activity starts.
