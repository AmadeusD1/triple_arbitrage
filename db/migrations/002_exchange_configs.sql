-- Add exchange column to existing trades rows
ALTER TABLE trades ADD COLUMN IF NOT EXISTS exchange TEXT DEFAULT 'KRAKEN';

-- Exchange configurations: one row per supported exchange
CREATE TABLE IF NOT EXISTS exchange_configs (
    id                 BIGSERIAL        NOT NULL,
    exchange           TEXT             NOT NULL,
    enabled            BOOLEAN          NOT NULL DEFAULT false,
    api_key            TEXT,
    api_secret         TEXT,
    api_passphrase     TEXT,
    ws_url             TEXT,
    order_size_usd     DOUBLE PRECISION NOT NULL DEFAULT 100000,
    position_limit_usd DOUBLE PRECISION NOT NULL DEFAULT 10000,
    max_daily_loss_usd DOUBLE PRECISION NOT NULL DEFAULT -1000,
    created_at         TIMESTAMP        NOT NULL DEFAULT now(),

    CONSTRAINT exchange_configs_pkey PRIMARY KEY (id),
    CONSTRAINT exchange_configs_exchange_uidx UNIQUE (exchange)
);
