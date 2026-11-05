package it.enterprise.claims.core.domain.model;

/**
 * Business event types of the audit trail ({@code coreapp.audit_events.event_type}).
 *
 * <p>The database validates the <em>format</em> of {@code event_type} ({@code
 * '^[A-Z][A-Z0-9_]{2,63}$'}) and not its value set (decision A5/DD-17): a new business event must
 * not require a migration, so this enum is the application-side vocabulary, not a constraint.
 *
 * <p>Every value is written in the same transaction as the fact it describes.
 */
public enum AuditEventType {
  /** A claim has been opened in {@code DRAFT}. */
  CLAIM_OPENED,
  /** A claim moved from one state of the machine to another. */
  CLAIM_STATUS_CHANGED,
  /** A document-service document has been attached to a claim. */
  CLAIM_DOCUMENT_LINKED,
  /** A document has been softly detached from a claim. */
  CLAIM_DOCUMENT_UNLINKED,
  /** An actor has been assigned to a claim in a role. */
  CLAIM_ASSIGNED,
  /** An assignment has been closed. */
  CLAIM_UNASSIGNED,
  /** An actor has been registered. */
  ACTOR_CREATED,
  /** The role set of an actor has been replaced. */
  ACTOR_ROLES_CHANGED
}
