package com.ib.arb.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.KrakenOrderBookFeed;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
import com.ib.arb.scanner.Signal;
import static com.ib.arb.common.Constants.Direction.BUY;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class KrakenOrderClient extends AbstractOrderClient {

    private static final String BASE_URL      = "https://api.kraken.com";
    private static final String ADD_ORDER_PATH = "/0/private/AddOrder";

    /** Fallback credentials read from application.yml (env vars) when no DB config exists. */
    @Value("${kraken.api-key:}")
    private String apiKeyFallback;

    @Value("${kraken.api-secret:}")
    private String apiSecretFallback;

    private final KrakenOrderBookFeed feed;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private record LegMeta(int legIndex, String pair, String direction, double price, double volume) {}

    public KrakenOrderClient(KrakenOrderBookFeed feed, SettingRepository settings,
                             ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
        this.feed = feed;
    }

    @Override public Exchange getExchange() { return Exchange.KRAKEN; }

    @Override
    public boolean isConnected() {
        return isSimulation() || (!effectiveApiKey().isBlank() && !effectiveApiSecret().isBlank());
    }

    /** DB config takes precedence over environment-variable fallbacks. */
    private String effectiveApiKey() {
        var key = apiKey();
        return key.isBlank() ? apiKeyFallback : key;
    }

    private String effectiveApiSecret() {
        var secret = apiSecret();
        return secret.isBlank() ? apiSecretFallback : secret;
    }

    /**
     * Computes per-leg order metadata from the live order book without placing orders.
     * Used by {@code AutoTrader} to build the legs list before simulation logging.
     */
    public List<LegResult> computeLegs(Signal signal, double orderSizeUsd) {
        return buildLegMeta(signal, orderSizeUsd).stream()
            .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(), l.price(), l.volume(), true, null))
            .toList();
    }

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var krakenPair = KrakenOrderBookFeed.toKrakenSymbol(l.pair());
                    var txid = sendOrder(krakenPair, l.direction().toLowerCase(), l.price(), l.quantity());
                    return new LegResult(l.legIndex(), l.pair(), l.direction(),
                        l.price(), l.quantity(), txid.isPresent(), txid.orElse(null));
                }))
                .toList();
            return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(LegResult::legIndex))
                .toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }

    private List<LegMeta> buildLegMeta(Signal signal, double orderSizeUsd) {
        var config     = signal.config();
        var pairs      = new String[]{ config.getPair1(), config.getPair2(), config.getPair3() };
        var directions = signal.cycle().dirs;

        var legs = new ArrayList<LegMeta>();
        for (int i = 0; i < 3; i++) {
            var snapshot = feed.getSnapshot(pairs[i]);
            if (snapshot == null) return List.of();
            var price = BUY.equals(directions[i]) ? snapshot.ask() : snapshot.bid();
            legs.add(new LegMeta(i + 1, pairs[i], directions[i], price, orderSizeUsd / price));
        }
        return legs;
    }

    private Optional<String> sendOrder(String pair, String type, double price, double volume) {
        try {
            var nonce   = KrakenAuth.nextNonce();
            var params  = new LinkedHashMap<String, String>();
            params.put("nonce",     nonce);
            params.put("ordertype", "limit");
            params.put("type",      type);
            params.put("pair",      pair);
            params.put("price",     String.format("%.5f", price));
            params.put("volume",    String.format("%.5f", volume));

            var postData  = KrakenAuth.encodeForm(params);
            var signature = KrakenAuth.sign(ADD_ORDER_PATH, nonce, postData, effectiveApiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + ADD_ORDER_PATH))
                .header("API-Key",      effectiveApiKey())
                .header("API-Sign",     signature)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(postData))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var node = mapper.readTree(response.body());
            if (!node.path("error").isEmpty()) return Optional.empty();
            var txid = node.path("result").path("txid").path(0).asText(null);
            return Optional.ofNullable(txid);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
