package it.enterprise.claims.bff.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata served at {@code /v3/api-docs} and {@code /swagger-ui.html}; the reviewed
 * contract of record remains {@code docs/api/bff.openapi.yaml}.
 */
@Configuration
public class OpenApiConfig {

  /**
   * @return the OpenAPI descriptor of this service
   */
  @Bean
  public OpenAPI bffOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("bff API")
                .version("v1")
                .description(
                    "Aggregation layer consumed by the React frontend: claims with their documents "
                        + "and dashboard counters. Errors follow RFC 9457.")
                .license(new License().name("Proprietary")));
  }
}
