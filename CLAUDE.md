# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Backend:** Java 21 + Spring Boot 3.5.0, built with **Gradle 8.13**
- **Frontend:** React 18 + MUI 9 + **TypeScript**
- **Database:** PostgreSQL — schema managed by hand-written SQL migrations, runtime access via Spring Data JPA / Hibernate
- **Broker:** Kraken REST API (`KrakenOrderClient`) — places 3 sequential limit orders per triangle cycle
- **Market Data:** WebSocket order book feeds from multiple exchanges (first: Kraken) — each exchange is scanned independently; trades execute entirely within one exchange
- **Auth:** Spring Security session-based authentication (HTTP-only cookie); role-based access (`ADMIN` / `QUANT` / `USER`)

## Project Structure

```
backend/
  build.gradle.kts               # Gradle build: Spring Boot plugin + dependencies
  settings.gradle.kts            # project name
  gradlew / gradlew.bat          # Gradle wrapper
  src/main/java/com/ib/arb/
    broker/          # KrakenOrderClient, KrakenAuth (HMAC signing + shared nonce)
    marketdata/      # WebSocket order book feeds (KrakenOrderBookFeed + future sources)
    position/        # PositionClient interface, KrakenPositionClient, PositionService
    scanner/         # ArbitrageEngine — triangle edge detection
    execution/       # AutoTrader — orchestrates scan → risk → order → record
    risk/            # RiskService — position limit, daily loss hard-stop, profit threshold
    analytics/       # AnalyticsService — win rate, Sharpe, equity curve, daily P&L
    scheduler/       # ArbitrageScheduler — @Scheduled loop
    alert/           # AlertService — email / desktop / SMS notifications
    api/             # REST controllers: ArbitrageController, AuthController, BrokerController,
                     #   OpenOrdersController, PositionsController, StatsController,
                     #   TradeController, TriangleController, SettingsController, UserController
    model/           # JPA entities: Trade, TradeLeg, Setting, User, TriangleConfig
    repository/      # Spring Data repos
    config/          # SecurityConfig (@EnableMethodSecurity), DataInitializer,
                     #   WebSocketConfig, DashboardWebSocketHandler
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
    context/         # AuthContext.tsx — session auth state (user + role)
    hooks/           # useDashboardSocket.ts, useAuth.ts
    pages/           # Dashboard, Trades, Positions, OpenOrders, Prices,
                     #   Settings, Triangles (Exchange Settings), Users, Login
    types.ts         # Shared TypeScript interfaces
  tsconfig.app.json
  tsconfig.node.json
  vite.config.ts

db/
  migrations/
    init.sql         # single combined migration: all tables + seed data

ansible/
  deploy.sh          # local: builds artifacts then runs playbook
  inventory.ini      # target host(s)
  requirements.yml   # community.postgresql collection
  group_vars/
    all.yml          # all deployment variables
  playbook.yml       # full deployment playbook
  templates/         # arb.env.j2, arb.service.j2, nginx-arb.conf.j2,
                     #   start/stop/restart/status.sh.j2
```

## Build & Run (backend)

```bash
cd backend && ./gradlew build       # compile + test
cd backend && ./gradlew bootRun     # run locally (requires PostgreSQL)
cd backend && ./gradlew bootJar     # fat jar → build/libs/arb.jar
cd backend && ./gradlew test        # run tests only
```

## Build & Run (frontend)

```bash
cd frontend && npm install
cd frontend && npm run dev          # dev server on http://localhost:5173
cd frontend && npm run build        # production build → dist/
cd frontend && npm run lint         # ESLint + TypeScript check
npx tsc --project tsconfig.app.json --noEmit  # type-check only
```

## Database Migrations

Single combined migration — apply on a fresh database:

```bash
psql -U postgres -d trades -f db/migrations/init.sql
```

`spring.jpa.hibernate.ddl-auto` is set to `validate` — Hibernate never modifies the schema.

## Deployment (Ansible)

Deploys backend JAR + frontend `dist/` to a single Ubuntu/Debian server. Builds locally, uploads artifacts, provisions PostgreSQL, installs systemd service behind nginx.

```bash
# 1. Edit ansible/inventory.ini  — set server IP + SSH key
# 2. Edit ansible/group_vars/all.yml — set db_password, admin_password
cd ansible && ./deploy.sh
```

**Credentials:**

| What | Variable | Default |
|---|---|---|
| PostgreSQL app user | `db_user` | `arb` |
| PostgreSQL app password | `db_password` | `change_me` |
| Web app admin username | `admin_username` | `admin` |
| Web app admin password | `admin_password` | `change_me` |

The web admin user is created by `DataInitializer` on first boot — not by `init.sql`.

