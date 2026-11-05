package it.enterprise.claims.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
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
                    "Claims (pratiche), policies, actors, roles, assignments and the 10-state claim "
                        + "workflow. Errors follow RFC 9457 (application/problem+json).")
                .license(new License().name("Proprietary")))
        .tags(
            List.of(
                new Tag().name("Claims").description("Claim lifecycle and status state machine"),
                new Tag().name("Actors").description("Actors (utenti) and their role grants"),
                new Tag()
                    .name("Roles")
                    .description("Role reference data (Istruttore, Perito, Liquidatore, ...)"),
                new Tag().name("Assignments").description("Actors assigned to a claim, per role"),
                new Tag().name("Audit").description("Append-only audit trail of a claim")));
  }
}
