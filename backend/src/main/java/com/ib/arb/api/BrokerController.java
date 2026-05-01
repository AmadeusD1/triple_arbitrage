package com.ib.arb.api;

import com.ib.arb.broker.KrakenOrderClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/broker")
public class BrokerController {

    private final KrakenOrderClient broker;

    public BrokerController(KrakenOrderClient broker) {
        this.broker = broker;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Boolean>> health() {
        return ResponseEntity.ok(Map.of("connected", broker.isConnected()));
    }
}