**Server convenience scripts** (deployed to `/opt/arb/`):
- `./start.sh` / `./stop.sh` / `./restart.sh` / `./status.sh`

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

### Kraken Authentication (`KrakenAuth`)

Shared utility for HMAC-SHA512 signing used by both `KrakenOrderClient` and `KrakenPositionClient`. Contains a static `AtomicLong` nonce counter shared across all callers — use `KrakenAuth.nextNonce()` instead of `System.currentTimeMillis()` to avoid "EAPI:Invalid nonce" errors from concurrent requests.

### Exchange Positions (`PositionService` + `PositionClient`)

Strategy pattern: `PositionClient` interface per exchange, `KrakenPositionClient` implements it. `PositionService` orchestrates caching (TTL: `kraken.position-cache-ttl-ms`) across all registered clients. Asset key translation for Kraken: ISO currency → `"Z" + iso` (e.g., `USD` → `ZUSD`).

### Edge Detection (ArbitrageEngine)

For each triangle `(pair1, pair2, pair3)`:
- **Cycle A:** `edge = bid1 × bid2 − ask3`
- **Cycle B:** `edge = bid3 − ask1 × ask2`

Valid when `edge > arb.edge-threshold` (default `0.00025`). `scanForOpportunities()` returns the single best `Signal` across all active triangles (loaded from DB each cycle).

### Execution Flow (AutoTrader)

1. Skip if within cooldown or open-order limit reached
2. `ArbitrageEngine.scanForOpportunities()` → best `Signal` or empty
3. `hasBalanceForAllLegs()` — checks all 3 legs' spent currencies against live order book prices; BUY legs check quote currency (`orderSize`), SELL legs check base currency (`orderSize / bid`)
4. `RiskService.check(orderSize)` → position limit + daily loss hard-stop
5. `RiskService.checkProfit(minPercent, minUsd, edge, estimatedPnl)` → per-triangle profit threshold
6. **Simulation** (`simulation_mode = 1`): log orders, record as `SIMULATION` — no Kraken calls
7. **Live**: `KrakenOrderClient.placeComboOrder(signal, orderSize)` → 3 sequential limit orders
8. Save `Trade` + `TradeLeg` records (fluent setters); broadcast `DashboardSnapshot` over WebSocket

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

Seeded defaults: `position_limit=10000`, `max_daily_loss=-1000`, `simulation_mode=1`.

### `users`
| column | type | notes |
|---|---|---|
| id | BIGSERIAL PK | |
| username | TEXT UNIQUE NOT NULL | |
| password | TEXT (nullable) | BCrypt hash; null for OAuth users |
| role | TEXT NOT NULL DEFAULT 'USER' | `ADMIN`, `QUANT`, or `USER` |

Admin user seeded on first startup by `DataInitializer` with `role = 'ADMIN'`. Only `ADMIN` users can access `GET/POST/PATCH/DELETE /api/users`.

### `triangles`
| column | type | notes |
|---|---|---|
| id | BIGSERIAL PK | |
| exchange | TEXT | e.g. `KRAKEN` |
| pair1 | TEXT | e.g. `EURUSD` |
| pair2 | TEXT | e.g. `USDJPY` |
| pair3 | TEXT | e.g. `EURJPY` |
| min_profit_usd | DOUBLE | default `10` |
| min_profit_percent | DOUBLE | default `0.01` (1%) |
| status | TEXT | `ACTIVE` or `INACTIVE` |
| hits | BIGINT | count of filled trades |
| total_profit_usd | DOUBLE | cumulative P&L |

Seven triangles seeded by default (all KRAKEN exchange). `TriangleConfigRepository.incrementStats()` atomically updates `hits` and `total_profit_usd` after each filled trade.

## REST API

