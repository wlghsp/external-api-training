package com.loopers.config;

import com.loopers.domain.payment.PgClient;
import com.loopers.infrastructure.payment.PgSimulatorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class PgClientConfig {

    @Bean
    public PgClient pgClient(
            RestClient.Builder builder,
            @Value("${pg-simulator.base-url}") String baseUrl
    ) {
        var factory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(3))
                );

        return new PgSimulatorClient(builder.requestFactory(factory), baseUrl);
    }
}
