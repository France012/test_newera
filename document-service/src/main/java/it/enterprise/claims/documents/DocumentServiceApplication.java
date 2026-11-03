package it.enterprise.claims.documents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code document-service} (port 8181).
 *
 * <p>Component scan rooted at {@code it.enterprise.claims.documents}: the module has its own schema
 * and its own persistence entities, and never sees core-app classes.
 */
@SpringBootApplication
public class DocumentServiceApplication {

  /**
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(DocumentServiceApplication.class, args);
  }
}
