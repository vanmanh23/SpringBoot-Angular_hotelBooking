package com.stayhotel.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ensures every routed request carries a well-formed X-Correlation-Id (client value kept if valid,
 * otherwise replaced) and echoes it in the response, so logs across services can be joined.
 */
@Component
class CorrelationIdFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, id)))
                .build();
        mutated.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
