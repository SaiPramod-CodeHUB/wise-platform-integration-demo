package com.sai.wise.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Builds the outbound WebClient used for every call to Wise.
 *
 * <p>Non-blocking on purpose. A partner integration spends most of its life
 * waiting on someone else's network, and blocking a thread per in-flight
 * transfer is how you turn a slow upstream into your own outage.
 */
@Configuration
public class WiseClientConfig {

    @Bean
    public WebClient wiseWebClient(WiseProperties props) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getClient().getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(props.getClient().getResponseTimeoutMs()))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(props.getClient().getResponseTimeoutMs(), TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }
}
