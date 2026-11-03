package it.enterprise.claims.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code bff} (port 8185).
 *
 * <p>Stateless aggregation layer: no database, no schema, no entity. It only orchestrates calls to
 * core-app and document-service and adapts their payloads to what the React application needs.
 */
@SpringBootApplication
public class BffApplication {

  /**
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(BffApplication.class, args);
  }
}
