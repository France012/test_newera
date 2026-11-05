package it.enterprise.claims.core.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract between the Flyway migrations and the JPA mapping of the {@code coreapp} schema.
 *
 * <p>The schema is owned by Flyway and Hibernate only validates it at startup ({@code
 * ddl-auto=validate}), so a column mapped by an entity but missing from the migration — or a {@link
 * ClaimStatus} value missing from the CHECK constraints — becomes a <em>runtime</em> failure that
 * no unit test would catch. This test turns that drift into a build failure.
 *
 * <p>It needs no database: every migration of the module is read from the test classpath (V1 plus
 * every incremental {@code V<n>}), so it runs in the default {@code mvn verify} and in CI. Columns
 * added by an incremental migration ({@code alter table … add column if not exists …}) are merged
 * with the ones declared in the original {@code create table}, and the <strong>last</strong>
 * definition of a constraint wins — that is what a migrated database ends up with.
 *
 * <p>It enforces the rules of {@code docs/db/README.md}: §4 (the entities mirror the migrated
 * columns one-to-one), §7.5 (a new {@link ClaimStatus} value requires both the enum and a
 * migration) and the decisions DD-6 (soft delete), DD-7 (no native enum), DD-11 (append-only audit)
 * and DD-15 (one active assignment per role).
 */
class SchemaMigrationContractTest {

  /** Versioned migrations of core-app, in Flyway order: V1 creates, V2 evolves. */
  private static final List<String> MIGRATIONS =
      List.of(
          "db/migration/V1__init_claims.sql", "db/migration/V2__claim_workflow_actors_audit.sql");

  private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']+)'");

  /** {@code alter table <schema>.<table> add column if not exists <column> <definition>}. */
  private static final Pattern ADDED_COLUMN =
      Pattern.compile(
          "alter table\\s+(\\w+)\\.(\\w+)\\s+add column if not exists\\s+(\\w+)\\s+([^;]+);");

  /** One entry per table of the {@code coreapp} schema: the entity is the source of truth. */
  private static final List<Class<?>> ENTITIES =
      List.of(
          ClaimJpaEntity.class,
          PolicyJpaEntity.class,
          CustomerJpaEntity.class,
          ClaimStatusHistoryJpaEntity.class,
          ClaimDocumentJpaEntity.class,
          RoleJpaEntity.class,
          ActorJpaEntity.class,
          ActorRoleJpaEntity.class,
          ClaimAssignmentJpaEntity.class,
          AuditEventJpaEntity.class);

  private static String ddl;

  @BeforeAll
  static void loadMigrations() {
    StringBuilder merged = new StringBuilder();
    for (String migration : MIGRATIONS) {
      merged.append(classpathText(migration)).append('\n');
    }
    ddl = merged.toString().toLowerCase(Locale.ROOT);
  }

  @Test
  @DisplayName("every mapped table is created by the migrations, in the declared schema")
  void everyMappedTableIsMigrated() {
    for (Class<?> entity : ENTITIES) {
      Table table = tableOf(entity);
      assertThat(table.schema())
          .as("%s must be schema-qualified (decision DD-9)", entity.getSimpleName())
          .isNotBlank();
      assertThat(columnDefinitions(table))
          .as("table %s.%s mapped by %s", table.schema(), table.name(), entity.getSimpleName())
          .isNotEmpty();
    }
  }

  @Test
  @DisplayName("every @Column of every entity exists in the migrations, with the declared type")
  void everyMappedColumnIsMigrated() {
    for (Class<?> entity : ENTITIES) {
      Table table = tableOf(entity);
      Map<String, String> definitions = columnDefinitions(table);
      Set<String> mapped = mappedColumns(entity);

      assertThat(definitions.keySet())
          .as(
              "%s.%s must declare every column mapped by %s",
              table.schema(), table.name(), entity.getSimpleName())
          .containsAll(mapped);

      mappedStringLengths(entity)
          .forEach(
              (column, length) ->
                  assertThat(typeOf(definitions, column))
                      .as(
                          "length of %s.%s.%s must match @Column(length = %d)",
                          table.schema(), table.name(), column, length)
                      .isEqualTo("varchar(" + length + ")"));
    }
  }

