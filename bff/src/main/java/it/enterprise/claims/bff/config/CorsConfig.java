package it.enterprise.claims.bff.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS policy for the browser client.
 *
 * <p>Allowed origins come from {@code BFF_ALLOWED_ORIGINS} (comma-separated). Credentials are
 * enabled, therefore a wildcard origin is rejected by design: with {@code allowCredentials(true)}
 * the CORS spec forbids {@code *}.
 */
@Configuration
public class CorsConfig {

  /**
   * @param allowedOrigins comma-separated list of origins allowed to call the BFF
   * @return the CORS configuration source applied by Spring Security
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${bff.cors.allowed-origins:http://localhost:5199}") String allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(parseOrigins(allowedOrigins));
    configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
    configuration.setExposedHeaders(List.of("Location"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/bff/**", configuration);
    return source;
  }

  private static List<String> parseOrigins(String rawOrigins) {
    return Arrays.stream(rawOrigins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty() && !"*".equals(origin))
        .toList();
  }
}
