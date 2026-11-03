package it.enterprise.claims.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP clients towards the downstream services.
 *
 * <p>Two named {@link RestClient} beans, each with its own base URL and its own timeouts, so a slow
 * dependency cannot exhaust the BFF's threads. URLs come from configuration only ({@code
 * CORE_APP_BASE_URL} / {@code DOCUMENT_SERVICE_BASE_URL}); nothing is hardcoded.
 *
 * <p>{@link RestClient.Builder} is a prototype bean in Spring Boot, therefore every method receives
 * a fresh builder instance and the two clients cannot share mutable state.
 */
@Configuration
public class RestClientConfig {

  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  /**
   * @param connectTimeoutMs TCP connect timeout in milliseconds
   * @param readTimeoutMs response read timeout in milliseconds
   */
  public RestClientConfig(
      @Value("${services.http.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${services.http.read-timeout-ms:5000}") int readTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  /**
   * @param builder prototype RestClient builder provided by Spring Boot
   * @param baseUrl core-app base URL
   * @return client bound to core-app
   */
  @Bean("coreAppRestClient")
  public RestClient coreAppRestClient(
      RestClient.Builder builder, @Value("${services.core-app.base-url}") String baseUrl) {
    return builder.baseUrl(baseUrl).requestFactory(requestFactory()).build();
  }

  /**
   * @param builder prototype RestClient builder provided by Spring Boot
   * @param baseUrl document-service base URL
   * @return client bound to document-service
   */
  @Bean("documentServiceRestClient")
  public RestClient documentServiceRestClient(
      RestClient.Builder builder, @Value("${services.document-service.base-url}") String baseUrl) {
    return builder.baseUrl(baseUrl).requestFactory(requestFactory()).build();
  }

  private SimpleClientHttpRequestFactory requestFactory() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeoutMs);
    factory.setReadTimeout(readTimeoutMs);
    return factory;
  }
}
