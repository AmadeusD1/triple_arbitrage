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
 * Implements EWrapper so it receives all callbacks from the TWS API inline.
 *
 * Host and port are read from exchange_configs.ws_url (e.g. "localhost:4001").
 * Client ID is read from exchange_configs.api_passphrase (defaults to 1).
 *
 * Place TwsApi.jar (downloaded from https://interactivebrokers.github.io) in backend/libs/.
 */
@Component
public class IbGatewayConnection implements EWrapper {

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
    final Map<String, double[]> marketData = new ConcurrentHashMap<>();
    private final Map<Integer, String> tickerToPair = new ConcurrentHashMap<>();
    private final AtomicInteger tickerIdSeq = new AtomicInteger(1);
    private volatile List<String> subscribedPairs = List.of();

    // account: currency -> cash balance
    final Map<String, Double> cashBalances = new ConcurrentHashMap<>();
    volatile long lastBalanceTs = 0;

    // open orders from IB callbacks
    final Map<Integer, OpenOrderInfo> openOrderMap = new ConcurrentHashMap<>();

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
        order.totalQuantity(Decimal.get128BitDecimalFromDouble(qty));
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
                order.action(), order.getOrderType(),
                order.lmtPrice(), order.totalQuantity().value().doubleValue(),
                orderState.status()));
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
    public void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
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

    // ── EWrapper no-ops ──────────────────────────────────────────────────────

    @Override public void tickOptionComputation(int i, int i1, int i2, double v, double v1, double v2, double v3, double v4, double v5, double v6, double v7) {}
    @Override public void tickGeneric(int i, int i1, double v) {}
    @Override public void tickString(int i, int i1, String s) {}
    @Override public void tickEFP(int i, int i1, double v, String s, double v1, int i2, String s1, double v2, double v3) {}
    @Override public void tickSnapshotEnd(int i) {}
    @Override public void tickReqParams(int i, double v, String s, int i1) {}
    @Override public void updateAccountValue(String s, String s1, String s2, String s3) {}
    @Override public void updatePortfolio(Contract c, Decimal d, double v, double v1, double v2, double v3, double v4, String s) {}
    @Override public void updateAccountTime(String s) {}
    @Override public void accountDownloadEnd(String s) {}
    @Override public void execDetails(int i, Contract c, Execution e) {}
    @Override public void execDetailsEnd(int i) {}
    @Override public void updateMktDepth(int i, int i1, int i2, int i3, double v, Decimal d) {}
    @Override public void updateMktDepthL2(int i, int i1, String s, int i2, int i3, double v, Decimal d, boolean b) {}
    @Override public void updateNewsBulletin(int i, int i1, String s, String s1) {}
    @Override public void managedAccounts(String s) {}
    @Override public void receiveFA(int i, String s) {}
    @Override public void historicalData(int i, Bar b) {}
    @Override public void historicalDataEnd(int i, String s, String s1) {}
    @Override public void historicalDataUpdate(int i, Bar b) {}
    @Override public void scannerParameters(String s) {}
    @Override public void scannerData(int i, int i1, ContractDetails c, String s, String s1, String s2, String s3) {}
    @Override public void scannerDataEnd(int i) {}
    @Override public void realtimeBar(int i, long l, double v, double v1, double v2, double v3, Decimal d, Decimal d1, int i1) {}
    @Override public void currentTime(long l) {}
    @Override public void fundamentalData(int i, String s) {}
    @Override public void deltaNeutralValidation(int i, DeltaNeutralContract d) {}
    @Override public void commissionReport(CommissionReport c) {}
    @Override public void position(String s, Contract c, Decimal d, double v) {}
    @Override public void positionEnd() {}
    @Override public void accountUpdateMulti(int i, String s, String s1, String s2, String s3, String s4) {}
    @Override public void accountUpdateMultiEnd(int i) {}
    @Override public void securityDefinitionOptionalParameter(int i, String s, int i1, String s1, String s2, Set<String> set, Set<Double> set1) {}
    @Override public void securityDefinitionOptionalParameterEnd(int i) {}
    @Override public void softDollarTiers(int i, SoftDollarTier[] t) {}
    @Override public void familyCodes(FamilyCode[] f) {}
    @Override public void symbolSamples(int i, ContractDescription[] c) {}
    @Override public void mktDepthExchanges(DepthMktDataDescription[] d) {}
    @Override public void tickNews(int i, long l, String s, String s1, String s2, String s3) {}
    @Override public void smartComponents(int i, Map<Integer, Map.Entry<String, Character>> m) {}
    @Override public void newsArticle(int i, int i1, String s) {}
    @Override public void newsProviders(NewsProvider[] n) {}
    @Override public void historicalNews(int i, String s, String s1, String s2, String s3) {}
    @Override public void historicalNewsEnd(int i, boolean b) {}
    @Override public void headTimestamp(int i, String s) {}
    @Override public void histogramData(int i, List<HistogramEntry> l) {}
    @Override public void rerouteMktDataReq(int i, int i1, String s) {}
    @Override public void rerouteMktDepthReq(int i, int i1, String s) {}
    @Override public void marketRule(int i, PriceIncrement[] p) {}
    @Override public void pnl(int i, double v, double v1, double v2) {}
    @Override public void pnlSingle(int i, Decimal d, double v, double v1, double v2, double v3) {}
    @Override public void historicalTicks(int i, List<HistoricalTick> l, boolean b) {}
    @Override public void historicalTicksBidAsk(int i, List<HistoricalTickBidAsk> l, boolean b) {}
    @Override public void historicalTicksLast(int i, List<HistoricalTickLast> l, boolean b) {}
    @Override public void tickByTickAllLast(int i, int i1, long l, double v, Decimal d, TickAttrib t, String s, String s1) {}
    @Override public void tickByTickBidAsk(int i, long l, double v, double v1, Decimal d, Decimal d1, TickAttribBidAsk t) {}
    @Override public void tickByTickMidPoint(int i, long l, double v) {}
    @Override public void orderBound(long l, int i, int i1) {}
    @Override public void completedOrder(Contract c, Order o, OrderState s) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int i, String s) {}
    @Override public void wshMetaData(int i, String s) {}
    @Override public void wshEventData(int i, String s) {}
    @Override public void historicalSchedule(int i, String s, String s1, String s2, List<HistoricalSession> l) {}
    @Override public void userInfo(int i, String s) {}
    @Override public void contractDetails(int i, ContractDetails c) {}
    @Override public void contractDetailsEnd(int i) {}
    @Override public void bondContractDetails(int i, ContractDetails c) {}
    @Override public void verifyMessageAPI(String s) {}
    @Override public void verifyCompleted(boolean b, String s) {}
    @Override public void verifyAndAuthMessageAPI(String s, String s1) {}
    @Override public void verifyAndAuthCompleted(boolean b, String s) {}
    @Override public void displayGroupList(int i, String s) {}
    @Override public void displayGroupUpdated(int i, String s) {}
    @Override public void positionMulti(int i, String s, String s1, Contract c, Decimal d, double v) {}
    @Override public void positionMultiEnd(int i) {}
    @Override public void marketDataType(int i, int i1) {}
}
