CREATE TABLE IF NOT EXISTS "triangles" (
    "id"                 BIGSERIAL        NOT NULL,
    "exchange"           TEXT             NOT NULL,
    "pair1"              TEXT             NOT NULL,
    "pair2"              TEXT             NOT NULL,
    "pair3"              TEXT             NOT NULL,
    "min_profit_usd"     DOUBLE PRECISION NOT NULL DEFAULT 0,
    "min_profit_percent" DOUBLE PRECISION NOT NULL DEFAULT 0.00025,
    "status"             TEXT             NOT NULL DEFAULT 'ACTIVE',
    "hits"               BIGINT           NOT NULL DEFAULT 0,
    "total_profit_usd"   DOUBLE PRECISION NOT NULL DEFAULT 0,

    CONSTRAINT "triangles_pkey" PRIMARY KEY ("id")
);

INSERT INTO "triangles" ("exchange","pair1","pair2","pair3","min_profit_usd","min_profit_percent","status","hits","total_profit_usd") VALUES
    ('KRAKEN','EURUSD','USDJPY','EURJPY', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','GBPUSD','USDCHF','GBPCHF',0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','EURGBP','GBPUSD','EURUSD', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','AUDUSD','USDJPY','AUDJPY', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','USDCAD','CADJPY','USDJPY', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','EURCHF','CHFJPY','EURJPY', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','AUDCAD','CADJPY','AUDJPY', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','NZDUSD','USDJPY','NZDJPY', 0,0.00025,'ACTIVE',0,0),
    ('KRAKEN','EURUSD','USDTRY','EURTRY', 0,0.00025,'ACTIVE',0,0)
ON CONFLICT DO NOTHING;
