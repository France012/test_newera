package it.enterprise.claims.core.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract between the state machine in the Java enum and the state machine stored in PostgreSQL.
 *
 * <p>Since TK-64FBC8 the allowed transitions live in <strong>two</strong> places by design
 * (decision A3): {@link ClaimStatus#allowedTransitions()} answers the API with {@code 409}
 * <em>before</em> the write, and the seed of {@code coreapp.claim_status_transition} is referenced
 * by the composite foreign key {@code fk_claim_status_history_transition}, so PostgreSQL rejects an
 * illegal pair even when the application check is bypassed. Two sources of truth are only
 * acceptable while they are provably identical: this test reads the migration from the test
 * classpath and fails the build on any divergence. No database is needed.
 */
class ClaimStatusTransitionContractTest {

  private static final String MIGRATION = "db/migration/V2__claim_workflow_actors_audit.sql";

  private static final Pattern TRANSITION_ROW =
      Pattern.compile("\\('([A-Z_]+)',\\s*'([A-Z_]+)'\\)");

  private static List<String> seed;

  private static Map<String, Set<String>> rules;

  @BeforeAll
  static void loadSeed() {
    String ddl = classpathText(MIGRATION);

    int start = ddl.indexOf("insert into coreapp.claim_status_transition");
    assertThat(start)
        .as("the state machine seed must live in %s", MIGRATION)
        .isGreaterThanOrEqualTo(0);
    int end = ddl.indexOf(';', start);
    String statement = ddl.substring(start, end);

    seed = new ArrayList<>();
    rules = new TreeMap<>();
    Matcher matcher = TRANSITION_ROW.matcher(statement);
    while (matcher.find()) {
      String from = matcher.group(1).toUpperCase(Locale.ROOT);
      String to = matcher.group(2).toUpperCase(Locale.ROOT);
      seed.add(from + " -> " + to);
      rules.computeIfAbsent(from, key -> new TreeSet<>()).add(to);
    }
  }

  @Test
  @DisplayName("the seeded pairs are exactly ClaimStatus.allowedTransitions()")
  void seedMirrorsTheEnum() {
    Map<String, Set<String>> expected = new TreeMap<>();
    for (ClaimStatus status : ClaimStatus.values()) {
      Set<String> targets = new TreeSet<>();
      for (ClaimStatus target : status.allowedTransitions()) {
        targets.add(target.name());
      }
      if (!targets.isEmpty()) {
        expected.put(status.name(), targets);
      }
    }

    assertThat(rules)
        .as("coreapp.claim_status_transition must mirror the enum one-to-one (decision A3)")
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("no self-transition and no transition declared twice")
  void seedIsASetOfRealChanges() {
    assertThat(seed).as("the seed must not contain duplicate pairs").doesNotHaveDuplicates();
    assertThat(seed)
        .allSatisfy(pair -> assertThat(pair.split(" -> ")[0]).isNotEqualTo(pair.split(" -> ")[1]));
  }

  @Test
  @DisplayName("the seed only references ClaimStatus values that exist")
  void seedOnlyReferencesKnownStatuses() {
    assertThat(seed)
        .allSatisfy(
            pair -> {
              for (String status : pair.split(" -> ")) {
                assertThat(ClaimStatus.valueOf(status))
                    .as("unknown status %s in the transition seed", status)
                    .isNotNull();
              }
            });
  }

  @Test
  @DisplayName("ARCHIVED is the only terminal state and no transition leaves it")
  void archivedIsTerminal() {
    assertThat(ClaimStatus.ARCHIVED.allowedTransitions()).isEmpty();
    assertThat(rules).as("no pair may start from ARCHIVED").doesNotContainKey("ARCHIVED");
    assertThat(ClaimStatus.values())
        .filteredOn(ClaimStatus::isTerminal)
        .containsExactly(ClaimStatus.ARCHIVED);
  }

  @Test
  @DisplayName("the settlement step cannot be skipped: APPROVED -> CLOSED is gone (decision A10)")
  void settlementCannotBeSkipped() {
    assertThat(ClaimStatus.APPROVED.canTransitionTo(ClaimStatus.CLOSED)).isFalse();
    assertThat(ClaimStatus.APPROVED.allowedTransitions()).containsExactly(ClaimStatus.SETTLEMENT);
    assertThat(rules.get("APPROVED")).containsExactly("SETTLEMENT");
    assertThat(seed).doesNotContain("APPROVED -> CLOSED");
  }

  @Test
  @DisplayName("every state is reachable from DRAFT and every state can reach ARCHIVED")
  void theStateMachineIsConnected() {
    Set<ClaimStatus> reachable = EnumSet.of(ClaimStatus.DRAFT);
    Deque<ClaimStatus> queue = new ArrayDeque<>(reachable);
    while (!queue.isEmpty()) {
      for (ClaimStatus target : queue.poll().allowedTransitions()) {
        if (reachable.add(target)) {
          queue.add(target);
        }
      }
    }
    assertThat(reachable)
        .as("every state must be reachable from DRAFT (no dead branch in the workflow)")
        .containsExactlyInAnyOrder(ClaimStatus.values());

    Set<ClaimStatus> reachArchived = new LinkedHashSet<>();
    for (ClaimStatus status : ClaimStatus.values()) {
      if (canReachArchived(status, EnumSet.noneOf(ClaimStatus.class))) {
        reachArchived.add(status);
      }
    }
    assertThat(reachArchived)
        .as("every state must be able to end in ARCHIVED, otherwise rows get stuck forever")
        .containsExactlyInAnyOrder(ClaimStatus.values());
  }

  @Test
  @DisplayName("the number of seeded transitions matches the enum")
  void transitionCountMatches() {
    int expected =
        List.of(ClaimStatus.values()).stream()
            .mapToInt(status -> status.allowedTransitions().size())
            .sum();
    assertThat(seed).hasSize(expected).hasSize(14);
  }

  /**
   * @param from starting state
   * @param visited already visited states (cycle guard)
   * @return {@code true} if {@link ClaimStatus#ARCHIVED} is reachable from {@code from}
   */
  private static boolean canReachArchived(ClaimStatus from, Set<ClaimStatus> visited) {
    if (from == ClaimStatus.ARCHIVED) {
      return true;
    }
    if (!visited.add(from)) {
      return false;
    }
    for (ClaimStatus target : from.allowedTransitions()) {
      if (canReachArchived(target, visited)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param resource a classpath resource
   * @return its content, UTF-8
   */
  private static String classpathText(String resource) {
    try (InputStream stream =
        ClaimStatusTransitionContractTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as("classpath resource %s", resource).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException(cause);
    }
  }
}