All endpoints require authentication (session cookie) except `/api/auth/login` and `/api/auth/me`.

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` → session cookie |
| POST | `/api/auth/logout` | Invalidate session |
| GET | `/api/auth/me` | `{username, role}` if authenticated |
| POST | `/api/arbitrage/start` | Set running = true |
| POST | `/api/arbitrage/stop` | Set running = false |
| GET | `/api/arbitrage/status` | `{running, stats}` |
| POST | `/api/arbitrage/manual-trade` | `{triangleId, cycle, legs}` → `{tradeId, status, pnl}` |
| GET | `/api/stats/daily-pnl` | `{dailyProfitAndLoss}` |
| GET | `/api/stats/drawdown` | `{drawdown}` |
| GET | `/api/stats/win-rate` | `{winRate}` |
| GET | `/api/stats/sharpe` | `{sharpe}` |
| GET | `/api/stats/arb` | `{detected, executed, missed, avgEdge}` |
| GET | `/api/stats/execution` | `{avgLatency, maxLatency, fillRate}` |
| GET | `/api/stats/equity` | `[{time, equity}]` |
| GET | `/api/broker/health` | `{connected}` |
| GET | `/api/trades` | Last 20 trades (no legs) |
| GET | `/api/trades/{id}` | Trade + all legs |
| GET/PUT | `/api/settings` | Read / update risk settings |
| GET | `/api/triangles` | List all triangle configs |
| POST | `/api/triangles` | Create a triangle config |
| PUT | `/api/triangles/{id}` | Update a triangle config |
| DELETE | `/api/triangles/{id}` | Delete a triangle config |
| GET | `/api/positions` | Exchange balances for all configured triangles |
| GET | `/api/orders/open` | Open orders across all exchanges |
| GET | `/api/users` | List users — **ADMIN only** |
| POST | `/api/users` | `{username, password, role}` → create user — **ADMIN only** |
| PATCH | `/api/users/{id}/role` | `{role}` → update user role — **ADMIN only** |
| DELETE | `/api/users/{id}` | Delete user (cannot delete self) — **ADMIN only** |

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
  frontend-url: ${FRONTEND_URL:http://localhost:5173}

alert:
  email-from: ${ALERT_EMAIL_FROM:}
  email-password: ${ALERT_EMAIL_PASS:}
  email-to: ${ALERT_EMAIL_TO:}
```

## Frontend (React + TypeScript + MUI)

Single-page dashboard. Auth state managed by `AuthProvider` — on load, calls `GET /api/auth/me` to restore session; shows login page if unauthenticated. `AuthUser` includes `role` — used by `canAccess()` to show/hide navigation:

| Role | Accessible pages |
|---|---|
| `USER` | Dashboard, Trades, Feeds |
| `QUANT` | Everything except Users |
| `ADMIN` | Everything |

**Shared types** (`src/types.ts`): `CycleDirection`, `LegDirection`, `TradeStatus`, `LegStatus`, `Trade`, `TradeLeg`, `TradeDetail`, `ArbitrageStats`, `DashboardSnapshot`, `EquityPoint`, `Setting`, `ExecutionStats`, `AnalyticsData`, `AuthUser` (`{username, role}`), `AppUser` (`{id, username, role}`), `PriceSnapshot`, `ManualLeg`, `TriangleStatus`, `TriangleConfig`, `BalanceEntry`, `OpenOrder`.

**WebSocket** (`useDashboardSocket`): connects to `/ws/dashboard`, returns `DashboardSnapshot | null`.

```typescript
interface DashboardSnapshot {
  dailyProfitAndLoss: number;
  brokerConnected: boolean;
  arbStats: ArbitrageStats;
  recentTrades: Trade[];
  prices: PriceSnapshot[];
  tradeInProgress: boolean;
}
```

**Pages:**
- `Dashboard` — KPI cards, Start/Stop button, trade-in-progress banner, scanner/execution stats, equity curve (Recharts)
- `Trades` — trade history table; click row to open leg detail dialog
- `Positions` — exchange balances (polls `/api/positions` every 5s)
- `Open Orders` — live Kraken open orders (polls `/api/orders/open` every 5s)
- `Feeds` — live bid/ask table, streamed via WebSocket
- `Exchange Settings` — CRUD for triangle configs; play button opens manual trade dialog
- `Settings` — position limit, max daily loss, simulation mode toggle
- `Users` — user list with inline role editing (dropdown per row) + create form with role selector + delete; **visible to ADMIN only**
- `Login` — username/password form

**Vite proxy** (dev): `/api` and `/ws` → `http(s)://localhost:8080`. Production: nginx proxies `/api/` and `/ws/` to Spring Boot on port 8080.

## Risk Rules (enforced before every order)
1. `orderSize ≤ position_limit` (from `settings` table)
2. `dailyPnl > max_daily_loss` (from `settings` table)
3. Open order count < `max-open-orders`
4. All 3 legs have sufficient exchange balance (`PositionService`)
5. `edge ≥ min_profit_percent` and `estimatedPnl ≥ min_profit_usd` (per-triangle thresholds)

Any failure aborts the cycle and increments the `missed` counter.

## Java Conventions

Use `var` for local variables where the type is clear from the right-hand side. Keep explicit types for fields, method parameters, and return types. `Trade` and `TradeLeg` use fluent setters (return `this`) for method chaining.

```java
// preferred
var signal = arbitrageEngine.scanForOpportunities();

// fluent entity construction
var trade = new Trade()
    .setTime(LocalDateTime.now())
    .setDirection(signal.cycle())
    .setStatus("SIMULATION");
```
