package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.ActorType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * API representation of an actor (blueprint §7.1).
 *
 * @param id identifier
 * @param subjectRef external IdP subject
 * @param displayName human readable name
 * @param email contact address, may be {@code null}
 * @param actorType nature of the actor
 * @param enabled {@code false} blocks new assignments
 * @param roleCodes codes of the roles currently granted, in stable order
 * @param createdAt creation timestamp
 * @param updatedAt last modification timestamp
 */
public record ActorResponse(
    UUID id,
    String subjectRef,
    String displayName,
    String email,
    ActorType actorType,
    boolean enabled,
    List<String> roleCodes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /**
   * @param actor domain actor to expose
   * @return the API representation
   */
  public static ActorResponse from(Actor actor) {
    Set<String> codes = actor.roleCodes();
    return new ActorResponse(
        actor.id(),
        actor.subjectRef(),
        actor.displayName(),
        actor.email(),
        actor.type(),
        actor.enabled(),
        codes.stream().sorted().toList(),
        actor.createdAt(),
        actor.updatedAt());
  }
}
