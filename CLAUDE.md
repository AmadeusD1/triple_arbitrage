# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Backend:** Java 21 + Spring Boot 3.5.0, built with **Gradle 8.13**
- **Frontend:** React 18 + MUI 9 + **TypeScript**
- **Database:** PostgreSQL — schema managed by hand-written SQL migrations, runtime access via Spring Data JPA / Hibernate
- **Broker:** Kraken REST API (`KrakenOrderClient`) — places 3 sequential limit orders per triangle cycle
- **Market Data:** WebSocket order book feeds from multiple exchanges (first: Kraken) — each exchange is scanned independently; trades execute entirely within one exchange
- **Auth:** Spring Security session-based authentication (HTTP-only cookie)

## Project Structure

```
backend/
  build.gradle.kts               # Gradle build: Spring Boot plugin + dependencies
  settings.gradle.kts            # project name
  gradlew / gradlew.bat          # Gradle wrapper
  src/main/java/com/ib/arb/
    broker/          # KrakenOrderClient — places orders, tracks open count, simulation mode
    marketdata/      # WebSocket order book feeds (KrakenOrderBookFeed + future sources)
    position/        # Exchange position fetching + balance validation (Kraken REST)
    scanner/         # ArbitrageEngine — triangle edge detection
    execution/       # AutoTrader — orchestrates scan → risk → order → record
    risk/            # RiskService — position limit, daily loss hard-stop
    analytics/       # AnalyticsService — win rate, Sharpe, equity curve, daily P&L
    scheduler/       # ArbitrageScheduler — @Scheduled loop
    alert/           # AlertService — email / desktop / SMS notifications
    api/             # REST controllers (ArbitrageController, AuthController, BrokerController, StatsController, TradeController, TriangleController, SettingsController)
    model/           # JPA entities: Trade, TradeLeg, Setting, User, TriangleConfig
    repository/      # Spring Data repos
    config/          # SecurityConfig, DataInitializer, WebSocketConfig, DashboardWebSocketHandler
  src/main/resources/
    application.yml
  src/test/java/com/ib/arb/
    scanner/         # ArbitrageEngineTest
    execution/       # AutoTraderTest
    risk/            # RiskServiceTest
    analytics/       # AnalyticsServiceTest
    marketdata/      # KrakenOrderBookFeedTest

frontend/
  src/
    api/             # rest.ts — typed Axios client with 401 interceptor
    context/         # AuthContext.tsx — session auth state
    hooks/           # useDashboardSocket.ts, useAuth.ts
    pages/           # Dashboard.tsx, Prices.tsx, Settings.tsx, Triangles.tsx, Login.tsx
    types.ts         # Shared TypeScript interfaces
  tsconfig.app.json  # TypeScript config for src/
  tsconfig.node.json # TypeScript config for vite.config.ts
  vite.config.ts

db/
  migrations/
    init.sql                 # all tables: trades, trade_legs, settings, users, triangles + seed data
```

## Build & Run (backend)

```bash
# Build
cd backend && ./gradlew build

# Run (requires PostgreSQL — apply all migrations first)
cd backend && ./gradlew bootRun

# Fat jar (output: build/libs/arb.jar)
cd backend && ./gradlew bootJar

# Run tests
cd backend && ./gradlew test
```

## Build & Run (frontend)

```bash
cd frontend && npm install
cd frontend && npm run dev       # dev server on http://localhost:5173
cd frontend && npm run build     # production build
cd frontend && npm run lint      # ESLint + TypeScript check
npx tsc --project tsconfig.app.json --noEmit  # type-check only
```

## Database Migrations

Apply with `psql`:

```bash
psql -U postgres -d trades -f db/migrations/init.sql
```

`spring.jpa.hibernate.ddl-auto` is set to `validate` — Hibernate never modifies the schema.

## Core Domain Logic

### Order Book Data (KrakenOrderBookFeed)

Each exchange implements `OrderBookFeed`:

```java
public interface OrderBookFeed {
    Exchange getExchange();
    OrderBook getSnapshot(String pair);   // non-blocking, reads in-memory state
    void subscribe(List<String> pairs);
    boolean isConnected();
}
```

`KrakenOrderBookFeed` connects to `wss://ws.kraken.com/v2`, subscribes to `book` channel for required pairs, and maintains an in-memory snapshot updated on each diff message. Reconnects automatically. Pair naming convention: `"EURUSD"` in code ↔ `"EUR/USD"` in Kraken API (use `KrakenOrderBookFeed.toKrakenSymbol()` / `toPair()` to convert).

### Edge Detection (ArbitrageEngine)

Nine hardcoded FX triangles. For each triangle `(pair1, pair2, pair3)`:
- **Cycle A:** `edge = bid1 × bid2 − ask3`
- **Cycle B:** `edge = bid3 − ask1 × ask2`

