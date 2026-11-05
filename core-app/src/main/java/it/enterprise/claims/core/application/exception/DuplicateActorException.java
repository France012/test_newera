package it.enterprise.claims.core.application.exception;

/**
 * Thrown when an actor is registered twice with the same external subject - mapped to HTTP 409.
 *
 * <p>The uniqueness of {@code subject_ref} spans soft-deleted rows too (an identity is reactivated,
 * never re-created), so the conflict is reported by the application before the database raises
 * {@code uk_actors_subject_ref}.
 */
public class DuplicateActorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String subjectRef;

  /**
   * @param subjectRef the subject that is already registered
   */
  public DuplicateActorException(String subjectRef) {
    super("An actor with subjectRef '" + subjectRef + "' already exists");
    this.subjectRef = subjectRef;
  }

  /**
   * @return the subject that is already registered
   */
  public String getSubjectRef() {
    return subjectRef;
  }
}
