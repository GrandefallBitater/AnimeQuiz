package com.example.animequiz.websocketConfig;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

public class SameOriginHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || origin.isBlank()) {
            return true;
        }

        try {
            URI originUri = URI.create(origin);
            String requestHost = request.getURI().getHost();
            boolean sameHost = originUri.getHost() != null
                    && requestHost != null
                    && originUri.getHost().equalsIgnoreCase(requestHost);
            if (!sameHost) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
            }
            return sameHost;
        } catch (IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
