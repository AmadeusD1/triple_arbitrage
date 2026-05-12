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

    private static final String CURRENCIES = "TRY,KRW,JPY,AUD,EUR,GBP,NZD,CAD,BTC,CHF,AED";

    @Value("${currency.layer.api-key:e31dcd26cbbffa7b035150766e41ddb6}")
    private String apiKey;

    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.create();

    @PostConstruct
    public void init() {
        fetch();
    }

    /** Returns the USD value of 1 unit of {@code isoCurrency}, or {@code 0.0} if unknown. */
    public double getRate(String isoCurrency) {
        if ("USD".equals(isoCurrency)) return 1.0;
        return rates.getOrDefault(isoCurrency, 0.0);
    }

    @Scheduled(fixedDelayString = "${currency.layer.poll-interval-ms:10000}")
    public void fetch() {
        var url = "http://apilayer.net/api/live?access_key=" + apiKey
                + "&source=USD&currencies=" + CURRENCIES;
        try {
            var response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(CurrencyLayerResponse.class);
            if (response == null || !response.success || response.quotes == null) {
                log.warn("[CurrencyLayer] API returned an unsuccessful response");
                return;
            }
            response.quotes.forEach((pair, value) -> {
                // pair = "USDXXX" (6 chars), value = USD units per 1 USD → invert to get USD per 1 XXX
                if (pair.length() == 6 && pair.startsWith("USD") && value > 0) {
                    rates.put(pair.substring(3), 1.0 / value);
                }
            });
            log.info("[CurrencyLayer] Fetched {} rates", rates.size());
        } catch (Exception e) {
            log.warn("[CurrencyLayer] Fetch failed: {}", e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CurrencyLayerResponse {
        public boolean success;
        public Map<String, Double> quotes;
    }
}
