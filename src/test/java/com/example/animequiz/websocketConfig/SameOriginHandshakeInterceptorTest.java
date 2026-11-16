package com.example.animequiz.websocketConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

@ExtendWith(MockitoExtension.class)
class SameOriginHandshakeInterceptorTest {

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler handler;

    private final SameOriginHandshakeInterceptor interceptor = new SameOriginHandshakeInterceptor();

    @Test
    void allowsRequestsWithoutOrigin() {
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        assertThat(interceptor.beforeHandshake(request, response, handler, Map.of()))
                .isTrue();
        verify(response, never()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void allowsSameHostOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:8081");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8081/ws"));

        assertThat(interceptor.beforeHandshake(request, response, handler, Map.of()))
                .isTrue();
    }

    @Test
    void comparesHostsCaseInsensitively() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://LOCALHOST:8081");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8081/ws"));

        assertThat(interceptor.beforeHandshake(request, response, handler, Map.of()))
                .isTrue();
    }

    @Test
    void rejectsForeignOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://evil.example");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8081/ws"));

        assertThat(interceptor.beforeHandshake(request, response, handler, Map.of()))
                .isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsMalformedOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://[::1");
        when(request.getHeaders()).thenReturn(headers);

        assertThat(interceptor.beforeHandshake(request, response, handler, Map.of()))
                .isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void afterHandshakeDoesNothing() {
        interceptor.afterHandshake(request, response, handler, null);
    }
}
