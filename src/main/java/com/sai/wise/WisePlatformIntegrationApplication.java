package com.sai.wise;

import com.sai.wise.config.WiseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Partner-side integration against the Wise Platform API.
 *
 * <p>This service plays the role a partner bank or fintech plays when it embeds
 * Wise: it calls the Wise Platform API to quote, create a recipient, create a
 * transfer, fund it, and then track it to completion via webhooks and a
 * reconciliation job.
 *
 * <p>Everything here runs against the Wise <b>sandbox</b>. No real money moves.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WiseProperties.class)
public class WisePlatformIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(WisePlatformIntegrationApplication.class, args);
    }
}