  @Test
  @DisplayName("every unique constraint declared on the entities exists in the migrations")
  void everyUniqueConstraintIsMigrated() {
    for (Class<?> entity : ENTITIES) {
      Table table = tableOf(entity);
      for (UniqueConstraint unique : table.uniqueConstraints()) {
        assertThat(ddl)
            .as("unique constraint %s of %s", unique.name(), entity.getSimpleName())
            .contains("constraint " + unique.name());
      }
    }
  }

  @Test
  @DisplayName("ClaimStatus and the status CHECK constraints declare exactly the same values")
  void claimStatusMatchesCheckConstraints() {
    List<String> expected =
        Arrays.stream(ClaimStatus.values())
            .map(Enum::name)
            .sorted(Comparator.naturalOrder())
            .toList();

    for (String constraint :
        List.of(
            "ck_claims_status",
            "ck_claim_status_history_from_status",
            "ck_claim_status_history_to_status",
            "ck_claim_status_transition_from",
            "ck_claim_status_transition_to")) {
      assertThat(quotedValuesOf(constraint))
          .as("%s must mirror ClaimStatus (decision DD-7)", constraint)
          .containsExactlyElementsOf(expected);
    }
  }

  @Test
  @DisplayName("the optimistic-locking counters are migrated as NOT NULL numeric columns")
  void optimisticLockingCountersAreMigrated() {
    assertThat(columnDefinitions(tableOf(ClaimJpaEntity.class)))
        .as("claims.version is the Hibernate @Version counter")
        .containsKey("version")
        .hasEntrySatisfying("version", definition -> assertThat(definition).contains("not null"));
    assertThat(typeOf(columnDefinitions(tableOf(ClaimJpaEntity.class)), "version"))
        .as("claims.version maps a Long, so the column must be bigint")
        .isEqualTo("bigint");

    assertThat(typeOf(columnDefinitions(tableOf(ActorJpaEntity.class)), "version"))
        .as("actors.version is the @Version counter of the actor aggregate")
        .isEqualTo("bigint");
    assertThat(columnDefinitions(tableOf(ActorJpaEntity.class)).get("version"))
        .as("actors.version must be NOT NULL")
        .contains("not null");
  }

  @Test
  @DisplayName("actors soft delete keeps the partial indexes and the nullable deleted_at")
  void actorSoftDeleteIsMigrated() {
    assertThat(columnDefinitions(tableOf(ActorJpaEntity.class)).get("deleted_at"))
        .as("a soft-deleted actor must stay readable/auditable, so the column is nullable (DD-6)")
        .doesNotContain("not null");
    assertThat(ddl)
        .as("the live-actor lookup must be served by a partial index")
        .containsPattern("idx_actors_type_enabled(\\s|.)*?where deleted_at is null");
  }

  @Test
  @DisplayName(
      "at most one active assignment per (claim, role) is enforced by a partial unique index")
  void activeAssignmentIsUniqueInTheDatabase() {
    assertThat(ddl)
        .as("decision DD-15: the invariant must be enforced by PostgreSQL, not by the service")
        .containsPattern(
            "create unique index if not exists uk_claim_assignments_active_role"
                + "(\\s|.)*?where unassigned_at is null");
  }

