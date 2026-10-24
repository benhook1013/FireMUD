package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterReadService.PublishedIdentity;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient.AuthenticatedSource;
import net.firedevops.firemud.entitymanagement.repository.EntityEmptySelectedSourceIntakePostgresFixture;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical owner-transaction proof using a controlled already-verified-proof double. It exercises
 * the real Entity selected-source intake and schema, but does not qualify cross-owner authority.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class EntityCharacterCreatePostgresIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-10-11T12:36:00Z");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;
  private EntityCharacterCreateRepository repository;
  private EntityEmptySelectedSourceIntakeReceipt source;

  @BeforeEach
  void migrateAndRetainActualEmptySource() {
    schema = "entity_create_" + UUID.randomUUID().toString().replace("-", "");
    var configuration =
        Flyway.configure()
            .dataSource(dataSource(schema))
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
                "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema));
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
    dsl = DSL.using(dataSource(schema), SQLDialect.POSTGRES);
    repository = new EntityCharacterCreateRepository(dsl, Clock.fixed(NOW, ZoneOffset.UTC));
    source = retainControlledSource(dsl);
  }

  @Test
  void upgradesActualEntity1014ParentWithoutChangingRetainedActorOrRosterRows() {
    String parentSchema = "entity_create_parent_" + UUID.randomUUID().toString().replace("-", "");
    try {
      DriverManagerDataSource parentDataSource = dataSource(parentSchema);
      migrate(parentDataSource, parentSchema, "1014");
      DSLContext parentDsl = DSL.using(parentDataSource, SQLDialect.POSTGRES);
      List<String> parentVersions = migrationVersions(parentDsl);
      assertThat(parentVersions).contains("1014").doesNotContain("1013");

      UUID tenant = UUID.randomUUID();
      UUID account = UUID.randomUUID();
      UUID namespace = UUID.randomUUID();
      UUID character = UUID.randomUUID();
      UUID snapshot = UUID.randomUUID();
      // Controlled SQL owner values prove retained storage only, not authentic upstream custody.
      seedRetainedParentActorAndRoster(parentDsl, tenant, account, namespace, character, snapshot);
      List<String> retainedRows = retainedParentRows(parentDsl, character, snapshot);
      List<String> parentHistory = migrationHistory(parentDsl);

      migrate(parentDataSource, parentSchema, null);
      DSLContext upgradedDsl = DSL.using(parentDataSource, SQLDialect.POSTGRES);
      assertThat(migrationHistory(upgradedDsl).subList(0, parentHistory.size()))
          .containsExactlyElementsOf(parentHistory);
      assertThat(migrationVersions(upgradedDsl)).last().isEqualTo("1016");
      assertThat(retainedParentRows(upgradedDsl, character, snapshot))
          .containsExactlyElementsOf(retainedRows);
      assertThat(
              upgradedDsl
                  .fetchSingle(
                      "SELECT canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                      snapshot)
                  .get("canonical_player_created_source_identity", byte[].class))
          .isNull();
    } finally {
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + parentSchema + "\" CASCADE");
    }
  }

  @AfterEach
  void removeIsolatedSchema() {
    if (schema != null)
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA \"" + schema + "\" CASCADE");
  }

  @Test
  void atomicallyPersistsAuthoredGenericActorAssociationAndExactReplay() throws Exception {
    var proof = proof(UUID.randomUUID(), UUID.randomUUID());
    var terminal = repository.settle(proof, null);
    assertThat(terminal.getOutcome())
        .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED);
    assertThat(repository.replay(proof.request()).orElseThrow().toByteArray())
        .containsExactly(terminal.toByteArray());
    assertThat(repository.settle(proof, null).toByteArray())
        .containsExactly(terminal.toByteArray());
    var actor =
        dsl.fetchOne(
            "SELECT * FROM characters WHERE character_uuid = ?",
            UUID.fromString(terminal.getCharacterId()));
    assertThat(actor).isNotNull();
    assertThat(actor.get("account_uuid", UUID.class).toString())
        .isEqualTo(proof.action().target().accountId());
    assertThat(actor.get("name", String.class)).isEqualTo("Wayfarer");
    assertThat(actor.get("authored_initial_core_json", String.class))
        .isEqualTo(proof.action().mutation().authoredSource().core().canonicalJson());
    for (String field :
        List.of(
            "account_id",
            "playable_state_key",
            "level",
            "experience",
            "strength",
            "agility",
            "intelligence",
            "stamina",
            "health",
            "mana",
            "body_layout_key")) assertThat(actor.get(field)).as(field).isNull();
    assertThat(actor.get("actor_entry_policy", String.class)).isEqualTo("PLAYER_CREATED");
    assertThat(terminal.getEntityIntakeRequestId()).isEqualTo(source.intakeRequestId().toString());
    assertThat(terminal.getEntitySourceReceiptDigest()).isEqualTo(source.receiptDigest());
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_character_create_operations SET terminal_bytes = ?",
                    new byte[] {1}))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE characters SET name = 'substituted' WHERE character_uuid = ?",
                    UUID.fromString(terminal.getCharacterId())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void actualWriterFeedsDeterministicReadWithoutChangingRetainedRows() throws Exception {
    UUID account = UUID.randomUUID();
    var proof = proof(account, UUID.randomUUID());
    var terminal = repository.settle(proof, null);
    UUID actorId = UUID.fromString(terminal.getCharacterId());
    var beforeActor =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE character_uuid = ?",
                actorId),
            "created actor evidence");
    var beforeOperation =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, original_request, terminal_bytes FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
                proof.action().scope().targetNamespace(),
                UUID.fromString(proof.original().getOperationId())),
            "created operation evidence");

    assertThat(
            repository.readPlayerCreatedRoster(
                new AuthenticatedAccountSelector(account), published(proof)))
        .containsExactly(
            new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor(
                actorId, "Wayfarer"));
    assertThat(
            repository.readPlayerCreatedRoster(
                new AuthenticatedAccountSelector(UUID.randomUUID()), published(proof)))
        .isEmpty();

    var afterActor =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE character_uuid = ?",
                actorId),
            "post-read actor evidence");
    var afterOperation =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, original_request, terminal_bytes FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
                proof.action().scope().targetNamespace(),
                UUID.fromString(proof.original().getOperationId())),
            "post-read operation evidence");
    assertThat(afterActor.get("row_version", String.class))
        .isEqualTo(beforeActor.get("row_version", String.class));
    assertThat(afterActor.get("authored_initial_core_json", String.class))
        .isEqualTo(beforeActor.get("authored_initial_core_json", String.class));
    assertThat(afterActor.get("creation_descriptor_json", String.class))
        .isEqualTo(beforeActor.get("creation_descriptor_json", String.class));
    assertThat(afterActor.get("creation_descriptor_digest", String.class))
        .isEqualTo(beforeActor.get("creation_descriptor_digest", String.class));
    assertThat(afterOperation.get("row_version", String.class))
        .isEqualTo(beforeOperation.get("row_version", String.class));
    assertThat(afterOperation.get("original_request", byte[].class))
        .containsExactly(beforeOperation.get("original_request", byte[].class));
    assertThat(afterOperation.get("terminal_bytes", byte[].class))
        .containsExactly(beforeOperation.get("terminal_bytes", byte[].class));
  }

  @Test
  void selectedReadReturnsExactCommittedTerminalAndRejectsOutOfRosterActors() throws Exception {
    UUID accountId = UUID.randomUUID();
    var proof = proof(accountId, UUID.randomUUID());
    EntityCharacterCreateTerminal original = repository.settle(proof, null);
    UUID characterId = UUID.fromString(original.getCharacterId());
    var account = new AuthenticatedAccountSelector(accountId);
    var published = published(proof);
    var actorBefore =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, name, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE character_uuid = ?",
                characterId));
    var operationBefore =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, operation_id, mutation_digest, original_request, terminal_bytes FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
                proof.action().scope().targetNamespace(),
                UUID.fromString(proof.original().getOperationId())));
    int actorCountBefore = dsl.fetchCount(DSL.table("characters"));

    EntityCharacterCreateTerminal selected =
        repository.readSelectedPlayerCreatedTerminal(account, published, characterId);
    assertThat(selected.toByteArray()).containsExactly(original.toByteArray());
    assertThat(selected.getOriginalRequest().getOriginal().getOperationId())
        .isEqualTo(operationBefore.get("operation_id", UUID.class).toString());
    assertThat(selected.getAccountOriginal().getMutationDigest())
        .isEqualTo(operationBefore.get("mutation_digest", String.class));

    assertSelectedActorUnavailable(
        new AuthenticatedAccountSelector(UUID.randomUUID()), published, characterId);
    assertSelectedActorUnavailable(
        account,
        new PublishedIdentity(
            published.targetNamespace(),
            published.tenantId(),
            UUID.randomUUID(),
            published.worldSlug(),
            published.realmSlug(),
            published.playableStateNamespaceId(),
            published.canonicalVersionId(),
            published.projection()),
        characterId);
    assertSelectedActorUnavailable(
        account,
        new PublishedIdentity(
            published.targetNamespace(),
            published.tenantId(),
            published.realmId(),
            published.worldSlug(),
            published.realmSlug(),
            UUID.randomUUID(),
            published.canonicalVersionId(),
            published.projection()),
        characterId);
    assertSelectedActorUnavailable(account, published, UUID.randomUUID());

    var actorAfter =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, name, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE character_uuid = ?",
                characterId));
    var operationAfter =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS row_version, operation_id, mutation_digest, original_request, terminal_bytes FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
                proof.action().scope().targetNamespace(),
                UUID.fromString(proof.original().getOperationId())));
    assertThat(actorAfter.intoMap()).isEqualTo(actorBefore.intoMap());
    assertThat(operationAfter.get("row_version", String.class))
        .isEqualTo(operationBefore.get("row_version", String.class));
    assertThat(operationAfter.get("operation_id", UUID.class))
        .isEqualTo(operationBefore.get("operation_id", UUID.class));
    assertThat(operationAfter.get("mutation_digest", String.class))
        .isEqualTo(operationBefore.get("mutation_digest", String.class));
    assertThat(operationAfter.get("original_request", byte[].class))
        .containsExactly(operationBefore.get("original_request", byte[].class));
    assertThat(operationAfter.get("terminal_bytes", byte[].class))
        .containsExactly(operationBefore.get("terminal_bytes", byte[].class));
    assertThat(dsl.fetchCount(DSL.table("characters"))).isEqualTo(actorCountBefore);
  }

  @Test
  void emptyDiscoveryAllocatesNothingAndOmitsLegacyQuarantinedDecoys() throws Exception {
    var proof = proof(UUID.randomUUID(), UUID.randomUUID());
    var published = published(proof);
    var account =
        new AuthenticatedAccountSelector(UUID.fromString(proof.action().target().accountId()));
    assertThat(repository.readPlayerCreatedRoster(account, published)).isEmpty();
    assertThat(dsl.fetchCount(DSL.table("characters"))).isZero();

    var terminal = repository.settle(proof, null);
    dsl.execute(
        "INSERT INTO characters (character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, account_id, tenant_id, name) VALUES (?, ?, ?, ?, 'PLAYABLE_STATE_SCOPE_SHARED', 45, ?, 'quarantined decoy')",
        UUID.randomUUID(),
        account.accountId(),
        published.tenantId(),
        published.playableStateNamespaceId(),
        87654L);
    assertThat(repository.readPlayerCreatedRoster(account, published))
        .containsExactly(
            new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor(
                UUID.fromString(terminal.getCharacterId()), "Wayfarer"));
    assertThat(dsl.fetchCount(DSL.table("characters"))).isEqualTo(2);
  }

  @Test
  void rejectsCurrentIdentityAndRetainedDescriptorProvenanceMismatch() throws Exception {
    UUID account = UUID.randomUUID();
    var proof = proof(account, UUID.randomUUID());
    var terminal = repository.settle(proof, null);
    var published = published(proof);
    var changedRealm =
        new PublishedIdentity(
            published.targetNamespace(),
            published.tenantId(),
            UUID.randomUUID(),
            published.worldSlug(),
            published.realmSlug(),
            published.playableStateNamespaceId(),
            published.canonicalVersionId(),
            published.projection());
    assertThatThrownBy(
            () ->
                repository.readPlayerCreatedRoster(
                    new AuthenticatedAccountSelector(account), changedRealm))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining("DATA_LOSS");

    dsl.execute("ALTER TABLE characters DISABLE TRIGGER trg_entity_created_actor_immutable");
    try {
      dsl.execute(
          "UPDATE characters SET creation_descriptor_json = '{}' WHERE character_uuid = ?",
          UUID.fromString(terminal.getCharacterId()));
      assertThatThrownBy(
              () ->
                  repository.readPlayerCreatedRoster(
                      new AuthenticatedAccountSelector(account), published))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .hasMessageContaining("DATA_LOSS");
      assertThatThrownBy(
              () ->
                  repository.readSelectedPlayerCreatedTerminal(
                      new AuthenticatedAccountSelector(account),
                      published,
                      UUID.fromString(terminal.getCharacterId())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .hasMessageContaining("DATA_LOSS");
    } finally {
      dsl.execute("ALTER TABLE characters ENABLE TRIGGER trg_entity_created_actor_immutable");
    }
  }

  private void assertSelectedActorUnavailable(
      AuthenticatedAccountSelector account, PublishedIdentity published, UUID characterId) {
    assertThatThrownBy(
            () -> repository.readSelectedPlayerCreatedTerminal(account, published, characterId))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining("FAILED_PRECONDITION")
        .hasMessageContaining("Selected PLAYER_CREATED character is unavailable");
  }

  @Test
  void explicitAbortPermanentlyWinsAgainstFutureCommitAndChangedDigestConflicts() throws Exception {
    var proof = proof(UUID.randomUUID(), UUID.randomUUID());
    String abort = UUID.randomUUID().toString();
    var terminal = repository.settle(proof, abort);
    assertThat(terminal.getOutcome())
        .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED);
    assertThat(repository.settle(proof, null).toByteArray())
        .containsExactly(terminal.toByteArray());
    assertThat(dsl.fetchCount(DSL.table("characters"))).isZero();
    assertThatThrownBy(
            () ->
                repository.read(
                    proof.request().getOriginal().toBuilder()
                        .setMutationDigest("f".repeat(64))
                        .build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining("ALREADY_EXISTS");
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_character_create_operations"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void expiredAuthorityAllocatesNothingButExplicitAbortRemainsDefinitive() throws Exception {
    var proof = proof(UUID.randomUUID(), UUID.randomUUID());
    var expired =
        new EntityCharacterCreateRepository(
            dsl, Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));
    assertThatThrownBy(() -> expired.settle(proof, null))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining("expired");
    assertThat(dsl.fetchCount(DSL.table("characters"))).isZero();
    assertThat(repository.read(proof.request().getOriginal())).isEmpty();
    assertThat(expired.settle(proof, UUID.randomUUID().toString()).getOutcome())
        .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED);
  }

  @Test
  void futureRemoteObservationAndAcquisitionsDoNotDenySolelyDueToClockSkew() throws Exception {
    var proof = proof(UUID.randomUUID(), UUID.randomUUID(), true);
    assertThat(proof.action().expectedVersion().evaluatedAt()).isAfter(NOW);
    assertThat(Instant.parse(proof.account().getAcquiredAt())).isAfter(NOW);
    assertThat(Instant.parse(proof.gameSession().acquiredAt())).isAfter(NOW);
    assertThat(Instant.parse(proof.world().getAcquiredAt())).isAfter(NOW);
    assertThat(repository.settle(proof, null).getOutcome())
        .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED);
    assertThat(dsl.fetchCount(DSL.table("characters"))).isEqualTo(1);
  }

  @Test
  void distinctRequestsCannotBothCrossTheSameZeroRoster() throws Exception {
    UUID account = UUID.randomUUID();
    var first = proof(account, UUID.randomUUID());
    var second = proof(account, UUID.randomUUID());
    var results = race(() -> repository.settle(first, null), () -> repository.settle(second, null));
    assertThat(results.stream().filter(EntityCharacterCreateTerminal.class::isInstance).count())
        .isEqualTo(1L);
    assertThat(results.stream().filter(io.grpc.StatusRuntimeException.class::isInstance).count())
        .isEqualTo(1L);
    assertThat(dsl.fetchCount(DSL.table("characters"))).isEqualTo(1);
    var loser = repository.read(first.request().getOriginal()).isEmpty() ? first : second;
    assertThat(repository.settle(loser, UUID.randomUUID().toString()).getOutcome())
        .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED);
    assertThat(dsl.fetchCount(DSL.table("characters"))).isEqualTo(1);
  }

  @Test
  void commitAndAbortSerializeToOneImmutableTerminal() throws Exception {
    var proof = proof(UUID.randomUUID(), UUID.randomUUID());
    String abort = UUID.randomUUID().toString();
    var results = race(() -> repository.settle(proof, null), () -> repository.settle(proof, abort));
    assertThat(results)
        .allSatisfy(value -> assertThat(value).isInstanceOf(EntityCharacterCreateTerminal.class));
    var first = (EntityCharacterCreateTerminal) results.getFirst();
    var second = (EntityCharacterCreateTerminal) results.get(1);
    assertThat(second.toByteArray()).containsExactly(first.toByteArray());
    assertThat(dsl.fetchCount(DSL.table("characters")))
        .isEqualTo(first.getCharacterId().isEmpty() ? 0 : 1);
  }

  @Test
  void legacyRowsKeepTheirConstraintsAndCannotBeRelabelled() {
    dsl.execute(
        "INSERT INTO characters (account_id, tenant_id, name) VALUES (76543, 87654, 'legacy')");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE characters SET actor_entry_policy = 'PLAYER_CREATED' WHERE name = 'legacy'"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO characters (account_id, tenant_id, name, level) VALUES (76544, 87654, 'invalid', NULL)"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT actor_identity_status FROM characters WHERE name = 'legacy'"))
                .get(0, String.class))
        .isEqualTo("QUARANTINED");
  }

  /** Retains the existing controlled source fixture in the caller's PostgreSQL schema. */
  static EntityEmptySelectedSourceIntakeReceipt retainControlledSource(DSLContext ownerDsl) {
    var controlled = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    return retainControlledSource(ownerDsl, controlled);
  }

  static EntityEmptySelectedSourceIntakeReceipt retainControlledSource(
      DSLContext ownerDsl, DraftCommitBinding selectedCommit) {
    var controlled = EntityEmptySelectedSourceIntakePostgresFixture.create("1", selectedCommit);
    return retainControlledSource(ownerDsl, controlled);
  }

  private static EntityEmptySelectedSourceIntakeReceipt retainControlledSource(
      DSLContext ownerDsl, EntityEmptySelectedSourceIntakePostgresFixture.Fixture controlled) {
    var inputs = controlled.inputs();
    String digest =
        EntityEmptySelectedSourceIntakeReceipt.requestDigest(
            inputs.authorizationBinding().targetNamespace(),
            inputs.authorizationBinding(),
            inputs.worldInventoryReadEvidence().request().freezeEvidence());
    return new EntityEmptySelectedSourceIntakeRepository(ownerDsl).retainFresh(inputs, digest);
  }

  private EntityCharacterCreateProofs proof(UUID accountId, UUID operationId) throws Exception {
    return proof(accountId, operationId, false);
  }

  private EntityCharacterCreateProofs proof(
      UUID accountId, UUID operationId, boolean futureRemoteClocks) throws Exception {
    return proof(source, accountId, operationId, futureRemoteClocks, null);
  }

  /** Reuses the controlled owner-proof double while binding the actual mTLS published identity. */
  static EntityCharacterCreateProofs proofForRosterCapture(
      EntityEmptySelectedSourceIntakeReceipt entitySource,
      UUID accountId,
      UUID operationId,
      AuthenticatedSource rosterSource)
      throws Exception {
    return proof(entitySource, accountId, operationId, false, rosterSource);
  }

  private static EntityCharacterCreateProofs proof(
      EntityEmptySelectedSourceIntakeReceipt entitySource,
      UUID accountId,
      UUID operationId,
      boolean futureRemoteClocks,
      AuthenticatedSource rosterSource)
      throws Exception {
    String raw;
    try (InputStream input =
        Objects.requireNonNull(
            EntityCharacterCreatePostgresIntegrationTest.class.getResourceAsStream(
                "/charactercreate/entity-public-action.json"))) {
      raw = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
    raw =
        raw.replace(
                "\"targetNamespace\":\"firemud\"",
                "\"targetNamespace\":\"" + entitySource.targetNamespace() + "\"")
            .replace(
                "a1000000-0000-4000-8000-000000000001", entitySource.canonicalTenantId().toString())
            .replace(
                "a6000000-0000-4000-8000-000000000001",
                entitySource.canonicalVersionId().toString())
            .replace("a4000000-0000-4000-8000-000000000001", accountId.toString())
            .replace(
                "a9000000-0000-4000-8000-000000000001", entitySource.selectedCommitId().toString())
            .replace(
                "sha256:e4da1526c7e4896ea8a951fb8e0bc9af6306d0a2ce8199eb877be5927c731c3a",
                entitySource.authorizationBinding().selected().digest())
            .replace(
                "a8000000-0000-4000-8000-000000000001",
                entitySource
                    .inputs()
                    .worldInventoryReadEvidence()
                    .inventory()
                    .publicEvidence()
                    .ownerScope()
                    .sourceOperationId()
                    .toString())
            .replace(
                "sha256:01e3d2f5ca1c0f6c6a17db5546729a7e4971e5b4972602b81f0556f2e5462263",
                entitySource
                    .inputs()
                    .worldInventoryReadEvidence()
                    .inventory()
                    .publicEvidence()
                    .ownerScope()
                    .sourceEvidenceDigest());
    if (futureRemoteClocks)
      raw = raw.replace("2026-10-11T12:34:56.123456789Z", "2026-10-11T12:38:00Z");
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
    CharacterCreateOperatorAction action = CharacterCreateOperatorActionCodec.decode(bytes);
    if (rosterSource != null) {
      action = requireRosterSourceAction(action, entitySource, accountId, rosterSource);
      bytes = CharacterCreateOperatorActionCodec.encode(action);
    }
    Instant controlledOwnerReadsAt =
        rosterSource == null ? null : action.expectedVersion().evaluatedAt().plusSeconds(1);
    String accountAcquiredAt =
        futureRemoteClocks
            ? "2026-10-11T12:38:01Z"
            : controlledOwnerReadsAt == null
                ? "2026-10-11T12:35:00Z"
                : controlledOwnerReadsAt.toString();
    String gameSessionAcquiredAt =
        futureRemoteClocks
            ? "2026-10-11T12:38:02Z"
            : controlledOwnerReadsAt == null
                ? "2026-10-11T12:35:01Z"
                : controlledOwnerReadsAt.plusSeconds(1).toString();
    String worldAcquiredAt =
        futureRemoteClocks
            ? "2026-10-11T12:38:03Z"
            : controlledOwnerReadsAt == null
                ? "2026-10-11T12:35:02Z"
                : controlledOwnerReadsAt.plusSeconds(2).toString();
    var original =
        ReadOriginalCharacterCreateResponse.newBuilder()
            .setTargetNamespace(entitySource.targetNamespace())
            .setOperationId(operationId.toString())
            .setCreateCharacterRequestId(UUID.randomUUID().toString())
            .setConnectScopeId("Issued.Scope-Exact_Opaque.mixedCase-01")
            .setAuthenticatedAccountId(accountId.toString())
            .setPurpose("CHARACTER_CREATE")
            .setOriginalAction(ByteString.copyFrom(bytes))
            .setMutationDigest(CharacterCreateOperatorActionCodec.mutationDigest(action))
            .setOriginalSourceReadback(ByteString.copyFromUtf8("controlled-repository-source"))
            .build();
    var reference =
        ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace(entitySource.targetNamespace())
            .setOperationId(operationId.toString())
            .setMutationDigest(original.getMutationDigest())
            .build();
    var accountRef = protection();
    var gsRef = protection();
    var worldRef = protection();
    var request =
        CommitProtectedCharacterCreateRequest.newBuilder()
            .setOriginal(reference)
            .setAccountProtection(accountRef)
            .setGameSessionProtection(gsRef)
            .setWorldProtection(worldRef)
            .build();
    var account =
        ReadOriginalCharacterCreateProtectionResponse.newBuilder()
            .setOriginal(original)
            .setAccountProtectionId(accountRef.getProtectionId())
            .setAccountProtectionFence(1L)
            .setAcquiredAt(accountAcquiredAt)
            .setOriginalMembershipSnapshot(ByteString.copyFromUtf8("controlled-membership"))
            .setOriginalEntitlementSnapshot(ByteString.copyFromUtf8("controlled-entitlement"))
            .build();
    var gs =
        new GameSessionCharacterCreateProtectionReader.Readback(
            account,
            gsRef,
            gameSessionAcquiredAt,
            ByteString.copyFromUtf8("controlled-GS-repository-proof"));
    var world =
        WorldCharacterCreateLifecycleProtection.newBuilder()
            .setAccountProtection(account)
            .setWorldProtectionId(worldRef.getProtectionId())
            .setWorldProtectionFence(1L)
            .setAcquiredAt(worldAcquiredAt)
            .setOriginalLifecycleEvidence(
                ByteString.copyFromUtf8("controlled-World-repository-proof"))
            .build();
    var proof = mock(EntityCharacterCreateProofs.class);
    when(proof.request()).thenReturn(request);
    when(proof.original()).thenReturn(original);
    when(proof.action()).thenReturn(action);
    when(proof.account()).thenReturn(account);
    when(proof.gameSession()).thenReturn(gs);
    when(proof.world()).thenReturn(world);
    when(proof.entitySource()).thenReturn(entitySource);
    return proof;
  }

  private static CharacterCreateOperatorAction requireRosterSourceAction(
      CharacterCreateOperatorAction original,
      EntityEmptySelectedSourceIntakeReceipt entitySource,
      UUID accountId,
      AuthenticatedSource source) {
    var target = source.target();
    var request = source.response().getRequest();
    var catalog = source.response().getCatalog();
    var pointer = source.response().getPointer();
    var projection = source.authored().projections().getFirst();
    if (!entitySource.targetNamespace().equals(request.getTargetNamespace())
        || !entitySource.canonicalTenantId().equals(target.tenantUuid())
        || !entitySource.canonicalVersionId().equals(target.canonicalVersionUuid())
        || !entitySource.selectedCommitId().equals(projection.selectedCommitId())) {
      throw new IllegalArgumentException(
          "Controlled Entity writer fixture does not match the authenticated publication source");
    }
    var authored = authoredSource(projection);
    return new CharacterCreateOperatorAction(
        new CharacterCreateOperatorAction.Scope(
            request.getTargetNamespace(),
            target.tenantUuid().toString(),
            target.playableStateNamespaceId().toString(),
            CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
        new CharacterCreateOperatorAction.Target(
            accountId.toString(),
            target.realmUuid().toString(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid().toString(),
            target.canonicalVersionUuid().toString()),
        new CharacterCreateOperatorAction.ExpectedVersion(
            Long.toString(target.catalogRevision()),
            Long.toString(target.pointerVersion()),
            Long.toString(target.activeWorldEpoch()),
            EntityCharacterCreateBindings.instant(pointer.getUpdatedAt()).plusSeconds(1),
            EntityCharacterCreateBindings.instant(pointer.getUpdatedAt()).plusSeconds(301)),
        new CharacterCreateOperatorAction.Mutation(
            original.mutation().purpose(),
            original.mutation().entryPolicy(),
            new CharacterCreateOperatorAction.Catalog(
                target.tenantUuid().toString(),
                catalog.getTenantSlug(),
                target.worldSlug(),
                catalog.getWorldDisplayName(),
                target.realmUuid().toString(),
                target.realmSlug(),
                catalog.getRealmDisplayName(),
                catalog.getVisible(),
                catalog.getPublicProduction(),
                catalog.getStateScope(),
                target.playableStateNamespaceId().toString(),
                catalog.getCharacterCreationPolicy(),
                Long.toString(target.catalogRevision())),
            new CharacterCreateOperatorAction.Pointer(
                target.tenantUuid().toString(),
                target.realmUuid().toString(),
                target.worldSlug(),
                target.realmSlug(),
                pointer.getAdmissionState(),
                target.gameInstanceUuid().toString(),
                Long.toString(target.pointerVersion()),
                Long.toString(target.catalogRevision()),
                EntityCharacterCreateBindings.instant(pointer.getUpdatedAt())),
            authored,
            original.mutation().canonicalCreationInput()),
        original.auditReason());
  }

  private static CharacterCreateOperatorAction.AuthoredSource authoredSource(
      AuthoredInitialActorCoreProjection projection) {
    var policy = projection.policy();
    return new CharacterCreateOperatorAction.AuthoredSource(
        AuthoredInitialActorCoreProjection.SCHEMA,
        projection.bindingId().toString(),
        projection.bindingDigest(),
        projection.canonicalTenantId().toString(),
        projection.canonicalVersionId().toString(),
        projection.worldSourceOperationId().toString(),
        projection.worldSourceEvidenceDigest(),
        projection.worldSlug(),
        projection.publicationWorkflowId(),
        projection.selectedCommitId().toString(),
        projection.selectedCommitDigest(),
        new CharacterCreateOperatorAction.Policy(
            policy.commitId().toString(),
            policy.revisionId().toString(),
            policy.logicalRevisionId(),
            policy.canonicalJson(),
            policy.contentDigest()),
        new CharacterCreateOperatorAction.Core(projection.core().canonicalJson()),
        new CharacterCreateOperatorAction.Descriptor(
            projection.descriptor().canonicalJson(), projection.descriptor().digest()),
        authoredRule(projection.disposition()),
        authoredRule(projection.safeFeedback()));
  }

  private static CharacterCreateOperatorAction.Rule authoredRule(
      AuthoredInitialActorCoreProjection.RuleDefinition rule) {
    return new CharacterCreateOperatorAction.Rule(
        rule.key(),
        rule.definitionJson(),
        rule.definitionDigest(),
        rule.sourceCommitId().toString(),
        rule.sourceCommitDigest(),
        rule.revisionId().toString(),
        rule.revisionOrder());
  }

  private static PublishedIdentity published(EntityCharacterCreateProofs proof) throws Exception {
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    var root = json.readTree(proof.original().getOriginalAction().toByteArray());
    var action = proof.action();
    var authored = action.mutation().authoredSource();
    var projectionNode =
        (tools.jackson.databind.node.ObjectNode) root.get("mutation").get("authoredSource");
    projectionNode.put("publicationWorkflowId", authored.publicationWorkflowId());
    ((tools.jackson.databind.node.ObjectNode) projectionNode.get("policy"))
        .put("logicalRevisionId", authored.policy().logicalRevisionId())
        .put("canonicalPolicyJson", authored.policy().canonicalPolicyJson());
    ((tools.jackson.databind.node.ObjectNode) projectionNode.get("core"))
        .put("canonicalJson", authored.core().canonicalJson());
    ((tools.jackson.databind.node.ObjectNode) projectionNode.get("descriptor"))
        .put("canonicalJson", authored.descriptor().canonicalJson());
    ((tools.jackson.databind.node.ObjectNode) projectionNode.get("disposition"))
        .put("definitionJson", authored.disposition().definitionJson());
    ((tools.jackson.databind.node.ObjectNode) projectionNode.get("safeFeedback"))
        .put("definitionJson", authored.safeFeedback().definitionJson());
    byte[] projectionBytes =
        Rfc8785CanonicalJson.canonicalizeUtf8(json.writeValueAsString(projectionNode));
    var projection =
        AuthoredInitialActorCoreProjection.fromStored(
            projectionBytes, CommandSource.sha256(projectionBytes));
    return new PublishedIdentity(
        action.scope().targetNamespace(),
        UUID.fromString(action.scope().tenantId()),
        UUID.fromString(action.target().realmId()),
        action.target().worldSlug(),
        action.target().realmSlug(),
        UUID.fromString(action.scope().playableStateNamespaceId()),
        UUID.fromString(action.target().versionId()),
        projection);
  }

  private static CharacterCreateProtectionReference protection() {
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(UUID.randomUUID().toString())
        .setProtectionFence(1L)
        .build();
  }

  private static List<Object> race(
      Callable<EntityCharacterCreateTerminal> first, Callable<EntityCharacterCreateTerminal> second)
      throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var barrier = new CyclicBarrier(2);
      Callable<Object> a =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              return first.call();
            } catch (io.grpc.StatusRuntimeException failed) {
              return failed;
            }
          };
      Callable<Object> b =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              return second.call();
            } catch (io.grpc.StatusRuntimeException failed) {
              return failed;
            }
          };
      var fa = workers.submit(a);
      var fb = workers.submit(b);
      return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
    }
  }

  private static DriverManagerDataSource dataSource(String schema) {
    String url = postgres.getJdbcUrl();
    if (schema != null) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
  }

  private static void migrate(DriverManagerDataSource dataSource, String schema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
                "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) configuration.target(target);
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
  }

  private static List<String> migrationHistory(DSLContext source) {
    return source
        .fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT FROM flyway_schema_history t ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static List<String> migrationVersions(DSLContext source) {
    return source
        .fetch(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static void seedRetainedParentActorAndRoster(
      DSLContext source, UUID tenant, UUID account, UUID namespace, UUID character, UUID snapshot) {
    source.execute(
        "INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?)", tenant);
    Long tenantRow =
        source
            .fetchSingle(
                "SELECT entity_tenant_row_id FROM entity_tenant_identities WHERE canonical_tenant_uuid = ?",
                tenant)
            .get("entity_tenant_row_id", Long.class);
    source.execute(
        "INSERT INTO entity_playable_state_namespace_scopes (tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, 'PLAYABLE_STATE_SCOPE_SHARED')",
        tenant,
        namespace);
    source.execute(
        "INSERT INTO characters (character_uuid, account_id, tenant_id, playable_state_key, name, level, experience, strength, agility, intelligence, stamina, health, mana, body_layout_key, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason) VALUES (?, 101, ?, 'shared-live', 'Retained actor', 1, 0, 10, 10, 10, 10, 100, 100, 'legacy-body', ?, ?, ?, 'PLAYABLE_STATE_SCOPE_SHARED', 'OWNER_RESOLVED', NULL)",
        character,
        tenantRow,
        account,
        tenant,
        namespace);
    source.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots (snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, publication_evidence_format, pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, admission_pointer_snapshot_digest, published_owner_proof_digest, playable_state_namespace_uuid, playable_state_scope, entry_policy, roster_count) VALUES (?, ?, ?, ?, ?, 'world', 'realm', ?, 1, ?, 'CANONICAL_UUID_V2', 1, 1, ?, 'retained-release', ?, ?, ?, 'PLAYABLE_STATE_SCOPE_SHARED', 'PRESEEDED_ONLY', 1)",
        snapshot,
        "a".repeat(64),
        account,
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        namespace);
    source.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshot_actors (snapshot_uuid, ordinal_position, character_uuid, display_name, actor_kind) VALUES (?, 0, ?, 'Retained actor', 'PLAYER')",
        snapshot,
        character);
    source.execute(
        "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' WHERE snapshot_uuid = ?",
        snapshot);
  }

  private static List<String> retainedParentRows(DSLContext source, UUID character, UUID snapshot) {
    return List.of(
        source
            .fetchSingle(
                "SELECT (to_jsonb(t) - ARRAY['creation_target_namespace', 'creation_operation_id', 'actor_entry_policy', 'authored_initial_core_json', 'creation_descriptor_json', 'creation_descriptor_digest'])::TEXT || ':' || t.xmin::TEXT FROM characters t WHERE character_uuid = ?",
                character)
            .get(0, String.class),
        source
            .fetchSingle(
                "SELECT (to_jsonb(t) - 'canonical_player_created_source_identity')::TEXT || ':' || t.xmin::TEXT FROM entity_canonical_gameplay_roster_snapshots t WHERE snapshot_uuid = ?",
                snapshot)
            .get(0, String.class),
        source
            .fetchSingle(
                "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT FROM entity_canonical_gameplay_roster_snapshot_actors t WHERE snapshot_uuid = ?",
                snapshot)
            .get(0, String.class));
  }
}
