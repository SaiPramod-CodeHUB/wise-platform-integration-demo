package com.sai.wise.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger is here for a reason that matters in this domain: a partner's
 * developers need to be able to exercise the integration themselves without
 * reading our source. Developer experience is part of the product.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI wiseIntegrationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Wise Platform Integration Demo")
                .version("1.0.0")
                .description("Partner-side integration against the Wise Platform sandbox: "
                        + "quote, recipient, transfer, fund, webhook tracking and reconciliation."));
    }
}