  @Test
  @DisplayName("audit_events is append-only in the mapping and stores details as jsonb")
  void auditEventsIsAppendOnly() {
    assertThat(AuditEventJpaEntity.class.isAnnotationPresent(Immutable.class))
        .as("decision DD-11: @Immutable prevents Hibernate from ever updating an audit row")
        .isTrue();

    for (Field field : AuditEventJpaEntity.class.getDeclaredFields()) {
      if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Column column = field.getAnnotation(Column.class);
      assertThat(column).as("audit field %s must be a mapped column", field.getName()).isNotNull();
      assertThat(column.updatable())
          .as("audit column %s must never be updatable (append-only)", column.name())
          .isFalse();
    }

    assertThat(AuditEventJpaEntity.class.getDeclaredFields())
        .as("details must be mapped as JSON so Hibernate writes jsonb")
        .anySatisfy(
            field -> {
              Column column = field.getAnnotation(Column.class);
              if (column != null && "details".equals(column.name())) {
                assertThat(field.getAnnotation(JdbcTypeCode.class))
                    .as("details needs @JdbcTypeCode(SqlTypes.JSON)")
                    .isNotNull()
                    .extracting(JdbcTypeCode::value)
                    .isEqualTo(SqlTypes.JSON);
              }
            });
    assertThat(typeOf(columnDefinitions(tableOf(AuditEventJpaEntity.class)), "details"))
        .as("coreapp.audit_events.details must be jsonb")
        .isEqualTo("jsonb");
  }

  @Test
  @DisplayName("persistence configuration keeps Flyway authoritative and Hibernate validate-only")
  void flywayOwnsTheSchema() {
    String configuration = classpathText("application.yml");
    String localProfile = classpathText("application-local.yml");

    assertThat(configuration)
        .as("Flyway must own the coreapp schema")
        .containsPattern("flyway:(\\s|.)*?enabled:\\s*true")
        .containsPattern("schemas:\\s*coreapp")
        .containsPattern("default-schema:\\s*coreapp")
        .containsPattern("baseline-on-migrate:\\s*false");
    assertThat(configuration)
        .as("Hibernate must never create or update the schema")
        .containsPattern("ddl-auto:\\s*validate")
        .doesNotContainPattern("ddl-auto:\\s*(create|create-drop|update)");
    assertThat(localProfile)
        .as("the local profile must not relax ddl-auto (ADR: Flyway owns the schema)")
        .doesNotContainPattern("ddl-auto:");
  }

  // ---------------------------------------------------------------------------------------------
  // DDL parsing helpers — deliberately dependency-free, no database and no SQL parser required.
  // ---------------------------------------------------------------------------------------------

  /**
   * @param entity an entity class
   * @return its {@code @Table} annotation, never {@code null}
   */
  private static Table tableOf(Class<?> entity) {
    Table table = entity.getAnnotation(Table.class);
    assertThat(table).as("@Table on %s", entity.getSimpleName()).isNotNull();
    return table;
  }

