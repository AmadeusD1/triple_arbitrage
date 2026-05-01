package com.ib.arb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String lastPayload = null;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        // Send the last known snapshot immediately so the client doesn't
        // have to wait for the next scheduler tick.
        if (lastPayload != null) {
            try { session.sendMessage(new TextMessage(lastPayload)); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(Object payload) {
        try {
            lastPayload = mapper.writeValueAsString(payload);
            var message = new TextMessage(lastPayload);
            for (var session : sessions) {
                if (session.isOpen()) session.sendMessage(message);
            }
        } catch (Exception ignored) {}
    }
}