Valid when `edge > arb.edge-threshold` (default `0.00025`). `scanForOpportunities()` returns the single best `Signal` across all active triangles (loaded from DB each cycle).

### Execution Flow (AutoTrader)

1. `ArbitrageEngine.scanForOpportunities()` → best `Signal` or empty
2. `PositionService.hasAvailableBalance(exchange, currency, amount)` → balance check
3. `RiskService.check(orderSize)` → position limit + daily loss hard-stop
4. **Simulation mode** (`simulation_mode = 1` in settings): log the intended orders, treat as filled — no real Kraken calls
5. **Live mode**: `KrakenOrderClient.placeComboOrder(signal, orderSize)` → 3 sequential limit orders; returns `List<LegResult>` with per-leg Kraken txid
6. Save `Trade` + `TradeLeg` records; broadcast `DashboardSnapshot` over WebSocket

### Simulation Mode

Controlled by the `simulation_mode` setting (1 = on, 0 = off; default 1 = safe). When on:
- `AutoTrader` logs `[SIM] Cycle A | BUY EURUSD, BUY USDJPY, SELL EURJPY | profit=...`
- Trade is recorded as `SIMULATION` with leg status `SIMULATED`
- No HTTP calls to Kraken
- `KrakenOrderClient.isConnected()` returns `true` regardless of API credentials
- Toggled in the Settings UI

### Scheduler (ArbitrageScheduler)

`@Scheduled(fixedDelayString = "${arb.scan-interval-ms}")` drives `AutoTrader.attemptArbitrage()`. Respects a `running` flag toggled by `/api/arbitrage/start` and `/api/arbitrage/stop`. When `running=false` it still calls `autoTrader.broadcast()` so the Prices tab stays live. Auto-starts on boot when simulation mode is enabled. Skips cycle if open orders ≥ `max-open-orders` (default `1`).

## Database Schema

### `trades`
| column | type | notes |
|---|---|---|
| id | BIGSERIAL PK | |
| time | TIMESTAMP | |
| direction | TEXT | `A` or `B` (cycle) |
| spread | DOUBLE | profit edge |
| pnl | DOUBLE | estimated P&L in USD |
| status | TEXT | `FILLED`, `CANCELLED`, or `SIMULATION` |
| latency_ms | DOUBLE | |

### `trade_legs`
| column | type | notes |
|---|---|---|
| id | BIGSERIAL PK | |
| trade_id | BIGINT FK | → trades.id |
| leg_index | INTEGER | 1, 2, or 3 |
| pair | TEXT | e.g. `EURUSD` |
| direction | TEXT | `BUY` or `SELL` |
| price | DOUBLE | execution price |
| volume | DOUBLE | base currency amount |
| status | TEXT | `FILLED`, `FAILED`, `SIMULATED` |
| order_id | TEXT (nullable) | Kraken txid |

### `settings`
| column | type | default |
|---|---|---|
| key | TEXT PK | |
| value | DOUBLE | |

Seeded defaults: `position_limit=50000`, `max_daily_loss=-1000`, `simulation_mode=1`.

### `users`
| column | type |
|---|---|
| id | BIGSERIAL PK |
| username | TEXT UNIQUE |
| password | TEXT (BCrypt hash) |

Admin user seeded on first startup by `DataInitializer`.

### `triangles`
| column | type | notes |
|---|---|---|
| id | BIGSERIAL PK | |
| exchange | TEXT | e.g. `KRAKEN` |
| pair1 | TEXT | e.g. `EURUSD` |
| pair2 | TEXT | e.g. `USDJPY` |
| pair3 | TEXT | e.g. `EURJPY` |
| min_profit_usd | DOUBLE | minimum absolute profit threshold |
| min_profit_percent | DOUBLE | minimum edge threshold (e.g. `0.00025`) |
| status | TEXT | `ACTIVE` or `INACTIVE` |
| hits | BIGINT | count of filled trades for this triangle |
| total_profit_usd | DOUBLE | cumulative P&L for this triangle |

Seven triangles seeded by default (all using KRAKEN exchange). `TriangleConfigRepository.incrementStats()` atomically increments `hits` and `total_profit_usd` after each filled trade.

## REST API

