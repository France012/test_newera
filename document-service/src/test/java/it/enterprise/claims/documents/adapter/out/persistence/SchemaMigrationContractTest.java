package it.enterprise.claims.documents.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract between the Flyway migration and the JPA mapping of the {@code documents} schema.
 *
 * <p>The schema is owned by Flyway and Hibernate only validates it at startup ({@code
 * ddl-auto=validate}), so a column mapped by {@link DocumentJpaEntity} but missing from the
 * migration — or a type that {@code validate} rejects — becomes a <em>runtime</em> failure that no
 * unit test would catch. This test turns that drift into a build failure, without a database.
 *
 * <p>Enforces the rules of {@code docs/db/README.md}: §4.6 (columns, constraints and the
 * soft-delete index) and the DD-2 decision ({@code checksum_sha256} is {@code varchar(64)}, never
 * {@code char(64)}, because {@code bpchar} is not type-compatible with the Hibernate {@code String}
 * mapping).
 */
class SchemaMigrationContractTest {

  private static final String MIGRATION = "db/migration/V1__init_documents.sql";

  private static String ddl;

  @BeforeAll
  static void loadMigration() {
    try (InputStream stream =
        SchemaMigrationContractTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
      assertThat(stream).as("migration %s on the test classpath", MIGRATION).isNotNull();
      ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    } catch (IOException cause) {
      throw new UncheckedIOException(cause);
    }
  }

  @Test
  @DisplayName("the documents table is created in the schema declared by @Table")
  void mappedTableIsMigrated() {
    Table table = tableOf(DocumentJpaEntity.class);
    assertThat(table.schema())
        .as("the entity must be schema-qualified (decision DD-9)")
        .isEqualTo("documents");
    assertThat(columnDefinitions()).as("table %s.%s", table.schema(), table.name()).isNotEmpty();
  }

  @Test
  @DisplayName("every @Column of the entity exists in the migration, with the declared type")
  void everyMappedColumnIsMigrated() {
    Map<String, String> definitions = columnDefinitions();

    assertThat(definitions.keySet())
        .as("documents.documents must declare every column mapped by DocumentJpaEntity")
        .containsAll(mappedColumns());

    mappedStringLengths()
        .forEach(
            (column, length) ->
                assertThat(typeOf(definitions, column))
                    .as(
                        "length of documents.documents.%s must match @Column(length = %d)",
                        column, length)
                    .isEqualTo("varchar(" + length + ")"));
  }

  @Test
  @DisplayName("checksum_sha256 is varchar(64), never char(64) (decision DD-2)")
  void checksumIsTypeCompatibleWithHibernate() {
    String definition = columnDefinitions().get("checksum_sha256");
    assertThat(definition)
        .as("bpchar is not type-compatible with the Hibernate String mapping")
        .startsWith("checksum_sha256 varchar(64)");
    assertThat(typeOf(columnDefinitions(), "checksum_sha256")).isEqualTo("varchar(64)");
    assertThat(tableBody())
        .as("the hex format is enforced by a CHECK instead of the char/bpchar type")
        .contains("constraint ck_documents_checksum_sha256_hex");
  }

  @Test
  @DisplayName("deleted_at is nullable and backed by the partial index used by every read path")
  void softDeleteIsMigrated() {
    assertThat(columnDefinitions().get("deleted_at"))
        .as("a soft-deleted row must stay readable/auditable, so the column is nullable")
        .doesNotContain("not null");
    assertThat(ddl)
        .as("decision DD-6: the hot path is served by a partial index")
        .containsPattern(
            "create index idx_documents_claim_active (\\s|.)*?where deleted_at is null");
  }

  @Test
  @DisplayName("the unique storage key and the claim lookup index exist")
  void storageKeyAndClaimIndexAreMigrated() {
    assertThat(tableBody())
        .as("storage_key identifies the physical content")
        .contains("constraint uk_documents_storage_key");
    assertThat(ddl)
        .as("GET /api/v1/documents?claimId=… path")
        .contains("create index idx_documents_claim on documents.documents (claim_id)");
  }

  @Test
  @DisplayName("persistence configuration keeps Flyway authoritative and Hibernate validate-only")
  void flywayOwnsTheSchema() {
    String configuration = classpathText("application.yml");

    assertThat(configuration)
        .as("Flyway must own the documents schema")
        .containsPattern("flyway:(\\s|.)*?enabled:\\s*true")
        .containsPattern("schemas:\\s*documents")
        .containsPattern("default-schema:\\s*documents")
        .containsPattern("baseline-on-migrate:\\s*false");
    assertThat(configuration)
        .as("Hibernate must never create or update the schema")
        .containsPattern("ddl-auto:\\s*validate")
        .doesNotContainPattern("ddl-auto:\\s*(create|create-drop|update)");
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
   * @return the raw body of {@code create table documents.documents}, empty when missing
   */
  private static String tableBody() {
    String marker = "create table documents.documents";
    int start = ddl.indexOf(marker);
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
   * @return column name to raw column definition (type, nullability, constraints)
   */
  private static Map<String, String> columnDefinitions() {
    Map<String, String> definitions = new LinkedHashMap<>();
    for (String segment : topLevelSegments(tableBody())) {
      if (segment.isBlank() || segment.startsWith("constraint ")) {
        continue;
      }
      String[] tokens = segment.split("\\s+");
      definitions.put(tokens[0], segment);
    }
    return definitions;
  }

  /**
   * Splits a {@code create table} body on the commas that are not nested inside parentheses, so
   * {@code check (checksum_sha256 ~* '^[0-9a-f]{64}$')} stays in one segment.
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
   * @return every column name mapped by {@link DocumentJpaEntity}
   */
  private static Set<String> mappedColumns() {
    return Stream.of(DocumentJpaEntity.class.getDeclaredFields())
        .map(field -> field.getAnnotation(Column.class))
        .filter(column -> column != null)
        .map(column -> column.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  /**
   * @return column name to {@code @Column(length)} for every mapped {@link String} field
   */
  private static Map<String, Integer> mappedStringLengths() {
    Map<String, Integer> lengths = new LinkedHashMap<>();
    for (Field field : DocumentJpaEntity.class.getDeclaredFields()) {
      Column column = field.getAnnotation(Column.class);
      if (column != null && field.getType() == String.class && column.length() > 0) {
        lengths.put(column.name().toLowerCase(Locale.ROOT), column.length());
      }
    }
    return lengths;
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
