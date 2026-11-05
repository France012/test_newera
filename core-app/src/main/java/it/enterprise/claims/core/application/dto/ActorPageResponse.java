package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Actor;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope of {@code GET /api/v1/actors}.
 *
 * @param content the actors of the current page
 * @param page zero-based index of the current page
 * @param size requested page size
 * @param totalElements total number of matching live actors
 * @param totalPages total number of pages
 */
public record ActorPageResponse(
    List<ActorResponse> content, int page, int size, long totalElements, int totalPages) {

  /**
   * @param page source page of domain actors
   * @return the API envelope
   */
  public static ActorPageResponse from(Page<Actor> page) {
    return new ActorPageResponse(
        page.getContent().stream().map(ActorResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
