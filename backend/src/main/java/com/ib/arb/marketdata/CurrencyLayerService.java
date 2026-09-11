package com.ib.arb.marketdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls apilayer.net for live USD-denominated FX rates and exposes them via
 * {@link #getRate(String)}. Acts as the primary rate source in
 * {@link CurrencyRateFeed#getRate(String)}, which falls back to its WebSocket
 * feed when a currency is not present here.
 *
 * <p>Rates are stored as "USD value of 1 unit of the given currency"
 * (e.g. rate("EUR") ≈ 1.08, rate("TRY") ≈ 0.022). A rate of {@code 0.0}
 * means the currency was not returned by the API.
 */
@Service
public class CurrencyLayerService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyLayerService.class);

    private static final String USD_CURRENCIES = "TRY,KRW,JPY,AUD,EUR,GBP,NZD,CAD,CHF,AED";
    private static final String EUR_CURRENCIES = "CHF,GBP";

    @Value("${currency.layer.api-key:e31dcd26cbbffa7b035150766e41ddb6}")
    private String apiKey;

    // USD-denominated: "EUR" → EUR/USD, "TRY" → TRY/USD, etc.
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    // EUR cross rates: "EUR/CHF" → rate, "EUR/GBP" → rate
    private final Map<String, Double> eurCrossRates = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.create();

    @PostConstruct
    public void init() {
        fetch();
    }

    /** Returns all rates: USD-denominated keyed as "CCY/USD", plus EUR cross rates. */
    public Map<String, Double> getAllRates() {
        var merged = new java.util.HashMap<String, Double>();
        rates.forEach((ccy, rate) -> merged.put(ccy + "/USD", rate));
        merged.putAll(eurCrossRates);
        return java.util.Collections.unmodifiableMap(merged);
    }

    /** Returns the USD value of 1 unit of {@code isoCurrency}, or {@code 0.0} if unknown. */
    public double getRate(String isoCurrency) {
        if ("USD".equals(isoCurrency)) return 1.0;
        return rates.getOrDefault(isoCurrency, 0.0);
    }

    @Scheduled(fixedDelayString = "${currency.layer.poll-interval-ms:10000}")
    public void fetch() {
        fetchUsd();
        fetchEur();
    }

    private void fetchUsd() {
        var url = "http://apilayer.net/api/live?access_key=" + apiKey
                + "&source=USD&currencies=" + USD_CURRENCIES;
        try {
            var response = restClient.get().uri(url).retrieve().body(CurrencyLayerResponse.class);
            if (response == null || !response.success || response.quotes == null) {
                log.warn("[CurrencyLayer] USD fetch returned unsuccessful response"); return;
            }
            response.quotes.forEach((pair, value) -> {
                if (pair.length() == 6 && pair.startsWith("USD") && value > 0) {
                    var ccy = pair.substring(3);
                    var rate = 1.0 / value;
                    if (!Double.valueOf(rate).equals(rates.put(ccy, rate)))
                        log.info("[CurrencyLayer] {}/USD = {}", ccy, rate);
                }
            });
        } catch (Exception e) {
            log.warn("[CurrencyLayer] USD fetch failed: {}", e.getMessage());
        }
    }

    private void fetchEur() {
        var url = "http://apilayer.net/api/live?access_key=" + apiKey
                + "&source=EUR&currencies=" + EUR_CURRENCIES;
        try {
            var response = restClient.get().uri(url).retrieve().body(CurrencyLayerResponse.class);
            if (response == null || !response.success || response.quotes == null) {
                log.warn("[CurrencyLayer] EUR fetch returned unsuccessful response"); return;
            }
            response.quotes.forEach((pair, value) -> {
                if (pair.length() == 6 && pair.startsWith("EUR") && value > 0) {
                    var key = "EUR/" + pair.substring(3);
                    if (!Double.valueOf(value).equals(eurCrossRates.put(key, value)))
                        log.info("[CurrencyLayer] {} = {}", key, value);
                }
            });
        } catch (Exception e) {
            log.warn("[CurrencyLayer] EUR fetch failed: {}", e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CurrencyLayerResponse {
        public boolean success;
        public Map<String, Double> quotes;
    }
}
