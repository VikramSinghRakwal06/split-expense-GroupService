package com.payflow.wallet.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the service, including the bearer scheme so the Swagger UI can
 * call the protected endpoints with a token obtained from auth-service.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI walletServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayFlow Wallet Service")
                        .version("v1")
                        .description("""
                                Wallet balances and the immutable money ledger. Every \
                                balance change is written atomically with the ledger entry \
                                that explains it, and concurrent movements on one wallet \
                                are made safe by optimistic locking with retry. \
                                Authenticates with access tokens issued by auth-service; \
                                this service verifies them and never mints one.""")
                        .contact(new Contact().name("PayFlow Platform"))
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "Access token returned by auth-service /api/v1/auth/login")))
                // Applied globally; the public endpoints simply ignore it.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
