package it.enterprise.claims.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code core-app} (port 8180).
 *
 * <p>The component scan is rooted at {@code it.enterprise.claims.core}, which covers the hexagonal
 * layout ({@code domain}, {@code application}, {@code adapter.in.web}, {@code
 * adapter.out.persistence}, {@code config}) and keeps the other modules of the monorepo out of this
 * context.
 */
@SpringBootApplication
public class CoreAppApplication {

  /**
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(CoreAppApplication.class, args);
  }
}
