-- Add cycle column to triangles table and seed the 4 example triangles
ALTER TABLE triangles ADD COLUMN IF NOT EXISTS cycle TEXT NOT NULL DEFAULT 'A';

INSERT INTO triangles (exchange,pair1,pair2,pair3,min_profit_usd,min_profit_percent,status,hits,total_profit_usd,cycle) VALUES
    ('KRAKEN','GBPUSD','EURGBP','EURUSD',10,0.01,'ACTIVE',0,0,'A'),
    ('KRAKEN','EURUSD','EURGBP','GBPUSD',10,0.01,'ACTIVE',0,0,'B'),
    ('KRAKEN','EURUSD','EURCHF','USDCHF',10,0.01,'ACTIVE',0,0,'C'),
    ('KRAKEN','USDCHF','EURCHF','EURUSD',10,0.01,'ACTIVE',0,0,'D')
ON CONFLICT DO NOTHING;
