package it.enterprise.claims.documents.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clock configuration: the upload timestamp comes from an injected {@link Clock}, so tests can pin
 * time instead of asserting on {@code OffsetDateTime.now()}.
 */
@Configuration
public class ClockConfig {

  /**
   * @return the system clock in UTC
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
