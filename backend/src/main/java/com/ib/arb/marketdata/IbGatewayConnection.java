package com.ib.arb.marketdata;

import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a single persistent socket connection to IB Gateway / TWS.
 * Extends DefaultEWrapper (no-ops for all protobuf callbacks) and overrides
 * only the callbacks relevant to market data, orders, and account summary.
 *
 * Host and port are read from exchange_configs.ws_url (e.g. "localhost:4001").
 * Client ID is read from exchange_configs.api_passphrase (defaults to 1).
 *
 * Requires TwsApi.jar (TWS API 10.45) in backend/libs/.
 */
@Component
public class IbGatewayConnection extends DefaultEWrapper {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayConnection.class);
    private static final String DEFAULT_HOST = "localhost";
    private static final int    DEFAULT_PORT = 4001;
    private static final int    DEFAULT_CLIENT_ID = 1;
    private static final int    ACCOUNT_SUMMARY_REQ_ID = 9001;

    private final ExchangeConfigRepository configRepo;
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "ib-reconnect"); t.setDaemon(true); return t; });

    private volatile EClientSocket client;
    private volatile int nextOrderId = -1;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // market data: pair (EURUSD) -> [bid, bidQty, ask, askQty]
    public final Map<String, double[]> marketData = new ConcurrentHashMap<>();
    private final Map<Integer, String> tickerToPair = new ConcurrentHashMap<>();
    private final AtomicInteger tickerIdSeq = new AtomicInteger(1);
    private volatile List<String> subscribedPairs = List.of();

    // account: currency -> cash balance
    public final Map<String, Double> cashBalances = new ConcurrentHashMap<>();
    public volatile long lastBalanceTs = 0;

    // open orders from IB callbacks
    public final Map<Integer, OpenOrderInfo> openOrderMap = new ConcurrentHashMap<>();

    // pending live-order futures: ibOrderId -> CompletableFuture<ibOrderId string>
    final Map<Integer, CompletableFuture<String>> pendingOrders = new ConcurrentHashMap<>();

    public record OpenOrderInfo(int orderId, String pair, String action, String orderType,
                                double price, double qty, String status) {}

    public IbGatewayConnection(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    // ── Connection management ────────────────────────────────────────────────

    public synchronized void connect() {
        if (client != null && client.isConnected()) return;
        if (!connecting.compareAndSet(false, true)) return;

        var cfg = configRepo.findByExchange("IB").orElse(null);
        if (cfg == null || !cfg.isEnabled()) {
            connecting.set(false);
            return;
        }

        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        var wsUrl = cfg.getWsUrl();
        if (wsUrl != null && !wsUrl.isBlank()) {
            var parts = wsUrl.split(":");
            host = parts[0];
            if (parts.length > 1) try { port = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        }

        int clientId = DEFAULT_CLIENT_ID;
        var passphrase = cfg.getApiPassphrase();
        if (passphrase != null && !passphrase.isBlank())
            try { clientId = Integer.parseInt(passphrase); } catch (Exception ignored) {}

        try {
            var signal = new EJavaSignal();
            var newClient = new EClientSocket(this, signal);
            newClient.eConnect(host, port, clientId);

            if (!newClient.isConnected()) {
                log.error("[IB] Could not connect to IB Gateway at {}:{} — is IB Gateway running?", host, port);
                connecting.set(false);
                scheduleReconnect();
                return;
            }

            client = newClient;
            log.info("[IB] Connected to IB Gateway at {}:{} clientId={}", host, port, clientId);

            var reader = new EReader(newClient, signal);
            reader.start();
            var finalHost = host;
            var finalPort = port;
            Thread readThread = new Thread(() -> {
                while (newClient.isConnected()) {
                    signal.waitForSignal();
                    try { reader.processMsgs(); }
                    catch (Exception e) { log.error("[IB] Read error: {}", e.getMessage()); }
                }
                log.info("[IB] Read loop ended — disconnected from {}:{}", finalHost, finalPort);
            }, "ib-reader");
            readThread.setDaemon(true);
            readThread.start();
        } catch (Exception e) {
            log.error("[IB] Connection error: {}", e.getMessage());
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    public void disconnect() {
        var c = client;
        client = null;
        if (c != null && c.isConnected()) c.eDisconnect();
        marketData.clear();
        cashBalances.clear();
        openOrderMap.clear();
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    private void scheduleReconnect() {
        reconnectScheduler.schedule(() -> {
            log.info("[IB] Attempting reconnect…");
            connect();
            if (isConnected() && !subscribedPairs.isEmpty()) subscribePairs(subscribedPairs);
        }, 5, TimeUnit.SECONDS);
    }

    // ── Market data ──────────────────────────────────────────────────────────

    public void subscribePairs(List<String> pairs) {
        subscribedPairs = List.copyOf(pairs);
        if (!isConnected()) { connect(); return; }
        pairs.forEach(pair -> {
            int id = tickerIdSeq.getAndIncrement();
            tickerToPair.put(id, pair);
            client.reqMktData(id, toFxContract(pair), "", false, false, null);
        });
        log.info("[IB] Subscribed market data for {} pairs", pairs.size());
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    public CompletableFuture<String> placeOrder(String pair, String direction,
                                                double price, double qty) {
        if (!isConnected() || nextOrderId < 0) {
            var failed = new CompletableFuture<String>();
            failed.complete(null);
            return failed;
        }
        int orderId = nextOrderId++;
        var order = new Order();
        order.orderId(orderId);
        order.action(direction.toUpperCase());
        order.orderType("LMT");
        order.totalQuantity(Decimal.get(qty));
        order.lmtPrice(price);
        order.tif("DAY");

        var future = new CompletableFuture<String>();
        pendingOrders.put(orderId, future);
        client.placeOrder(orderId, toFxContract(pair), order);
        log.info("[IB] Placed order {} {} {} qty={} price={}", orderId, direction, pair, qty, price);

        // fail-safe timeout
        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
            var f = pendingOrders.remove(orderId);
            if (f != null && !f.isDone()) {
                log.warn("[IB] Order {} timed out — no status callback received", orderId);
                f.complete(null);
            }
        });
        return future;
    }

    // ── Account ──────────────────────────────────────────────────────────────

    public void refreshAccountSummary() {
        if (!isConnected()) return;
        client.reqAccountSummary(ACCOUNT_SUMMARY_REQ_ID, "All", "CashBalance");
    }

    // ── FX contract builder ──────────────────────────────────────────────────

    static Contract toFxContract(String pair) {
        // "EURUSD" → symbol=EUR, currency=USD, secType=CASH, exchange=IDEALPRO
        var contract = new Contract();
        contract.symbol(pair.substring(0, 3).toUpperCase());
        contract.currency(pair.substring(3).toUpperCase());
        contract.secType("CASH");
        contract.exchange("IDEALPRO");
        return contract;
    }

    // ── EWrapper callbacks ───────────────────────────────────────────────────

    @Override
    public void nextValidId(int orderId) {
        nextOrderId = orderId;
        log.info("[IB] Next valid order ID: {}", orderId);
    }

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        var pair = tickerToPair.get(tickerId);
        if (pair == null || price <= 0) return;
        var ticks = marketData.computeIfAbsent(pair, k -> new double[4]);
        if (field == 1) ticks[0] = price;  // BID
        if (field == 2) ticks[2] = price;  // ASK
    }

    @Override
    public void tickSize(int tickerId, int field, Decimal size) {
        var pair = tickerToPair.get(tickerId);
        if (pair == null) return;
        var ticks = marketData.computeIfAbsent(pair, k -> new double[4]);
        double sz = size.isZero() ? 0 : size.value().doubleValue();
        if (field == 0) ticks[1] = sz;  // BID_SIZE
        if (field == 3) ticks[3] = sz;  // ASK_SIZE
    }

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining,
                             double avgFillPrice, long permId, int parentId, double lastFillPrice,
                             int clientId, String whyHeld, double mktCapPrice) {
        log.debug("[IB] Order {} → {}", orderId, status);
        var future = pendingOrders.remove(orderId);
        if (future != null && (status.equals("Submitted") || status.equals("PreSubmitted")
                || status.equals("Filled") || status.equals("PartiallyFilled"))) {
            future.complete(String.valueOf(orderId));
        }
        var info = openOrderMap.get(orderId);
        if (info != null)
            openOrderMap.put(orderId, new OpenOrderInfo(info.orderId(), info.pair(),
                    info.action(), info.orderType(), info.price(), info.qty(), status));
    }

    @Override
    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        var symbol = contract.symbol() + contract.currency();
        openOrderMap.put(orderId, new OpenOrderInfo(orderId, symbol,
                order.action().getApiString(), order.getOrderType(),
                order.lmtPrice(), order.totalQuantity().value().doubleValue(),
                orderState.status().name()));
    }

    @Override
    public void openOrderEnd() {}

    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        if ("CashBalance".equals(tag)) {
            try { cashBalances.put(currency, Double.parseDouble(value)); }
            catch (NumberFormatException ignored) {}
        }
    }

    @Override
    public void accountSummaryEnd(int reqId) {
        lastBalanceTs = System.currentTimeMillis();
        client.cancelAccountSummary(reqId);
        log.debug("[IB] Account summary updated: {} balances", cashBalances.size());
    }

    @Override
    public void error(Exception e) { log.error("[IB] Error: {}", e.getMessage()); }

    @Override
    public void error(String str) { log.warn("[IB] {}", str); }

    @Override
    public void error(int id, long reqId, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        // 2104/2106/2158 = market data farm connected (info, not errors)
        if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) {
            log.debug("[IB] Info {}: {}", errorCode, errorMsg);
        } else if (errorCode >= 2000 && errorCode < 3000) {
            log.warn("[IB] Warning {}: {}", errorCode, errorMsg);
        } else {
            log.error("[IB] Error {} (id={}): {}", errorCode, id, errorMsg);
            var future = pendingOrders.remove(id);
            if (future != null) future.complete(null);
        }
    }

    @Override
    public void connectionClosed() {
        log.warn("[IB] Connection closed — scheduling reconnect");
        client = null;
        scheduleReconnect();
    }

}
