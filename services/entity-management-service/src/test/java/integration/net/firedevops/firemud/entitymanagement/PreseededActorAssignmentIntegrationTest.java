package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    classes = EntityManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class PreseededActorAssignmentIntegrationTest {
  private UUID assignmentUuid;
  private UUID secondAssignmentUuid;
  private UUID accountUuid;
  private UUID secondAccountUuid;
  private UUID tenantUuid;
  private UUID realmUuid;
  private UUID namespaceUuid;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "entity_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private CharacterRepository characterRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @BeforeEach
  void createIsolatedOwnerProofIdentities() {
    assignmentUuid = UUID.randomUUID();
    secondAssignmentUuid = UUID.randomUUID();
    accountUuid = UUID.randomUUID();
    secondAccountUuid = UUID.randomUUID();
    tenantUuid = UUID.randomUUID();
    realmUuid = UUID.randomUUID();
    namespaceUuid = UUID.randomUUID();
  }

  @Test
  void postgresMigrationInstallsAndEnforcesDeferredAssignmentForeignKeys() {
    assign(
        assignmentUuid,
        accountUuid,
        4L,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        "Assigned Actor",
        "a");

    UUID missingCharacterUuid = UUID.randomUUID();
    UUID invalidOperationUuid = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO entity_preseeded_actor_assignment_operations ("
                        + "assignment_uuid, intent_digest, account_uuid, account_uuid_provenance, "
                        + "account_identity_schema_version, account_identity_target_namespace, "
                        + "source_account_row_id, eligibility, "
                        + "eligibility_evaluated_at, membership_authority_generation, "
                        + "eligibility_evidence_digest, tenant_uuid, realm_uuid, world_slug, "
                        + "realm_slug, game_instance_id, catalog_revision, canonical_version_uuid, "
                        + "publication_evidence_format, frozen_policy_digest, "
                        + "playable_state_namespace_id, playable_state_scope, entry_policy, "
                        + "actor_kind, display_name, status, character_uuid, completed_at, "
                        + "account_authority_snapshot_digest, account_purpose, account_currentness, "
                        + "published_owner_proof_digest, published_release_bundle_ref) "
                        + "SELECT ?, intent_digest, account_uuid, account_uuid_provenance, "
                        + "account_identity_schema_version, account_identity_target_namespace, "
                        + "source_account_row_id, eligibility, "
                        + "eligibility_evaluated_at, membership_authority_generation, "
                        + "eligibility_evidence_digest, tenant_uuid, realm_uuid, world_slug, "
                        + "realm_slug, game_instance_id, catalog_revision, canonical_version_uuid, "
                        + "publication_evidence_format, frozen_policy_digest, "
                        + "playable_state_namespace_id, playable_state_scope, entry_policy, "
                        + "actor_kind, display_name, 'ASSIGNED', ?, CURRENT_TIMESTAMP "
                        + ", account_authority_snapshot_digest, account_purpose, "
                        + "account_currentness, published_owner_proof_digest, "
                        + "published_release_bundle_ref "
                        + "FROM entity_preseeded_actor_assignment_operations "
                        + "WHERE assignment_uuid = ?",
                    invalidOperationUuid,
                    missingCharacterUuid,
                    assignmentUuid))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                    + "WHERE conname IN (?, ?, ?) AND contype = 'f' AND convalidated "
                    + "AND condeferrable AND condeferred "
                    + "AND conrelid = 'entity_preseeded_actor_assignment_operations'::regclass",
                String.class,
                "fk_entity_preseeded_assignment_tenant_identity",
                "fk_entity_preseeded_assignment_namespace",
                "fk_entity_preseeded_assignment_character"))
        .containsExactlyInAnyOrder(
            "fk_entity_preseeded_assignment_tenant_identity",
            "fk_entity_preseeded_assignment_namespace",
            "fk_entity_preseeded_assignment_character");
  }

  @Test
  void migrationRetainsOlderRosterEvidenceWithoutBackfillingOwnerCounters() throws Exception {
    String schema = "entity_publication_history_" + UUID.randomUUID().toString().replace("-", "");
    UUID historicalSnapshot = UUID.randomUUID();
    UUID v8Snapshot = UUID.randomUUID();
    UUID historicalTenant = UUID.randomUUID();
    UUID historicalNamespace = UUID.randomUUID();
    UUID v8CanonicalVersion = UUID.randomUUID();

    migrationFlyway(schema).target(MigrationVersion.fromVersion("7")).load().migrate();
    try (Connection connection = connectToSchema(schema)) {
      try (PreparedStatement tenant =
          connection.prepareStatement(
              "INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?)")) {
        tenant.setObject(1, historicalTenant);
        tenant.executeUpdate();
      }
      try (PreparedStatement namespace =
          connection.prepareStatement(
              "INSERT INTO entity_playable_state_namespace_scopes "
                  + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) "
                  + "VALUES (?, ?, ?)")) {
        namespace.setObject(1, historicalTenant);
        namespace.setObject(2, historicalNamespace);
        namespace.setString(3, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
        namespace.executeUpdate();
      }
      try (PreparedStatement snapshot =
          connection.prepareStatement(
              "INSERT INTO entity_canonical_gameplay_roster_snapshots "
                  + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, "
                  + "realm_uuid, world_slug, realm_slug, game_instance_uuid, catalog_revision, "
                  + "published_version_id, published_version_number, published_policy_digest, "
                  + "published_release_bundle_ref, admission_pointer_snapshot_digest, "
                  + "published_owner_proof_digest, playable_state_namespace_uuid, "
                  + "playable_state_scope, entry_policy, roster_count, construction_state) "
                  + "VALUES (?, ?, ?, ?, ?, 'world', 'realm', ?, 7, 17, 2, ?, "
                  + "'release/test', ?, ?, ?, ?, 'PRESEEDED_ONLY', 0, 'SEALED')")) {
        snapshot.setObject(1, historicalSnapshot);
        snapshot.setString(2, "a".repeat(64));
        snapshot.setObject(3, UUID.randomUUID());
        snapshot.setObject(4, historicalTenant);
        snapshot.setObject(5, UUID.randomUUID());
        snapshot.setObject(6, UUID.randomUUID());
        snapshot.setString(7, "b".repeat(64));
        snapshot.setString(8, "c".repeat(64));
        snapshot.setString(9, "d".repeat(64));
        snapshot.setObject(10, historicalNamespace);
        snapshot.setString(11, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
        snapshot.executeUpdate();
      }
    }

    migrationFlyway(schema).target(MigrationVersion.fromVersion("8")).load().migrate();
    try (Connection connection = connectToSchema(schema);
        PreparedStatement snapshot =
            connection.prepareStatement(
                "INSERT INTO entity_canonical_gameplay_roster_snapshots "
                    + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, "
                    + "realm_uuid, world_slug, realm_slug, game_instance_uuid, catalog_revision, "
                    + "published_version_id, published_version_number, canonical_version_uuid, "
                    + "publication_evidence_format, published_policy_digest, "
                    + "published_release_bundle_ref, admission_pointer_snapshot_digest, "
                    + "published_owner_proof_digest, playable_state_namespace_uuid, "
                    + "playable_state_scope, entry_policy, roster_count, construction_state) "
                    + "VALUES (?, ?, ?, ?, ?, 'world', 'realm', ?, 7, NULL, NULL, ?, "
                    + "'CANONICAL_UUID', ?, 'release/v8', ?, ?, ?, ?, "
                    + "'PRESEEDED_ONLY', 0, 'SEALED')")) {
      snapshot.setObject(1, v8Snapshot);
      snapshot.setString(2, "e".repeat(64));
      snapshot.setObject(3, UUID.randomUUID());
      snapshot.setObject(4, historicalTenant);
      snapshot.setObject(5, UUID.randomUUID());
      snapshot.setObject(6, UUID.randomUUID());
      snapshot.setObject(7, v8CanonicalVersion);
      snapshot.setString(8, "f".repeat(64));
      snapshot.setString(9, "a".repeat(64));
      snapshot.setString(10, "b".repeat(64));
      snapshot.setObject(11, historicalNamespace);
      snapshot.setString(12, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
      snapshot.executeUpdate();
    }

    migrationFlyway(schema).load().migrate();
    try (Connection connection = connectToSchema(schema);
        PreparedStatement retained =
            connection.prepareStatement(
                "SELECT snapshot_digest, publication_evidence_format, canonical_version_uuid, "
                    + "published_version_id, published_version_number, pointer_version, "
                    + "active_world_epoch "
                    + "FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?")) {
      retained.setObject(1, historicalSnapshot);
      try (ResultSet row = retained.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("a".repeat(64));
        assertThat(row.getString(2)).isEqualTo("LEGACY_NUMERIC");
        assertThat(row.getObject(3)).isNull();
        assertThat(row.getLong(4)).isEqualTo(17L);
        assertThat(row.getInt(5)).isEqualTo(2);
        assertThat(row.getObject(6)).isNull();
        assertThat(row.getObject(7)).isNull();
      }
    }
    try (Connection connection = connectToSchema(schema);
        PreparedStatement retained =
            connection.prepareStatement(
                "SELECT snapshot_digest, publication_evidence_format, canonical_version_uuid, "
                    + "published_version_id, published_version_number, pointer_version, "
                    + "active_world_epoch "
                    + "FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?")) {
      retained.setObject(1, v8Snapshot);
      try (ResultSet row = retained.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("e".repeat(64));
        assertThat(row.getString(2)).isEqualTo("CANONICAL_UUID");
        assertThat(row.getObject(3)).isEqualTo(v8CanonicalVersion);
        assertThat(row.getObject(4)).isNull();
        assertThat(row.getObject(5)).isNull();
        assertThat(row.getObject(6)).isNull();
        assertThat(row.getObject(7)).isNull();
      }
    }
  }

  @Test
  void quarantinesEveryRetainedDuplicateBeforeEnforcingUniqueLiveAccountNamespace()
      throws Exception {
    String schema = "entity_preseeded_assignment_" + UUID.randomUUID().toString().replace("-", "");
    UUID retainedAccountUuid = UUID.randomUUID();
    UUID retainedTenantUuid = UUID.randomUUID();
    UUID retainedNamespaceUuid = UUID.randomUUID();

    migrationFlyway(schema).target(MigrationVersion.fromVersion("4")).load().migrate();
    try (Connection connection = connectToSchema(schema)) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO entity_playable_state_namespace_scopes "
                  + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) "
                  + "VALUES (?, ?, ?)")) {
        statement.setObject(1, retainedTenantUuid);
        statement.setObject(2, retainedNamespaceUuid);
        statement.setString(3, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO characters (account_id, name, tenant_id, playable_state_key, "
                  + "character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, "
                  + "playable_state_scope, actor_identity_status, "
                  + "actor_identity_quarantine_reason) "
                  + "VALUES (23, ?, 41, ?, ?, ?, ?, ?, ?, 'OWNER_RESOLVED', NULL)")) {
        for (String retainedName : List.of("Retained duplicate A", "Retained duplicate B")) {
          statement.setString(1, retainedName);
          statement.setString(2, retainedNamespaceUuid.toString());
          statement.setObject(3, UUID.randomUUID());
          statement.setObject(4, retainedAccountUuid);
          statement.setObject(5, retainedTenantUuid);
          statement.setObject(6, retainedNamespaceUuid);
          statement.setString(7, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
          statement.executeUpdate();
        }
      }
    }

    migrationFlyway(schema).load().migrate();

    try (Connection connection = connectToSchema(schema)) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT count(*) FROM characters WHERE account_uuid = ? "
                  + "AND actor_identity_status = 'QUARANTINED' "
                  + "AND actor_identity_quarantine_reason = 'AMBIGUOUS_ACCOUNT_NAMESPACE'")) {
        statement.setObject(1, retainedAccountUuid);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(2L);
        }
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT count(*) FROM pg_indexes WHERE schemaname = ? "
                  + "AND indexname = 'ux_characters_owner_resolved_account_namespace'")) {
        statement.setString(1, schema);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(1L);
        }
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO characters (account_id, name, tenant_id, playable_state_key, "
                  + "character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, "
                  + "playable_state_scope, actor_identity_status, "
                  + "actor_identity_quarantine_reason) "
                  + "VALUES (23, 'New verified assignment', 41, ?, ?, ?, ?, ?, ?, "
                  + "'OWNER_RESOLVED', NULL)")) {
        insert.setString(1, retainedNamespaceUuid.toString());
        insert.setObject(2, UUID.randomUUID());
        insert.setObject(3, retainedAccountUuid);
        insert.setObject(4, retainedTenantUuid);
        insert.setObject(5, retainedNamespaceUuid);
        insert.setString(6, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }
      try (PreparedStatement duplicate =
          connection.prepareStatement(
              "INSERT INTO characters (account_id, name, tenant_id, playable_state_key, "
                  + "character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, "
                  + "playable_state_scope, actor_identity_status, "
                  + "actor_identity_quarantine_reason) "
                  + "VALUES (23, 'Duplicate live actor', 41, ?, ?, ?, ?, ?, ?, "
                  + "'OWNER_RESOLVED', NULL)")) {
        duplicate.setString(1, retainedNamespaceUuid.toString());
        duplicate.setObject(2, UUID.randomUUID());
        duplicate.setObject(3, retainedAccountUuid);
        duplicate.setObject(4, retainedTenantUuid);
        duplicate.setObject(5, retainedNamespaceUuid);
        duplicate.setString(6, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED.name());
        assertThatThrownBy(duplicate::executeUpdate).isInstanceOf(SQLException.class);
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT count(*) FILTER (WHERE actor_identity_status = 'QUARANTINED'), "
                  + "count(*) FILTER (WHERE actor_identity_status = 'OWNER_RESOLVED') "
                  + "FROM characters WHERE account_uuid = ?")) {
        statement.setObject(1, retainedAccountUuid);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(2L);
          assertThat(rows.getLong(2)).isEqualTo(1L);
        }
      }
    }
  }

  @Test
  void exactRetryReturnsSamePersistedActorAndChangedIntentConflictsBeforeAllocation() {
    PreseededActorAssignmentResult first =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "a");
    PreseededActorAssignmentResult retry =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "a");
    PreseededActorAssignmentResult changedIntent =
        assign(
            assignmentUuid,
            accountUuid,
            5L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "b");

    assertThat(first.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
    assertThat(retry).isEqualTo(first);
    assertThat(retry.characterUuid()).isEqualTo(first.characterUuid());
    assertConflictWithoutActorDisclosure(changedIntent);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE character_uuid = ? "
                    + "AND account_uuid = ? AND tenant_uuid = ? "
                    + "AND playable_state_namespace_id = ? AND actor_identity_status = 'OWNER_RESOLVED'",
                Integer.class,
                first.characterUuid(),
                accountUuid,
                tenantUuid,
                namespaceUuid))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_preseeded_actor_assignment_operations "
                    + "WHERE assignment_uuid = ?",
                Integer.class,
                assignmentUuid))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters c "
                    + "JOIN entity_tenant_identities t "
                    + "ON c.tenant_id = t.entity_tenant_row_id "
                    + "WHERE c.character_uuid = ? AND t.canonical_tenant_uuid = ?",
                Integer.class,
                first.characterUuid(),
                tenantUuid))
        .isEqualTo(1);
  }

  @Test
  void committedReceiptIsExactReplayStableAndDeniedForChangedOrQuarantinedActor() {
    PreseededActorAssignmentResult assigned =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "a");
    var evidence =
        evidence(assignmentUuid, accountUuid, 4L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    var expectedTarget =
        new PreseededActorAssignmentExpectedTarget(
            tenantUuid,
            realmUuid,
            "world",
            "realm",
            "game-instance",
            4L,
            UUID.fromString("17000000-0000-4000-8000-000000000017"),
            "2".repeat(64),
            namespaceUuid,
            "release-bundle/test",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    var exactRequest =
        new PreseededActorAssignmentReceiptRequest(
            assignmentUuid,
            accountUuid,
            assigned.characterUuid(),
            assigned.intentDigest(),
            expectedTarget);

    var receipt =
        characterRepository.readPreseededActorAssignmentReceipt(exactRequest).orElseThrow();
    assertThat(characterRepository.readPreseededActorAssignmentReceipt(exactRequest))
        .contains(receipt);
    assertThat(receipt.assignmentUuid()).isEqualTo(assignmentUuid);
    assertThat(receipt.canonicalAccountUuid()).isEqualTo(accountUuid);
    assertThat(receipt.characterUuid()).isEqualTo(assigned.characterUuid());
    assertThat(receipt.intentDigest()).isEqualTo(assigned.intentDigest());
    assertThat(receipt.target()).isEqualTo(expectedTarget);
    assertThat(receipt.accountPurpose())
        .isEqualTo(PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING);
    assertThat(receipt.accountCurrentness())
        .isEqualTo(
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION);
    assertThat(receipt.accountIdentitySchemaVersion()).isEqualTo(1);
    assertThat(receipt.accountIdentityTargetNamespace()).isEqualTo("dev");
    assertThat(receipt.sourceAccountRowId()).isEqualTo(23L);
    assertThat(receipt.accountUuidProvenance()).isEqualTo("ACCOUNT_DATABASE_INSERT");
    assertThat(receipt.eligibility())
        .isEqualTo(PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE);
    assertThat(receipt.eligibleAtRevalidation()).isTrue();
    assertThat(receipt.publishedReleaseBundleRef()).isEqualTo("release-bundle/test");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT canonical_version_uuid FROM entity_preseeded_actor_assignment_operations "
                    + "WHERE assignment_uuid = ?",
                UUID.class,
                assignmentUuid))
        .isEqualTo(expectedTarget.canonicalVersionUuid());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT publication_evidence_format FROM entity_preseeded_actor_assignment_operations "
                    + "WHERE assignment_uuid = ?",
                String.class,
                assignmentUuid))
        .isEqualTo("CANONICAL_UUID");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_preseeded_actor_assignment_operations "
                        + "SET canonical_version_uuid = ? WHERE assignment_uuid = ?",
                    UUID.randomUUID(),
                    assignmentUuid))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            characterRepository.readPreseededActorAssignmentReceipt(
                new PreseededActorAssignmentReceiptRequest(
                    assignmentUuid,
                    accountUuid,
                    assigned.characterUuid(),
                    "f".repeat(64),
                    expectedTarget)))
        .isEmpty();
    assertThat(
            characterRepository.readPreseededActorAssignmentReceipt(
                new PreseededActorAssignmentReceiptRequest(
                    assignmentUuid,
                    secondAccountUuid,
                    assigned.characterUuid(),
                    assigned.intentDigest(),
                    expectedTarget)))
        .isEmpty();
    assertThat(
            characterRepository.readPreseededActorAssignmentReceipt(
                new PreseededActorAssignmentReceiptRequest(
                    assignmentUuid,
                    accountUuid,
                    UUID.randomUUID(),
                    assigned.intentDigest(),
                    expectedTarget)))
        .isEmpty();

    jdbcTemplate.update(
        "UPDATE characters SET account_id = 24 WHERE character_uuid = ?", assigned.characterUuid());
    assertThat(characterRepository.readPreseededActorAssignmentReceipt(exactRequest)).isEmpty();
    jdbcTemplate.update(
        "UPDATE characters SET account_id = 23 WHERE character_uuid = ?", assigned.characterUuid());

    jdbcTemplate.update(
        "UPDATE characters SET actor_identity_status = 'QUARANTINED', "
            + "actor_identity_quarantine_reason = 'OWNER_PROVENANCE_MISSING' "
            + "WHERE character_uuid = ?",
        assigned.characterUuid());
    assertThat(characterRepository.readPreseededActorAssignmentReceipt(exactRequest)).isEmpty();

    PreseededActorAssignmentOwnerEvidence changedReleaseRef =
        withPublishedReleaseBundleRef(evidence, "release-bundle/changed");
    PreseededActorAssignmentRequest replayRequest = assignmentRequest(changedReleaseRef);
    PreseededActorAssignmentResult changedRelease =
        characterRepository.assignPreseededActor(
            replayRequest,
            changedReleaseRef,
            accountIdentity(assignmentUuid, accountUuid),
            replayRequest.mutationIntentDigest());
    assertConflictWithoutActorDisclosure(changedRelease);
  }

  @Test
  void selectedAssignmentReadRequiresExactOwnerTargetAndCurrentActorProvenance() {
    PreseededActorAssignmentResult assigned =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Selected Actor",
            "a");
    var ownerEvidence =
        evidence(assignmentUuid, accountUuid, 4L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    var expectedTarget = assignmentRequest(ownerEvidence).expectedTarget();

    var receipt =
        characterRepository
            .readSelectedPreseededActorAssignmentReceipt(
                accountUuid,
                assigned.characterUuid(),
                expectedTarget,
                ownerEvidence.publishedOwnerProofDigest())
            .orElseThrow();
    assertThat(receipt.assignmentUuid()).isEqualTo(assignmentUuid);
    assertThat(receipt.canonicalAccountUuid()).isEqualTo(accountUuid);
    assertThat(receipt.characterUuid()).isEqualTo(assigned.characterUuid());
    assertThat(receipt.intentDigest()).isEqualTo(assigned.intentDigest());

    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                secondAccountUuid,
                assigned.characterUuid(),
                expectedTarget,
                ownerEvidence.publishedOwnerProofDigest()))
        .isEmpty();
    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                accountUuid,
                UUID.randomUUID(),
                expectedTarget,
                ownerEvidence.publishedOwnerProofDigest()))
        .isEmpty();
    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                accountUuid,
                assigned.characterUuid(),
                new PreseededActorAssignmentExpectedTarget(
                    tenantUuid,
                    realmUuid,
                    "world",
                    "realm",
                    "game-instance",
                    5L,
                    expectedTarget.canonicalVersionUuid(),
                    expectedTarget.frozenPolicyDigest(),
                    namespaceUuid,
                    expectedTarget.publishedReleaseBundleRef(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
                ownerEvidence.publishedOwnerProofDigest()))
        .isEmpty();
    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                accountUuid,
                assigned.characterUuid(),
                new PreseededActorAssignmentExpectedTarget(
                    tenantUuid,
                    realmUuid,
                    "world",
                    "realm",
                    "game-instance",
                    4L,
                    expectedTarget.canonicalVersionUuid(),
                    expectedTarget.frozenPolicyDigest(),
                    namespaceUuid,
                    expectedTarget.publishedReleaseBundleRef(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED),
                ownerEvidence.publishedOwnerProofDigest()))
        .isEmpty();
    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                accountUuid, assigned.characterUuid(), expectedTarget, "f".repeat(64)))
        .isEmpty();

    jdbcTemplate.update(
        "UPDATE characters SET account_uuid = ? WHERE character_uuid = ?",
        secondAccountUuid,
        assigned.characterUuid());
    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                accountUuid,
                assigned.characterUuid(),
                expectedTarget,
                ownerEvidence.publishedOwnerProofDigest()))
        .isEmpty();
    jdbcTemplate.update(
        "UPDATE characters SET account_uuid = ? WHERE character_uuid = ?",
        accountUuid,
        assigned.characterUuid());
    jdbcTemplate.update(
        "UPDATE characters SET actor_identity_status = 'QUARANTINED', "
            + "actor_identity_quarantine_reason = 'OWNER_PROVENANCE_MISSING' "
            + "WHERE character_uuid = ?",
        assigned.characterUuid());
    assertThat(
            characterRepository.readSelectedPreseededActorAssignmentReceipt(
                accountUuid,
                assigned.characterUuid(),
                expectedTarget,
                ownerEvidence.publishedOwnerProofDigest()))
        .isEmpty();
  }

  @Test
  void sameAssignmentUuidConflictsForChangedAccountScopeOrPayloadWithoutReturningActor() {
    PreseededActorAssignmentResult original =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "a");
    PreseededActorAssignmentResult changedAccount =
        assign(
            assignmentUuid,
            secondAccountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "b");
    PreseededActorAssignmentResult changedScope =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
            "Assigned Actor",
            "c");
    PreseededActorAssignmentResult changedPayload =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Renamed Actor",
            "d");

    assertThat(original.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
    assertConflictWithoutActorDisclosure(changedAccount);
    assertConflictWithoutActorDisclosure(changedScope);
    assertConflictWithoutActorDisclosure(changedPayload);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE actor_identity_status = 'OWNER_RESOLVED' "
                    + "AND tenant_uuid = ?",
                Integer.class,
                tenantUuid))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_preseeded_actor_assignment_operations "
                    + "WHERE assignment_uuid = ? AND status = 'ASSIGNED'",
                Integer.class,
                assignmentUuid))
        .isEqualTo(1);
  }

  @Test
  void separateAuthorizedAssignmentsCanCreateMultipleActorsEvenWithSameDisplayName() {
    PreseededActorAssignmentResult first =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "a");
    PreseededActorAssignmentResult second =
        assign(
            secondAssignmentUuid,
            secondAccountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "b");

    assertThat(first.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
    assertThat(second.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
    assertThat(second.characterUuid()).isNotEqualTo(first.characterUuid());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE tenant_uuid = ? "
                    + "AND playable_state_namespace_id = ? AND account_uuid = ? "
                    + "AND name = 'Assigned Actor' AND actor_identity_status = 'OWNER_RESOLVED'",
                Integer.class,
                tenantUuid,
                namespaceUuid,
                accountUuid))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE tenant_uuid = ? "
                    + "AND playable_state_namespace_id = ? AND account_uuid = ? "
                    + "AND name = 'Assigned Actor' AND actor_identity_status = 'OWNER_RESOLVED'",
                Integer.class,
                tenantUuid,
                namespaceUuid,
                secondAccountUuid))
        .isEqualTo(1);
  }

  @Test
  void distinctConcurrentAssignmentsPersistOneWinnerAndOneReplayableConflict() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<PreseededActorAssignmentResult> first =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Assignment race did not start");
                }
                return assign(
                    assignmentUuid,
                    accountUuid,
                    4L,
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                    "Concurrent Actor",
                    "a");
              });
      Future<PreseededActorAssignmentResult> second =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Assignment race did not start");
                }
                return assign(
                    secondAssignmentUuid,
                    accountUuid,
                    4L,
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                    "Concurrent Actor",
                    "b");
              });

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<PreseededActorAssignmentResult> results =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      PreseededActorAssignmentResult winner =
          results.stream()
              .filter(result -> result.outcome() == PreseededActorAssignmentResult.Outcome.ASSIGNED)
              .findFirst()
              .orElseThrow();
      PreseededActorAssignmentResult loser =
          results.stream()
              .filter(
                  result ->
                      result.outcome()
                          == PreseededActorAssignmentResult.Outcome.IDEMPOTENCY_CONFLICT)
              .findFirst()
              .orElseThrow();

      assertThat(winner.characterUuid()).isNotNull();
      assertThat(loser.characterUuid()).isNull();
      assertThat(
              assign(
                  loser.assignmentUuid(),
                  accountUuid,
                  4L,
                  PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                  "Concurrent Actor",
                  "retry"))
          .isEqualTo(loser);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM entity_preseeded_actor_assignment_operations "
                      + "WHERE assignment_uuid = ? AND status = 'IDEMPOTENCY_CONFLICT' "
                      + "AND character_uuid IS NULL "
                      + "AND completed_at IS NOT NULL",
                  Integer.class,
                  loser.assignmentUuid()))
          .isEqualTo(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM characters WHERE tenant_uuid = ? "
                      + "AND playable_state_namespace_id = ? AND account_uuid = ? "
                      + "AND actor_identity_status = 'OWNER_RESOLVED'",
                  Integer.class,
                  tenantUuid,
                  namespaceUuid,
                  accountUuid))
          .isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void conflictingNamespaceScopeProofRollsBackGuardAndDoesNotPromoteOrAllocateActor() {
    jdbcTemplate.update(
        "INSERT INTO entity_playable_state_namespace_scopes "
            + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) "
            + "VALUES (?, ?, ?)",
        tenantUuid,
        namespaceUuid,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED.name());

    assertThatThrownBy(
            () ->
                assign(
                    assignmentUuid,
                    accountUuid,
                    4L,
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                    "Assigned Actor",
                    "a"))
        .isInstanceOf(RuntimeException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE tenant_uuid = ?", Integer.class, tenantUuid))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_preseeded_actor_assignment_operations "
                    + "WHERE assignment_uuid = ?",
                Integer.class,
                assignmentUuid))
        .isZero();
  }

  @Test
  void assignmentReplayAndOwnerMappingsRejectDeleteAndTruncate() {
    PreseededActorAssignmentResult assigned =
        assign(
            assignmentUuid,
            accountUuid,
            4L,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "Assigned Actor",
            "a");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM entity_preseeded_actor_assignment_operations "
                        + "WHERE assignment_uuid = ?",
                    assignmentUuid))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM entity_tenant_identities WHERE canonical_tenant_uuid = ?",
                    tenantUuid))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                jdbcTemplate.execute(
                    "TRUNCATE TABLE entity_preseeded_actor_assignment_operations CASCADE"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE entity_tenant_identities"))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            assign(
                assignmentUuid,
                accountUuid,
                4L,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                "Assigned Actor",
                "a"))
        .isEqualTo(assigned);
  }

  private PreseededActorAssignmentResult assign(
      UUID operationUuid,
      UUID actorAccountUuid,
      long catalogRevision,
      PlayableStateScope scope,
      String displayName,
      String digestFirstHexDigit) {
    PreseededActorAssignmentOwnerEvidence target =
        evidence(operationUuid, actorAccountUuid, catalogRevision, scope);
    PreseededActorAssignmentRequest request = assignmentRequest(target, displayName);
    return characterRepository.assignPreseededActor(
        request,
        target,
        accountIdentity(operationUuid, actorAccountUuid),
        request.mutationIntentDigest());
  }

  private PreseededActorAssignmentRequest assignmentRequest(
      PreseededActorAssignmentOwnerEvidence target) {
    return assignmentRequest(target, "Assigned Actor");
  }

  private PreseededActorAssignmentRequest assignmentRequest(
      PreseededActorAssignmentOwnerEvidence target, String displayName) {
    return new PreseededActorAssignmentRequest(
        target.assignmentUuid(),
        target.canonicalAccountUuid(),
        new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, displayName),
        new PreseededActorAssignmentExpectedTarget(
            target.canonicalTenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            "game-instance",
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.frozenPolicyDigest(),
            target.playableStateNamespaceId(),
            target.publishedReleaseBundleRef(),
            target.playableStateScope()));
  }

  private static PreseededActorAssignmentOwnerEvidence withPublishedReleaseBundleRef(
      PreseededActorAssignmentOwnerEvidence evidence, String releaseRef) {
    return new PreseededActorAssignmentOwnerEvidence(
        evidence.assignmentUuid(),
        evidence.canonicalAccountUuid(),
        evidence.eligibility(),
        evidence.eligibilityEvaluatedAt(),
        evidence.membershipAuthorityGeneration(),
        evidence.eligibilityEvidenceDigest(),
        evidence.canonicalTenantUuid(),
        evidence.realmUuid(),
        evidence.worldSlug(),
        evidence.realmSlug(),
        "game-instance",
        evidence.catalogRevision(),
        evidence.canonicalVersionUuid(),
        evidence.frozenPolicyDigest(),
        evidence.playableStateNamespaceId(),
        evidence.playableStateScope(),
        evidence.entryPolicy(),
        evidence.accountPurpose(),
        evidence.accountCurrentness(),
        evidence.accountAuthoritySnapshotDigest(),
        evidence.publishedOwnerProofDigest(),
        releaseRef);
  }

  private PreseededActorAssignmentOwnerEvidence evidence(
      UUID operationUuid, UUID actorAccountUuid, long catalogRevision, PlayableStateScope scope) {
    return new PreseededActorAssignmentOwnerEvidence(
        operationUuid,
        actorAccountUuid,
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        Instant.parse("2026-10-04T00:00:00Z"),
        9L,
        "1".repeat(64),
        tenantUuid,
        realmUuid,
        "world",
        "realm",
        "game-instance",
        catalogRevision,
        UUID.fromString("17000000-0000-4000-8000-000000000017"),
        "2".repeat(64),
        namespaceUuid,
        scope,
        PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        "3".repeat(64),
        "4".repeat(64),
        "release-bundle/test");
  }

  private RuntimeAccountIdentityEvidence accountIdentity(UUID requestUuid, UUID actorAccountUuid) {
    return new RuntimeAccountIdentityEvidence(
        1,
        "dev",
        requestUuid,
        actorAccountUuid,
        actorAccountUuid.equals(accountUuid) ? 23L : 24L,
        "ACCOUNT_DATABASE_INSERT");
  }

  private void assertConflictWithoutActorDisclosure(PreseededActorAssignmentResult result) {
    assertThat(result.outcome())
        .isEqualTo(PreseededActorAssignmentResult.Outcome.IDEMPOTENCY_CONFLICT);
    assertThat(result.characterUuid()).isNull();
  }

  private FluentConfiguration migrationFlyway(String schema) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .placeholders(Map.of("serviceSchema", schema));
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    return configuration;
  }

  private Connection connectToSchema(String schema) throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema);
      return connection;
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }
  }
}
