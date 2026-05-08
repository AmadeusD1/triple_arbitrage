package com.ib.arb.marketdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client that consumes aggregated USD rates from the crypto-aggregator service.
 *
 * <p>Receives {@code Map<String,Double>} JSON messages with keys in {@code "CCY/USD"} format
 * (e.g. {@code "BTC/USD"}, {@code "ETH/USD"}). Reconnects automatically on disconnect.
 *
 * <p>Used by {@code AutoTrader.getUSDValue()} as the primary source for currency-to-USD
 * conversion; Kraken order book snapshots serve as fallback for FX pairs not yet in the feed.
 */
@Service
public class CurrencyRateFeed {

    private static final Logger log = LoggerFactory.getLogger(CurrencyRateFeed.class);

    @Value("${currency.feed-url:ws://localhost:7070/api/ws/global}")
    private String feedUrl;

    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private volatile boolean connected = false;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService reconnectScheduler =
        Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void start() {
        connect();
    }

    /** Returns the USD value of 1 unit of {@code isoCurrency}. Returns {@code 1.0} for USD,
     *  {@code 0.0} if the rate is not yet available. Tries {@code CCY/USD} first; falls back
     *  to {@code 1 / USD/CCY} if only the inverse pair is published. */
    public double getRate(String isoCurrency) {
        if ("USD".equals(isoCurrency)) return 1.0;
        var direct = rates.get(isoCurrency + "/USD");
        if (direct != null) return direct;
        var inverse = rates.get("USD/" + isoCurrency);
        if (inverse != null && inverse != 0.0) return 1.0 / inverse;
        return 0.0;
    }

    public Map<String, Double> getAllRates() {
        return Collections.unmodifiableMap(rates);
    }

    public boolean isConnected() {
        return connected;
    }

    private void connect() {
        try {
            log.info("[FX] Connecting to {}", feedUrl);
            http.newWebSocketBuilder()
                .buildAsync(URI.create(feedUrl), new Listener())
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        log.warn("[FX] Connection failed: {}", ex.getMessage());
                        scheduleReconnect();
                    }
                });
        } catch (Exception e) {
            log.warn("[FX] Connection error: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected = false;
        reconnectScheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
    }

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            log.info("[FX] Connected to {}", feedUrl);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("[FX] WebSocket error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("[FX] Disconnected ({}): {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }
    }

    private void handleMessage(String json) {
        try {
            var update = mapper.readValue(json, new TypeReference<Map<String, Double>>() {});
            rates.putAll(update);
        } catch (Exception e) {
            log.debug("[FX] Failed to parse message: {}", e.getMessage());
        }
    }
}