  /**
   * @param table the mapped table
   * @return the raw body of the {@code create table} statement, empty when the table is missing
   */
  private static String tableBody(Table table) {
    int start = -1;
    for (String prefix : List.of("create table if not exists ", "create table ")) {
      start = ddl.indexOf(prefix + table.schema() + "." + table.name());
      if (start >= 0) {
        break;
      }
    }
    if (start < 0) {
      return "";
    }
    int open = ddl.indexOf('(', start);
    int depth = 0;
    for (int index = open; index < ddl.length(); index++) {
      char current = ddl.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
        if (depth == 0) {
          return ddl.substring(open + 1, index);
        }
      }
    }
    return "";
  }

  /**
   * Columns of a table as they end up after <em>all</em> migrations: the {@code create table}
   * definition plus every {@code alter table … add column if not exists …} of the incremental
   * migrations (V2 onwards).
   *
   * @param table the mapped table
   * @return column name to raw column definition (type, nullability, constraints)
   */
  private static Map<String, String> columnDefinitions(Table table) {
    Map<String, String> definitions = new LinkedHashMap<>();
    for (String segment : topLevelSegments(tableBody(table))) {
      if (segment.isBlank() || segment.startsWith("constraint ")) {
        continue;
      }
      String[] tokens = segment.split("\\s+");
      definitions.put(tokens[0], segment);
    }

    Matcher added = ADDED_COLUMN.matcher(ddl);
    while (added.find()) {
      if (added.group(1).equals(table.schema()) && added.group(2).equals(table.name())) {
        String column = added.group(3);
        definitions.put(column, column + " " + added.group(4).trim());
      }
    }
    return definitions;
  }

  /**
   * Splits a {@code create table} body on the commas that are not nested inside parentheses, so
   * {@code numeric(12,2)} and {@code check (status in (…))} stay in one segment.
   *
   * @param body the body of the statement
   * @return the top-level segments, trimmed
   */
  private static List<String> topLevelSegments(String body) {
    List<String> segments = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int index = 0; index < body.length(); index++) {
      char current = body.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth--;
      } else if (current == ',' && depth == 0) {
        segments.add(body.substring(start, index).trim());
        start = index + 1;
      }
    }
    if (!body.isBlank()) {
      segments.add(body.substring(start).trim());
    }
    return segments;
  }

  /**
   * @param definitions column name to definition
   * @param column a column name
   * @return the SQL type token of the column
   */
  private static String typeOf(Map<String, String> definitions, String column) {
    String definition = definitions.get(column);
    assertThat(definition).as("column %s in the migration", column).isNotNull();
    return definition.split("\\s+")[1];
  }

  /**
   * @param entity an entity class
   * @return every column name mapped by the entity, {@code @EmbeddedId} classes included
   */
  private static Set<String> mappedColumns(Class<?> entity) {
    Set<String> columns = new LinkedHashSet<>();
    for (Field field : entity.getDeclaredFields()) {
      Column column = field.getAnnotation(Column.class);
      if (column != null) {
        columns.add(column.name().toLowerCase(Locale.ROOT));
      }
      if (field.isAnnotationPresent(EmbeddedId.class)) {
        columns.addAll(mappedColumns(field.getType()));
      }
    }
    return columns;
  }

  /**
   * @param entity an entity class
   * @return column name to {@code @Column(length)} for every mapped {@link String} field
   */
  private static Map<String, Integer> mappedStringLengths(Class<?> entity) {
    Map<String, Integer> lengths = new LinkedHashMap<>();
    for (Field field : entity.getDeclaredFields()) {
      Column column = field.getAnnotation(Column.class);
      // A JSON column is not a varchar: @Column(length) keeps its default there and is meaningless.
      boolean jsonColumn = field.getAnnotation(JdbcTypeCode.class) != null;
      if (column != null && field.getType() == String.class && column.length() > 0 && !jsonColumn) {
        lengths.put(column.name().toLowerCase(Locale.ROOT), column.length());
      }
      if (field.isAnnotationPresent(EmbeddedId.class)) {
        lengths.putAll(mappedStringLengths(field.getType()));
      }
    }
    return lengths;
  }

  /**
   * Quoted values of the <strong>last</strong> definition of a named constraint: an incremental
   * migration that drops and re-adds a CHECK (widening {@code ck_claims_status} from 7 to 10 values
   * in V2) is what the migrated database actually enforces.
   *
   * @param constraintName a named constraint of the migrations
   * @return the quoted values of that constraint, sorted, uppercase
   */
  private static List<String> quotedValuesOf(String constraintName) {
    int start = ddl.lastIndexOf("constraint " + constraintName + " ");
    assertThat(start)
        .as("constraint %s declared in one of %s", constraintName, MIGRATIONS)
        .isGreaterThanOrEqualTo(0);

    String tail = ddl.substring(start);
    int end = tail.length();
    int nextConstraint = tail.indexOf("constraint ", 1);
    int endOfStatement = tail.indexOf(';');
    for (int candidate : new int[] {nextConstraint, endOfStatement}) {
      if (candidate > 0) {
        end = Math.min(end, candidate);
      }
    }

    List<String> values = new ArrayList<>();
    Matcher matcher = QUOTED_VALUE.matcher(tail.substring(0, end));
    while (matcher.find()) {
      values.add(matcher.group(1).toUpperCase(Locale.ROOT));
    }
    values.sort(Comparator.naturalOrder());
    return values;
  }

  /**
   * @param resource a classpath resource
   * @return its content, UTF-8
   */
  private static String classpathText(String resource) {
    try (InputStream stream =
        SchemaMigrationContractTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as("classpath resource %s", resource).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException(cause);
    }
  }
}