All endpoints require authentication (session cookie) except `/api/auth/login` and `/api/auth/me`.

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` → session cookie |
| POST | `/api/auth/logout` | Invalidate session |
| GET | `/api/auth/me` | `{username}` if authenticated |
| POST | `/api/arbitrage/start` | Set running = true |
| POST | `/api/arbitrage/stop` | Set running = false |
| GET | `/api/arbitrage/status` | `{running, stats}` |
| GET | `/api/stats/daily-pnl` | `{dailyProfitAndLoss}` |
| GET | `/api/stats/drawdown` | `{drawdown}` |
| GET | `/api/stats/win-rate` | `{winRate}` |
| GET | `/api/stats/sharpe` | `{sharpe}` |
| GET | `/api/stats/arb` | `{detected, executed, missed, avgEdge}` |
| GET | `/api/stats/execution` | `{avgLatency, maxLatency, fillRate}` |
| GET | `/api/stats/equity` | `[{time, equity}]` — full equity curve |
| GET | `/api/broker/health` | `{connected}` |
| GET | `/api/trades` | Last 20 trades (no legs) |
| GET | `/api/trades/{id}` | Trade + all legs |
| GET/PUT | `/api/settings` | Read / update risk settings |
| GET | `/api/triangles` | List all triangle configs |
| POST | `/api/triangles` | Create a triangle config |
| PUT | `/api/triangles/{id}` | Update a triangle config |
| DELETE | `/api/triangles/{id}` | Delete a triangle config |
| POST | `/api/arbitrage/manual-trade` | `{triangleId, cycle, legs}` → `{tradeId, status, pnl}` — execute a manual trade bypassing cooldown |

## Key Configuration (`application.yml`)

```yaml
arb:
  order-size-usd: 100000
  edge-threshold: 0.00025
  max-open-orders: 1
  scan-interval-ms: 5000
  trade-cooldown-ms: 10000

kraken:
  ws-url: wss://ws.kraken.com/v2
  api-key: ${KRAKEN_API_KEY:}
  api-secret: ${KRAKEN_API_SECRET:}
  position-cache-ttl-ms: 2000

app:
  admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin}

alert:
  email-from: ${ALERT_EMAIL_FROM:}
  email-password: ${ALERT_EMAIL_PASS:}
  email-to: ${ALERT_EMAIL_TO:}
```

## Frontend (React + TypeScript + MUI)

Single-page dashboard. Auth state managed by `AuthProvider` — on load, calls `GET /api/auth/me` to restore session; shows login page if unauthenticated.

**Shared types** (`src/types.ts`): `CycleDirection`, `LegDirection`, `TradeStatus` (`FILLED | CANCELLED | SIMULATION`), `LegStatus`, `Trade`, `TradeLeg`, `TradeDetail`, `ArbitrageStats`, `DashboardSnapshot`, `EquityPoint`, `Setting`, `ExecutionStats`, `AnalyticsData`, `AuthUser`, `PriceSnapshot`, `ManualLeg`, `TriangleStatus`, `TriangleConfig`.

**WebSocket** (`useDashboardSocket`): connects to `/ws/dashboard`, returns `DashboardSnapshot | null`. The backend broadcasts after every scheduler cycle (even when trading is paused):
```typescript
interface DashboardSnapshot {
  dailyProfitAndLoss: number;
  brokerConnected: boolean;
  arbStats: ArbitrageStats;       // detected / executed / missed / avgEdge
  recentTrades: Trade[];          // last 20 (no legs — fetch /api/trades/{id} on click)
  prices: PriceSnapshot[];        // current bid/ask for all subscribed pairs
  tradeInProgress: boolean;       // true while live orders are being placed
}
```

**Pages:**
- `Dashboard` — KPI cards, Start/Stop button, trade-in-progress banner, scanner/execution stats, equity curve chart (Recharts), trades table. Click a trade row to open `TradeDetailDialog` which fetches legs via `GET /api/trades/{id}`.
- `Prices` — live bid/ask table for all subscribed pairs, streamed via WebSocket.
- `Settings` — position limit, max daily loss, simulation mode toggle.
- `Triangles` — CRUD for triangle configs; play button opens a manual trade dialog with per-leg editable price and volume (pre-filled from live prices).
- `Login` — username/password form, inline error on 401.

**Vite proxy** (dev): `/api` → `http://localhost:8080`, `/ws` → `ws://localhost:8080`. This makes session cookies work without CORS issues.

## Risk Rules (enforced before every order)
1. `orderSize ≤ position_limit` (from `settings` table)
2. `dailyPnl > max_daily_loss` (from `settings` table)
3. Open order count < `MAX_OPEN_ORDERS`
4. Sufficient exchange balance for each leg (`PositionService`)

All four must pass; any failure aborts the cycle and increments `missed` counter.

## Java Conventions

Use `var` for local variables where the type is clear from the right-hand side. Keep explicit types for fields, method parameters, and return types.

```java
// preferred
var signal = arbitrageEngine.scanForOpportunities();
var trades = tradeRepo.findTop20ByOrderByTimeDesc();

// keep explicit
private final ArbitrageEngine arbitrageEngine;
public Optional<Signal> scanForOpportunities() { ... }
```
