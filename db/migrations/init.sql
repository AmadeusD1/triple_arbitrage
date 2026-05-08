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
    "cycle"              TEXT             NOT NULL DEFAULT 'BBS',

    CONSTRAINT "triangles_pkey" PRIMARY KEY ("id")
);

-- cycle BBS (BUY,BUY,SELL): edge = bid1×bid2 − ask3
-- cycle BSS (BUY,SELL,SELL): edge = bid1 − ask2×ask3
-- cycle BSB (BUY,SELL,BUY):  edge = bid1×bid3 − ask2
-- cycle SBS (SELL,BUY,SELL): edge = bid2 − ask1×ask3
INSERT INTO "triangles" ("exchange","pair1","pair2","pair3","min_profit_usd","min_profit_percent","status","hits","total_profit_usd","cycle") VALUES
    ('KRAKEN','GBPUSD','EURGBP','EURUSD', 10,0.01,'ACTIVE',0,0,'BBS'),
    ('KRAKEN','EURUSD','EURGBP','GBPUSD', 10,0.01,'ACTIVE',0,0,'BSS'),
    ('KRAKEN','EURUSD','EURCHF','USDCHF', 10,0.01,'ACTIVE',0,0,'BSB'),
    ('KRAKEN','USDCHF','EURCHF','EURUSD', 10,0.01,'ACTIVE',0,0,'SBS')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS "missed_opportunities" (
    "id"          BIGSERIAL        NOT NULL,
    "time"        TIMESTAMP(3)     NOT NULL,
    "triangle_id" BIGINT           NOT NULL,
    "exchange"    TEXT             NOT NULL,
    "pair1"       TEXT             NOT NULL,
    "pair2"       TEXT             NOT NULL,
    "pair3"       TEXT             NOT NULL,
    "cycle"       TEXT             NOT NULL,
    "edge"        DOUBLE PRECISION NOT NULL,
    "order_size"  DOUBLE PRECISION NOT NULL,
    "rejection"   TEXT             NOT NULL,
    "reason"      TEXT,

    CONSTRAINT "missed_opportunities_pkey" PRIMARY KEY ("id")
);
