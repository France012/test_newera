package it.enterprise.claims.core.domain.model;

/**
 * Nature of an {@link Actor} (Italian claims domain: <em>tipo attore</em>).
 *
 * <p>Mirrored by the CHECK constraint {@code ck_actors_actor_type} of {@code coreapp.actors}
 * (migration {@code V2__claim_workflow_actors_audit.sql}): adding a value requires both an enum
 * change and a new migration (docs/db/README.md §7.5).
 *
 * <ul>
 *   <li>{@code INTERNAL} — dipendente/struttura interna (istruttore, perito interno, liquidatore)
 *   <li>{@code EXTERNAL} — controparte esterna (perito esterno, legale, agenzia)
 *   <li>{@code SYSTEM} — processo automatico (job di retention, integrazione batch)
 * </ul>
 */
public enum ActorType {
  INTERNAL,
  EXTERNAL,
  SYSTEM
}
