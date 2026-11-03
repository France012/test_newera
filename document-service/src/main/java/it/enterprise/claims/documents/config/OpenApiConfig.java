package it.enterprise.claims.documents.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata served at {@code /v3/api-docs} and {@code /swagger-ui.html}; the reviewed
 * contract of record remains {@code docs/api/document-service.openapi.yaml}.
 */
@Configuration
public class OpenApiConfig {

  /**
   * @return the OpenAPI descriptor of this service
   */
  @Bean
  public OpenAPI documentServiceOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("document-service API")
                .version("v1")
                .description(
                    "Upload, metadata lookup, content streaming and soft delete of claim documents. "
                        + "Errors follow RFC 9457 (application/problem+json).")
                .license(new License().name("Proprietary")));
  }
}
