package it.enterprise.claims.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata served at {@code /v3/api-docs} and {@code /swagger-ui.html}.
 *
 * <p>The versioned contract of record stays {@code docs/api/core-app.openapi.yaml}: this bean only
 * describes the running application, it does not replace the reviewed contract.
 */
@Configuration
public class OpenApiConfig {

  /**
   * @return the OpenAPI descriptor of this service
   */
  @Bean
  public OpenAPI coreAppOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("core-app API")
                .version("v1")
                .description(
                    "Claims, policies and the claim status state machine. "
                        + "Errors follow RFC 9457 (application/problem+json).")
                .license(new License().name("Proprietary")));
  }
}
