package it.enterprise.claims.core.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clock configuration.
 *
 * <p>{@link Clock} is injected instead of calling {@code OffsetDateTime.now()} directly, so tests
 * can pin time and assert on generated claim numbers and audit timestamps.
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
