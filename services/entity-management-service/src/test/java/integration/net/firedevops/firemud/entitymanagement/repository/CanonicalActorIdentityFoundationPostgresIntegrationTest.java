package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical PostgreSQL proof for additive canonical actor identity and legacy quarantine. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalActorIdentityFoundationPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID TENANT_A = uuid("10000000-0000-4000-8000-000000000001");
  private static final UUID TENANT_B = uuid("10000000-0000-4000-8000-000000000002");
  private static final UUID ACCOUNT_A = uuid("20000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_B = uuid("20000000-0000-4000-8000-000000000002");
  private static final UUID NAMESPACE_SHARED = uuid("30000000-0000-4000-8000-000000000001");
  private static final UUID NAMESPACE_ISOLATED = uuid("30000000-0000-4000-8000-000000000002");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DriverManagerDataSource dataSource;
  private DSLContext dsl;

  @BeforeEach
  void migrateImmutableHistoryBeforeSeedingLegacyActors() {
    schema = "entity_actor_identity_" + UUID.randomUUID().toString().replace("-", "");
    dataSource = dataSource(schema);
    configureFlyway(MigrationVersion.fromVersion("2")).migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  @AfterEach
  void dropIsolatedSchema() {
    if (schema != null) {
      String checkedSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + checkedSchema + "\" CASCADE");
      schema = null;
    }
  }

  @Test
  void retainedNumericRowsAreQuarantinedAndLegacyWriterCannotPromoteThem() {
    CharacterRepository legacyWriter = new CharacterRepository(dsl);
    Character retained = legacyCharacter(7101L, 8101L, "retained-legacy");
    long retainedId = legacyWriter.save(retained).getId();

    migrateAll();

    var migrated = readIdentityRow(retainedId);
    UUID retainedCharacterUuid = migrated.get("character_uuid", UUID.class);
    assertThat(retainedCharacterUuid).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(migrated.get("account_id", Long.class)).isEqualTo(8101L);
    assertThat(migrated.get("tenant_id", Long.class)).isEqualTo(7101L);
    assertThat(migrated.get("account_uuid", UUID.class)).isNull();
    assertThat(migrated.get("tenant_uuid", UUID.class)).isNull();
    assertThat(migrated.get("playable_state_namespace_id", UUID.class)).isNull();
    assertThat(migrated.get("actor_identity_status", String.class))
        .isEqualTo(ActorIdentityStatus.QUARANTINED.name());
    assertThat(migrated.get("actor_identity_quarantine_reason", String.class))
        .isEqualTo("OWNER_PROVENANCE_MISSING");
    assertThat(
            identityRepository()
                .findOwnerResolvedRoster(
                    TENANT_A.toString(),
                    ACCOUNT_A.toString(),
                    NAMESPACE_SHARED.toString(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();

    Character writtenByLegacyPath =
        legacyWriter.save(legacyCharacter(7102L, 8102L, "new-legacy-path"));
    var newlyWritten = readIdentityRow(writtenByLegacyPath.getId());
    assertThat(newlyWritten.get("character_uuid", UUID.class)).isNotNull();
    assertThat(newlyWritten.get("actor_identity_status", String.class))
        .isEqualTo(ActorIdentityStatus.QUARANTINED.name());
    assertThat(
            identityRepository()
                .findOwnerResolvedActor(
                    TENANT_A.toString(),
                    ACCOUNT_A.toString(),
                    NAMESPACE_SHARED.toString(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                    newlyWritten.get("character_uuid", UUID.class).toString()))
        .isEmpty();

    long reservedTenantKey = 9900101L;
    dsl.execute(
        "INSERT INTO entity_empty_source_numeric_key_reservation "
            + "(key_kind, numeric_key, claim_kind) VALUES ('TENANT', ?, 'CANONICAL_EMPTY_SOURCE')",
        reservedTenantKey);
    assertThatThrownBy(
            () -> legacyWriter.save(legacyCharacter(reservedTenantKey, 8103L, "reserved-key")))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void ownerResolvedLookupsFilterExactTenantAccountNamespaceAndScope() {
    migrateAll();
    insertNamespace(TENANT_A, NAMESPACE_SHARED, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    insertNamespace(TENANT_A, NAMESPACE_ISOLATED, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);
    insertNamespace(TENANT_B, NAMESPACE_SHARED, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    UUID targetCharacter = uuid("40000000-0000-4000-8000-000000000001");
    UUID otherAccountCharacter = uuid("40000000-0000-4000-8000-000000000002");
    UUID otherNamespaceCharacter = uuid("40000000-0000-4000-8000-000000000003");
    UUID otherTenantCharacter = uuid("40000000-0000-4000-8000-000000000004");
    insertOwnerResolvedActor(
        7201L,
        8201L,
        targetCharacter,
        TENANT_A,
        ACCOUNT_A,
        NAMESPACE_SHARED,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    insertOwnerResolvedActor(
        7201L,
        8202L,
        otherAccountCharacter,
        TENANT_A,
        ACCOUNT_B,
        NAMESPACE_SHARED,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    insertOwnerResolvedActor(
        7201L,
        8201L,
        otherNamespaceCharacter,
        TENANT_A,
        ACCOUNT_A,
        NAMESPACE_ISOLATED,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);
    insertOwnerResolvedActor(
        7202L,
        8201L,
        otherTenantCharacter,
        TENANT_B,
        ACCOUNT_A,
        NAMESPACE_SHARED,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    ActorIdentityRepository repository = identityRepository();
    assertThat(
            repository.findOwnerResolvedRoster(
                TENANT_A.toString(),
                ACCOUNT_A.toString(),
                NAMESPACE_SHARED.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .extracting(identity -> identity.characterUuid())
        .containsExactly(targetCharacter);
    assertThat(
            repository.findOwnerResolvedRoster(
                TENANT_A.toString(),
                ACCOUNT_A.toString(),
                NAMESPACE_ISOLATED.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isEmpty();
    assertThat(
            repository.findOwnerResolvedActor(
                TENANT_A.toString(),
                ACCOUNT_A.toString(),
                NAMESPACE_SHARED.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                targetCharacter.toString()))
        .map(identity -> identity.characterUuid())
        .contains(targetCharacter);
    assertThat(
            repository.findOwnerResolvedActor(
                TENANT_A.toString(),
                ACCOUNT_B.toString(),
                NAMESPACE_SHARED.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                targetCharacter.toString()))
        .isEmpty();
    assertThat(
            repository.findOwnerResolvedActor(
                TENANT_B.toString(),
                ACCOUNT_A.toString(),
                NAMESPACE_SHARED.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                targetCharacter.toString()))
        .isEmpty();
    assertThat(
            repository.findOwnerResolvedActor(
                TENANT_A.toString(),
                ACCOUNT_A.toString(),
                NAMESPACE_ISOLATED.toString(),
                PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
                targetCharacter.toString()))
        .isEmpty();
  }

  private Flyway configureFlyway(MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema));
    return (target == null ? configuration : configuration.target(target)).load();
  }

  private void migrateAll() {
    configureFlyway(null).migrate();
  }

  private org.jooq.Record readIdentityRow(long id) {
    return dsl.fetchOne(
        "SELECT id, account_id, tenant_id, character_uuid, account_uuid, tenant_uuid, "
            + "playable_state_namespace_id, playable_state_scope, actor_identity_status, "
            + "actor_identity_quarantine_reason FROM characters WHERE id = ?",
        id);
  }

  private void insertNamespace(UUID tenant, UUID namespace, PlayableStateScope scope) {
    dsl.execute(
        "INSERT INTO entity_playable_state_namespace_scopes "
            + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, ?)",
        tenant,
        namespace,
        scope.name());
  }

  private void insertOwnerResolvedActor(
      long numericTenant,
      long numericAccount,
      UUID character,
      UUID tenant,
      UUID account,
      UUID namespace,
      PlayableStateScope scope) {
    dsl.execute(
        "INSERT INTO characters (tenant_id, account_id, playable_state_key, name, "
            + "character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, "
            + "playable_state_scope, actor_identity_status, actor_identity_quarantine_reason) "
            + "VALUES (?, ?, 'identity-fixture', ?, ?, ?, ?, ?, ?, 'OWNER_RESOLVED', NULL)",
        numericTenant,
        numericAccount,
        "canonical-" + character,
        character,
        account,
        tenant,
        namespace,
        scope.name());
  }

  private ActorIdentityRepository identityRepository() {
    return new ActorIdentityRepository(dsl);
  }

  private static Character legacyCharacter(long tenantId, long accountId, String name) {
    Character character = new Character();
    character.setTenantId(tenantId);
    character.setAccountId(accountId);
    character.setPlayableStateKey("legacy-numeric");
    character.setName(name);
    return character;
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setDriverClassName("org.postgresql.Driver");
    source.setUrl(postgres.getJdbcUrl());
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    if (schema != null) {
      source.setSchema(schema);
    }
    return source;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
