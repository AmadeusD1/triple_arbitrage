package com.ib.arb.api;

import com.ib.arb.alert.AlertService;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.TriangleConfigRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

import static com.ib.arb.common.Constants.TriangleStatus.ACTIVE;

/**
 * Endpoints for same-server internal services to signal the ib app directly - not for
 * browser/frontend use. Currently just crypto-aggregator's outlier protection, which calls
 * here when it has disabled a pair for an absurd price and that same pair is traded by one
 * or more triangles here.
 *
 * <p>Permitted unauthenticated in SecurityConfig (browser sessions have no reason to call
 * this), but additionally restricted to localhost here regardless, since these actions have
 * real effects on live trading.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);
    private static final Set<String> LOCALHOST = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    private static final String INACTIVE = "INACTIVE";

    private final TriangleConfigRepository triangleRepo;
    private final AlertService alertService;

    public InternalController(TriangleConfigRepository triangleRepo, AlertService alertService) {
        this.triangleRepo = triangleRepo;
        this.alertService = alertService;
    }

    /**
     * Disables every active triangle on {@code exchange} that trades {@code pair} (as any of
     * its three legs) - never the exchange itself, and never a triangle that doesn't actually
     * trade this pair. crypto-aggregator calls this only once a price deviation is large
     * enough to cross its triangle-disable threshold (wider than the threshold it uses to
     * disable just its own feed for the pair - see ExchangeReliabilityService there).
     */
    @PostMapping("/triangles/disable")
    public ResponseEntity<Void> disableTriangles(@RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        if (!LOCALHOST.contains(request.getRemoteAddr())) {
            log.warn("[INTERNAL] Rejected triangle-disable request from non-localhost address {}",
                request.getRemoteAddr());
            return ResponseEntity.status(403).build();
        }

        var exchangeName = body.get("exchange");
        var pair = body.get("pair");
        var reason = body.getOrDefault("reason", "unspecified");
        if (exchangeName == null || pair == null) {
            return ResponseEntity.badRequest().build();
        }
        try { Exchange.valueOf(exchangeName.toUpperCase()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }

        // ccy-agg pairs are "BASE/QUOTE"; ib's own TriangleConfig legs are stored without the
        // separator (e.g. "LUNCUSD") - strip it so the two sides compare equal.
        var noSlashPair = pair.replace("/", "").toUpperCase();

        var affected = triangleRepo.findByStatus(ACTIVE).stream()
            .filter(t -> exchangeName.equalsIgnoreCase(t.getExchange()))
            .filter(t -> noSlashPair.equalsIgnoreCase(t.getPair1())
                      || noSlashPair.equalsIgnoreCase(t.getPair2())
                      || noSlashPair.equalsIgnoreCase(t.getPair3()))
            .toList();

        if (affected.isEmpty()) {
            log.debug("[INTERNAL] No active triangle on {} trades {} - outlier reason: {}", exchangeName, pair, reason);
            return ResponseEntity.noContent().build();
        }

        affected.forEach(t -> { t.setStatus(INACTIVE); triangleRepo.save(t); });
        log.error("[INTERNAL] Disabled {} triangle(s) on {} trading {} due to an outlier price. Reason: {}",
            affected.size(), exchangeName, pair, reason);
        alertService.trianglesDisabledForOutlier(exchangeName, pair, reason, affected.size());

        return ResponseEntity.noContent().build();
    }
}
