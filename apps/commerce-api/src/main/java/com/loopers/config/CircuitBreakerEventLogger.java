package com.loopers.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventLogger {

    private final CircuitBreakerRegistry registry;

    @PostConstruct
    public void registerListeners() {
        registry.circuitBreaker("pg-simulator").getEventPublisher()
                .onStateTransition(event ->
                        log.warn("CircuitBreaker 상태 전이: {} -> {}",
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()
                        ));
    }

}
