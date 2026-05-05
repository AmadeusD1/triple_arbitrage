CREATE TABLE IF NOT EXISTS "trades" (
    "id"         BIGSERIAL        NOT NULL,
    "time"       TIMESTAMP(3)     NOT NULL,
    "direction"  TEXT             NOT NULL,
    "spread"     DOUBLE PRECISION NOT NULL,
    "pnl"        DOUBLE PRECISION NOT NULL,
    "status"     TEXT             NOT NULL,
    "latency_ms" DOUBLE PRECISION NOT NULL,

    CONSTRAINT "trades_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "trade_legs" (
    "id"        BIGSERIAL        NOT NULL,
    "trade_id"  BIGINT           NOT NULL,
    "leg_index" INTEGER          NOT NULL,
    "pair"      TEXT             NOT NULL,
    "direction" TEXT             NOT NULL,
    "price"     DOUBLE PRECISION NOT NULL,
    "volume"    DOUBLE PRECISION NOT NULL,
    "status"    TEXT             NOT NULL,
    "order_id"  TEXT,

    CONSTRAINT "trade_legs_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "trade_legs_trade_fk" FOREIGN KEY ("trade_id") REFERENCES "trades" ("id")
);

CREATE TABLE IF NOT EXISTS "settings" (
    "key"   TEXT             NOT NULL,
    "value" DOUBLE PRECISION NOT NULL,

    CONSTRAINT "settings_pkey" PRIMARY KEY ("key")
);

CREATE TABLE IF NOT EXISTS "users" (
    "id"       BIGSERIAL NOT NULL,
    "username" TEXT      NOT NULL UNIQUE,
    "password" TEXT,
    "role"     TEXT      NOT NULL DEFAULT 'USER',

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

INSERT INTO "settings" ("key", "value") VALUES
    ('position_limit',  10000),
    ('max_daily_loss',  -1000),
    ('simulation_mode', 1)
ON CONFLICT ("key") DO NOTHING;

CREATE TABLE IF NOT EXISTS "triangles" (
    "id"                 BIGSERIAL        NOT NULL,
    "exchange"           TEXT             NOT NULL,
    "pair1"              TEXT             NOT NULL,
    "pair2"              TEXT             NOT NULL,
    "pair3"              TEXT             NOT NULL,
    "min_profit_usd"     DOUBLE PRECISION NOT NULL DEFAULT 10,
    "min_profit_percent" DOUBLE PRECISION NOT NULL DEFAULT 0.01,
    "status"             TEXT             NOT NULL DEFAULT 'ACTIVE',
    "hits"               BIGINT           NOT NULL DEFAULT 0,
    "total_profit_usd"   DOUBLE PRECISION NOT NULL DEFAULT 0,

    CONSTRAINT "triangles_pkey" PRIMARY KEY ("id")
);

-- 7 triangles using only Kraken-supported FX spot pairs
-- Supported: AUD/JPY AUD/USD EUR/AUD EUR/CAD EUR/CHF EUR/GBP EUR/JPY EUR/USD GBP/USD USD/CAD USD/CHF USD/JPY
INSERT INTO "triangles" ("exchange","pair1","pair2","pair3","min_profit_usd","min_profit_percent","status","hits","total_profit_usd") VALUES
    ('KRAKEN','EURUSD','USDJPY','EURJPY', 10,0.01,'ACTIVE',0,0),
    ('KRAKEN','EURUSD','USDCHF','EURCHF', 10,0.01,'ACTIVE',0,0),
    ('KRAKEN','EURGBP','GBPUSD','EURUSD', 10,0.01,'ACTIVE',0,0),
    ('KRAKEN','AUDUSD','USDJPY','AUDJPY', 10,0.01,'ACTIVE',0,0),
    ('KRAKEN','EURUSD','USDCAD','EURCAD', 10,0.01,'ACTIVE',0,0),
    ('KRAKEN','EURAUD','AUDUSD','EURUSD', 10,0.01,'ACTIVE',0,0),
    ('KRAKEN','EURAUD','AUDJPY','EURJPY', 10,0.01,'ACTIVE',0,0)
ON CONFLICT DO NOTHING;
