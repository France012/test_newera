package it.enterprise.claims.core.application.exception;

import java.util.UUID;

/** Thrown when an actor id does not resolve to a live row - mapped to HTTP 404. */
public class ActorNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID actorId;

  /**
   * @param actorId the identifier that was not found
   */
  public ActorNotFoundException(UUID actorId) {
    super("Actor " + actorId + " not found");
    this.actorId = actorId;
  }

  /**
   * @return the identifier that was not found
   */
  public UUID getActorId() {
    return actorId;
  }
}
