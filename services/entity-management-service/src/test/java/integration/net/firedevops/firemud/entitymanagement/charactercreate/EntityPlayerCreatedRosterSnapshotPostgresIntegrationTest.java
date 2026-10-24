package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterReadService.PublishedIdentity;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture.WireFixture;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical PostgreSQL proof using the real initialized Entity mTLS source client and controlled
 * owner-shaped publication and original-reference fixtures. The source stub and original owner
 * response do not prove production Game Session custody or genuine publication. The created-actor
 * case uses the actual Entity SQL writer with its existing controlled upstream-proof fixture.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class EntityPlayerCreatedRosterSnapshotPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private DataSourceTransactionManager transactionManager;
  private TransactionTemplate transaction;

  @TempDir Path certificateDirectory;

  @BeforeEach
  void migrateCurrentEntitySchema() {
    schema = "entity_created_roster_" + UUID.randomUUID().toString().replace("-", "");
    dataSource = dataSource(schema);
    var configuration = migrationConfiguration(dataSource, schema);
    configuration.load().migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    transactionManager = new DataSourceTransactionManager(dataSource);
    transaction = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void removeOwnedSchema() {
    if (schema != null) {
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
  }

  @Test
  void sealedEmptyCreatedSnapshotRetainsImmutableSourceIdentity() {
    UUID tenant = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    byte[] source = sourceIdentity().toByteArray();
    enroll(tenant, namespace);

    insertBuildingSnapshot(snapshot, tenant, namespace, source);
    dsl.execute(
        "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' WHERE snapshot_uuid = ?",
        snapshot);

    var stored =
        dsl.fetchOne(
            "SELECT entry_policy, playable_state_scope, roster_count, construction_state, canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
            snapshot);
    assertThat(stored.get("entry_policy", String.class)).isEqualTo("PLAYER_CREATED");
    assertThat(stored.get("playable_state_scope", String.class))
        .isEqualTo("PLAYABLE_STATE_SCOPE_SHARED");
    assertThat(stored.get("roster_count", Short.class)).isZero();
    assertThat(stored.get("construction_state", String.class)).isEqualTo("SEALED");
    assertThat(stored.get("canonical_player_created_source_identity", byte[].class))
        .containsExactly(source);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshot_actors")))
        .isZero();

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_gameplay_roster_snapshots SET canonical_player_created_source_identity = ? WHERE snapshot_uuid = ?",
                    sourceIdentity().toBuilder()
                        .setDescriptorRevisionUuid(UUID.randomUUID().toString())
                        .build()
                        .toByteArray(),
                    snapshot))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void createdSnapshotCannotOmitSourceIdentity() {
    UUID tenant = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    enroll(tenant, namespace);

    assertThatThrownBy(() -> insertBuildingSnapshot(UUID.randomUUID(), tenant, namespace, null))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void captureRejectsAnAmbientReadOnlyTransactionBeforeAcceptingAnySourceValue() {
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var capture = new EntityPlayerCreatedRosterSnapshotRepository(dsl, transactionManager);
    var outerReadOnly = new TransactionTemplate(transactionManager);
    outerReadOnly.setReadOnly(true);

    assertThatThrownBy(
            () -> outerReadOnly.execute(ignored -> capture.capture(null, null, null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("short Entity transactions");
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
  }

  @Test
  void actualConfiguredMtlsSourceCapturesAndReplaysAnEmptyRosterAtomically() throws Exception {
    var inputs = authenticatedCaptureInputs();
    UUID admission = reserveAdmission(inputs.request());
    var repository = new EntityPlayerCreatedRosterSnapshotRepository(dsl, transactionManager);

    var first = repository.capture(inputs.request(), inputs.source(), inputs.original(), admission);
    var replay =
        repository.capture(inputs.request(), inputs.source(), inputs.original(), admission);

    assertThat(replay.toByteArray()).containsExactly(first.toByteArray());
    assertThat(first.getRoster().getActorsList()).isEmpty();
    assertThat(first.getRoster().getSnapshotDigest())
        .isEqualTo(
            CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
                inputs.request().getCanonicalAccountUuid(),
                inputs.request().getExpectedTarget(),
                inputs.request().getExpectedSource(),
                first.getRoster().getActorsList()));
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots")))
        .isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshot_actors")))
        .isZero();

    var snapshot =
        dsl.fetchSingle(
            "SELECT entry_policy, roster_count, construction_state, canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
            UUID.fromString(first.getRoster().getSnapshotUuid()));
    assertThat(snapshot.get("entry_policy", String.class)).isEqualTo("PLAYER_CREATED");
    assertThat(snapshot.get("roster_count", Short.class)).isZero();
    assertThat(snapshot.get("construction_state", String.class)).isEqualTo("SEALED");
    assertThat(snapshot.get("canonical_player_created_source_identity", byte[].class))
        .containsExactly(inputs.request().getExpectedSource().toByteArray());

    var retained =
        dsl.fetchSingle(
            "SELECT owner_request, owner_fence, owner_target_fence_uuid, canonical_player_created_roster_receipt FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
            UUID.fromString(inputs.request().getRequestUuid()));
    assertThat(retained.get("owner_request", byte[].class))
        .containsExactly(inputs.original().getRequest().toByteArray());
    assertThat(retained.get("owner_fence", Long.class))
        .isEqualTo(inputs.original().getOwnerFence());
    assertThat(retained.get("owner_target_fence_uuid", UUID.class))
        .isEqualTo(UUID.fromString(inputs.original().getTargetFenceUuid()));
    assertThat(retained.get("canonical_player_created_roster_receipt", byte[].class))
        .containsExactly(first.toByteArray());
    assertThat(
            dsl.fetchSingle(
                    "SELECT snapshot_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ?",
                    UUID.fromString(inputs.request().getRequestUuid()),
                    admission)
                .get(0, UUID.class))
        .isEqualTo(UUID.fromString(first.getRoster().getSnapshotUuid()));

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_gameplay_roster_snapshots SET canonical_player_created_source_identity = ? WHERE snapshot_uuid = ?",
                    sourceIdentity().toByteArray(),
                    UUID.fromString(first.getRoster().getSnapshotUuid())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void actualCreatedActorWriterFeedsAuthenticatedBoundedSnapshotCapture() throws Exception {
    UUID accountId = UUID.randomUUID();
    var inputs = authenticatedCaptureInputs(accountId, null);
    DraftCommitBinding selectedCommit =
        GameDesignPublicationOperationBinding.fromStored(
                inputs.source().publishedPolicySet().operationBytes())
            .account()
            .input()
            .selection()
            .selectedCommit();
    var projection = inputs.source().authored().projections().getFirst();
    assertThat(selectedCommit.target().canonicalTenantId())
        .isEqualTo(inputs.source().target().tenantUuid());
    assertThat(selectedCommit.target().canonicalVersionId())
        .isEqualTo(inputs.source().target().canonicalVersionUuid());
    assertThat(projection.selectedCommitId()).isEqualTo(selectedCommit.commitId());
    assertThat(projection.selectedCommitDigest()).isEqualTo(selectedCommit.digest());
    var entitySource =
        EntityCharacterCreatePostgresIntegrationTest.retainControlledSource(dsl, selectedCommit);
    var proof =
        EntityCharacterCreatePostgresIntegrationTest.proofForRosterCapture(
            entitySource, accountId, UUID.randomUUID(), inputs.source());
    var writer =
        new EntityCharacterCreateRepository(
            dsl, Clock.fixed(Instant.parse("2026-10-24T00:01:00Z"), ZoneOffset.UTC));

    var terminal = writer.settle(proof, null);
    UUID actorId = UUID.fromString(terminal.getCharacterId());
    var published = PublishedIdentity.from(inputs.source());
    var selected =
        writer.readSelectedPlayerCreatedTerminal(
            new AuthenticatedAccountSelector(accountId), published, actorId);
    assertThat(terminal.getOutcome())
        .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED);
    assertThat(selected.toByteArray()).containsExactly(terminal.toByteArray());
    assertThat(terminal.getOriginalRequest().getOriginal().getTargetNamespace())
        .isEqualTo(inputs.source().response().getRequest().getTargetNamespace());
    assertThat(terminal.getAccountOriginal().getMutationDigest())
        .isEqualTo(proof.original().getMutationDigest());

    UUID admission = reserveAdmission(inputs.request());
    var snapshotRepository =
        new EntityPlayerCreatedRosterSnapshotRepository(dsl, transactionManager);
    var snapshot =
        snapshotRepository.capture(inputs.request(), inputs.source(), inputs.original(), admission);

    var selectedOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                inputs.original().getRequest(),
                inputs.original().getOwnerFence(),
                UUID.fromString(inputs.original().getTargetFenceUuid()),
                snapshot)
            .toBuilder()
            .setSelectedCharacterUuid(actorId.toString())
            .setSelectionInput(
                CanonicalGameplayRosterSelectionInput.newBuilder()
                    .setSchemaVersion(1)
                    .setSelector("controlled-test-selection"))
            .build();
    var selectedRequest =
        CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(
            CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
                .setRequestUuid(inputs.request().getRequestUuid())
                .setCanonicalAccountUuid(inputs.request().getCanonicalAccountUuid())
                .setSelectedCharacterUuid(actorId.toString())
                .setExpectedTarget(inputs.request().getExpectedTarget())
                .setExpectedSnapshot(
                    net.firedevops.firemud.entitymanagement.v1
                        .CanonicalGameplayRosterSnapshotReference.newBuilder()
                        .setSnapshotUuid(snapshot.getRoster().getSnapshotUuid())
                        .setSnapshotDigest(snapshot.getRoster().getSnapshotDigest()))
                .setExpectedSource(inputs.request().getExpectedSource())
                .setPlayerExecutionContext(
                    inputs.request().getPlayerExecutionContext().toBuilder()
                        .setCharacterId(actorId.toString()))
                .build());
    var beforeSelectedRead =
        dsl.fetchSingle(
            "SELECT (SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots) AS snapshots, (SELECT count(*) FROM entity_canonical_gameplay_roster_snapshot_actors) AS actors, (SELECT count(*) FROM entity_canonical_roster_original_selections) AS originals");
    var selectedReceipt =
        snapshotRepository.readSelected(selectedRequest, inputs.source(), selectedOriginal);
    assertThat(selectedReceipt.getTerminal().toByteArray()).containsExactly(terminal.toByteArray());
    assertThat(selectedReceipt.getCreationOperationUuid())
        .isEqualTo(proof.original().getOperationId());
    assertThat(selectedReceipt.getMutationDigest()).isEqualTo(proof.original().getMutationDigest());
    var afterSelectedRead =
        dsl.fetchSingle(
            "SELECT (SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots) AS snapshots, (SELECT count(*) FROM entity_canonical_gameplay_roster_snapshot_actors) AS actors, (SELECT count(*) FROM entity_canonical_roster_original_selections) AS originals");
    assertThat(afterSelectedRead.get("snapshots", Long.class))
        .isEqualTo(beforeSelectedRead.get("snapshots", Long.class));
    assertThat(afterSelectedRead.get("actors", Long.class))
        .isEqualTo(beforeSelectedRead.get("actors", Long.class));
    assertThat(afterSelectedRead.get("originals", Long.class))
        .isEqualTo(beforeSelectedRead.get("originals", Long.class));

    UUID wrongActorId = UUID.randomUUID();
    var wrongActor =
        selectedRequest.toBuilder()
            .setSelectedCharacterUuid(wrongActorId.toString())
            .setPlayerExecutionContext(
                selectedRequest.getPlayerExecutionContext().toBuilder()
                    .setCharacterId(wrongActorId.toString()))
            .build();
    assertThatThrownBy(
            () -> snapshotRepository.readSelected(wrongActor, inputs.source(), selectedOriginal))
        .isInstanceOf(IllegalStateException.class);
    var wrongSnapshot =
        selectedRequest.toBuilder()
            .setExpectedSnapshot(
                selectedRequest.getExpectedSnapshot().toBuilder().setSnapshotDigest("f".repeat(64)))
            .build();
    assertThatThrownBy(
            () -> snapshotRepository.readSelected(wrongSnapshot, inputs.source(), selectedOriginal))
        .isInstanceOf(IllegalStateException.class);
    var substitutedSource =
        selectedRequest.toBuilder()
            .setExpectedSource(
                inputs.request().getExpectedSource().toBuilder()
                    .setDescriptorUuid(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(
            () ->
                snapshotRepository.readSelected(
                    substitutedSource, inputs.source(), selectedOriginal))
        .isInstanceOf(IllegalArgumentException.class);
    UUID otherAccount = UUID.randomUUID();
    var substitutedAccount =
        selectedRequest.toBuilder()
            .setCanonicalAccountUuid(otherAccount.toString())
            .setPlayerExecutionContext(
                selectedRequest.getPlayerExecutionContext().toBuilder()
                    .setAccountId(otherAccount.toString()))
            .build();
    assertThatThrownBy(
            () ->
                snapshotRepository.readSelected(
                    substitutedAccount, inputs.source(), selectedOriginal))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongNamespaceRequest =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(inputs.request().getRequestUuid()),
            UUID.fromString(inputs.request().getCanonicalAccountUuid()),
            UUID.fromString(inputs.request().getPlayerExecutionContext().getSessionId()),
            "other",
            inputs.request().getExpectedTarget(),
            inputs.request().getExpectedSource());
    var wrongNamespaceOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            wrongNamespaceRequest,
            selectedOriginal.getOwnerFence(),
            UUID.fromString(selectedOriginal.getTargetFenceUuid()),
            snapshot);
    assertThatThrownBy(
            () ->
                snapshotRepository.readSelected(
                    selectedRequest, inputs.source(), wrongNamespaceOriginal))
        .isInstanceOf(IllegalArgumentException.class);

    UUID recoveryId = UUID.randomUUID();
    var recoveryRequest =
        CanonicalGameplayRosterRecoveryRequest.newBuilder()
            .setSchemaVersion(1)
            .setRecoveryUuid(recoveryId.toString())
            .setOriginalSelection(selectedOriginal.getRequest())
            .build();
    var recoveryOwner = selectedOriginal.toBuilder().setRecoveryUuid(recoveryId.toString()).build();
    var retentionRepository = new CanonicalGameplayRosterRetentionRepository(dsl);
    transaction.executeWithoutResult(
        ignored ->
            retentionRepository.closeAdmission(
                recoveryRequest,
                recoveryOwner,
                new CanonicalGameplayRosterRetentionPolicy(
                    Duration.ofHours(1),
                    Duration.ofHours(1),
                    Duration.ofHours(1),
                    Duration.ofMinutes(1),
                    2)));
    assertThatThrownBy(
            () ->
                snapshotRepository.readSelected(selectedRequest, inputs.source(), selectedOriginal))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");

    UUID missingOperation = UUID.randomUUID();
    UUID missingSession = UUID.randomUUID();
    var missingRequest =
        selectedRequest.toBuilder()
            .setRequestUuid(missingOperation.toString())
            .setPlayerExecutionContext(
                selectedRequest.getPlayerExecutionContext().toBuilder()
                    .setRequestId(missingOperation.toString())
                    .setSessionId(missingSession.toString()))
            .build();
    var missingOwnerRequest =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            missingOperation,
            UUID.fromString(inputs.request().getCanonicalAccountUuid()),
            missingSession,
            inputs.source().response().getRequest().getTargetNamespace(),
            inputs.request().getExpectedTarget(),
            inputs.request().getExpectedSource());
    var missingOwner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                missingOwnerRequest,
                selectedOriginal.getOwnerFence(),
                UUID.fromString(selectedOriginal.getTargetFenceUuid()),
                snapshot)
            .toBuilder()
            .setSelectedCharacterUuid(actorId.toString())
            .setSelectionInput(
                CanonicalGameplayRosterSelectionInput.newBuilder()
                    .setSchemaVersion(1)
                    .setSelector("controlled-test-selection"))
            .build();
    assertThatThrownBy(
            () -> snapshotRepository.readSelected(missingRequest, inputs.source(), missingOwner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");

    assertThat(snapshot.getRoster().getActorsList())
        .containsExactly(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                .setCharacterUuid(actorId.toString())
                .setDisplayName("Wayfarer")
                .setActorKind(
                    net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                        .CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .build());
    assertThat(snapshot.getRoster().getSnapshotDigest())
        .isEqualTo(
            CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
                inputs.request().getCanonicalAccountUuid(),
                inputs.request().getExpectedTarget(),
                inputs.request().getExpectedSource(),
                snapshot.getRoster().getActorsList()));
    var persistedActor =
        dsl.fetchSingle(
            "SELECT character_uuid, actor_entry_policy, creation_descriptor_json, creation_descriptor_digest, authored_initial_core_json FROM characters WHERE character_uuid = ?",
            actorId);
    assertThat(persistedActor.get("actor_entry_policy", String.class)).isEqualTo("PLAYER_CREATED");
    assertThat(persistedActor.get("creation_descriptor_json", String.class))
        .isEqualTo(inputs.source().creationDescriptor().canonicalJson());
    assertThat(persistedActor.get("creation_descriptor_digest", String.class))
        .isEqualTo(inputs.source().creationDescriptor().digest());
    assertThat(persistedActor.get("authored_initial_core_json", String.class))
        .isEqualTo(inputs.source().authored().projections().getFirst().core().canonicalJson());
    assertThat(proof.action().mutation().authoredSource().bindingId())
        .isEqualTo(inputs.source().authored().projections().getFirst().bindingId().toString());
    assertThat(proof.action().mutation().authoredSource().bindingDigest())
        .isEqualTo(inputs.source().authored().projections().getFirst().bindingDigest());
    assertThat(
            dsl.fetchSingle(
                    "SELECT canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                    UUID.fromString(snapshot.getRoster().getSnapshotUuid()))
                .get("canonical_player_created_source_identity", byte[].class))
        .containsExactly(inputs.request().getExpectedSource().toByteArray());
    assertThat(
            dsl.fetchSingle(
                    "SELECT terminal_bytes FROM entity_character_create_operations WHERE operation_id = ?",
                    UUID.fromString(proof.original().getOperationId()))
                .get("terminal_bytes", byte[].class))
        .containsExactly(terminal.toByteArray());
  }

  @Test
  void substitutedSourceAccountSessionAndOriginalNamespaceRefuseBeforeSnapshotWrites()
      throws Exception {
    var inputs = authenticatedCaptureInputs();
    UUID admission = reserveAdmission(inputs.request());
    var repository = new EntityPlayerCreatedRosterSnapshotRepository(dsl, transactionManager);

    var changedSource =
        inputs.request().getExpectedSource().toBuilder()
            .setDescriptorUuid(UUID.randomUUID().toString())
            .build();
    var sourceSubstitution =
        request(
            inputs.source(),
            UUID.fromString(inputs.request().getRequestUuid()),
            UUID.fromString(inputs.request().getCanonicalAccountUuid()),
            UUID.fromString(inputs.request().getPlayerExecutionContext().getSessionId()),
            changedSource);
    assertThatThrownBy(
            () ->
                repository.capture(
                    sourceSubstitution, inputs.source(), inputs.original(), admission))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source identity changed");

    UUID otherAccount = UUID.randomUUID();
    var accountSubstitution =
        request(
            inputs.source(),
            UUID.fromString(inputs.request().getRequestUuid()),
            otherAccount,
            UUID.fromString(inputs.request().getPlayerExecutionContext().getSessionId()),
            inputs.request().getExpectedSource());
    assertThatThrownBy(
            () ->
                repository.capture(
                    accountSubstitution, inputs.source(), inputs.original(), admission))
        .isInstanceOf(IllegalArgumentException.class);

    UUID otherSession = UUID.randomUUID();
    var sessionSubstitution =
        request(
            inputs.source(),
            UUID.fromString(inputs.request().getRequestUuid()),
            UUID.fromString(inputs.request().getCanonicalAccountUuid()),
            otherSession,
            inputs.request().getExpectedSource());
    assertThatThrownBy(
            () ->
                repository.capture(
                    sessionSubstitution, inputs.source(), inputs.original(), admission))
        .isInstanceOf(IllegalArgumentException.class);

    var wrongNamespaceRequest =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(inputs.request().getRequestUuid()),
            UUID.fromString(inputs.request().getCanonicalAccountUuid()),
            UUID.fromString(inputs.request().getPlayerExecutionContext().getSessionId()),
            "other",
            inputs.request().getExpectedTarget(),
            inputs.request().getExpectedSource());
    var wrongNamespaceOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            wrongNamespaceRequest,
            inputs.original().getOwnerFence(),
            UUID.fromString(inputs.original().getTargetFenceUuid()),
            null);
    assertThatThrownBy(
            () ->
                repository.capture(
                    inputs.request(), inputs.source(), wrongNamespaceOriginal, admission))
        .isInstanceOf(IllegalArgumentException.class);

    var isolatedTarget =
        inputs.request().getExpectedTarget().toBuilder()
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED)
            .build();
    var isolatedRequest =
        inputs.request().toBuilder()
            .setExpectedTarget(isolatedTarget)
            .setPlayerExecutionContext(
                inputs.request().getPlayerExecutionContext().toBuilder()
                    .setPlayableStateScope("ISOLATED"))
            .build();
    assertThatThrownBy(
            () ->
                repository.capture(isolatedRequest, inputs.source(), inputs.original(), admission))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections"))).isZero();
    var localAdmission =
        dsl.fetchSingle(
            "SELECT snapshot_uuid, admission_state FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
            UUID.fromString(inputs.request().getRequestUuid()));
    assertThat(localAdmission.get("snapshot_uuid", UUID.class)).isNull();
    assertThat(localAdmission.get("admission_state", String.class)).isEqualTo("OPEN");
  }

  @Test
  void failedOriginalBindRollsBackTheSnapshotAndAdmissionBinding() throws Exception {
    var inputs = authenticatedCaptureInputs();
    UUID admission = reserveAdmission(inputs.request());
    dsl.execute(
        "CREATE FUNCTION fail_player_created_original_bind() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled original bind failure'; END $$");
    dsl.execute(
        "CREATE TRIGGER fail_player_created_original_bind BEFORE INSERT ON entity_canonical_roster_original_selections FOR EACH ROW EXECUTE FUNCTION fail_player_created_original_bind()");
    var repository = new EntityPlayerCreatedRosterSnapshotRepository(dsl, transactionManager);

    assertThatThrownBy(
            () ->
                repository.capture(inputs.request(), inputs.source(), inputs.original(), admission))
        .isInstanceOf(DataAccessException.class);

    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshot_actors")))
        .isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retention_lifecycle"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections"))).isZero();
    var localAdmission =
        dsl.fetchSingle(
            "SELECT snapshot_uuid, admission_state FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
            UUID.fromString(inputs.request().getRequestUuid()));
    assertThat(localAdmission.get("snapshot_uuid", UUID.class)).isNull();
    assertThat(localAdmission.get("admission_state", String.class)).isEqualTo("OPEN");
  }

  private void enroll(UUID tenant, UUID namespace) {
    dsl.execute("INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?)", tenant);
    dsl.execute(
        "INSERT INTO entity_playable_state_namespace_scopes (tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, 'PLAYABLE_STATE_SCOPE_SHARED')",
        tenant,
        namespace);
  }

  private void insertBuildingSnapshot(UUID snapshot, UUID tenant, UUID namespace, byte[] source) {
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots (snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, admission_pointer_snapshot_digest, published_owner_proof_digest, playable_state_namespace_uuid, playable_state_scope, entry_policy, publication_evidence_format, canonical_player_created_source_identity, roster_count, construction_state) VALUES (?, ?, ?, ?, ?, 'world', 'realm', ?, 3, ?, 5, 7, ?, 'published-release', ?, ?, ?, 'PLAYABLE_STATE_SCOPE_SHARED', 'PLAYER_CREATED', 'CANONICAL_UUID_V2', ?, 0, 'BUILDING')",
        snapshot,
        UUID.randomUUID().toString().replace("-", "").repeat(2),
        UUID.randomUUID(),
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        namespace,
        source);
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity() {
    return CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
        .setSchemaVersion(1)
        .setDescriptorUuid(UUID.randomUUID().toString())
        .setDescriptorRevisionUuid(UUID.randomUUID().toString())
        .setDescriptorDigest("sha256:" + "1".repeat(64))
        .setAuthoredBindingUuid(UUID.randomUUID().toString())
        .setAuthoredBindingDigest("sha256:" + "2".repeat(64))
        .build();
  }

  private CaptureInputs authenticatedCaptureInputs() throws Exception {
    return authenticatedCaptureInputs(UUID.randomUUID(), null);
  }

  private CaptureInputs authenticatedCaptureInputs(UUID account, UUID selectedCommitId)
      throws Exception {
    var controlled = new ControlledPlayerCreatedRosterSourceFixture(certificateDirectory);
    WireFixture wire =
        selectedCommitId == null
            ? controlled.wireFixture()
            : controlled.wireFixture("canonical-instance-launch-descriptor", selectedCommitId);
    try (var owner = controlled.owner("server");
        var client = controlled.client(owner)) {
      owner.respondWith(wire.response());
      client.init();
      var source = client.read(wire.request());
      assertThat(owner.receivedRequests()).containsExactly(wire.request());
      assertThat(owner.observedPeerUri())
          .isEqualTo("spiffe://firemud/ns/test/sa/entity-management-service");
      return inputs(source, account);
    }
  }

  private static CaptureInputs inputs(
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source, UUID account) {
    UUID operation = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    var sourceIdentity = sourceIdentity(source);
    var request = request(source, operation, account, session, sourceIdentity);
    var originalRequest =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            operation,
            account,
            session,
            source.response().getRequest().getTargetNamespace(),
            source.target().toWire(),
            sourceIdentity);
    var original =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            originalRequest, 1L, UUID.randomUUID(), null);
    return new CaptureInputs(request, source, original);
  }

  private static CanonicalPlayerCreatedRosterRequest request(
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source,
      UUID operation,
      UUID account,
      UUID session,
      CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity) {
    var target = source.target().toWire();
    return CanonicalPlayerCreatedRosterEvidence.requireRequest(
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(operation.toString())
            .setCanonicalAccountUuid(account.toString())
            .setExpectedTarget(target)
            .setExpectedSource(sourceIdentity)
            .setPlayerExecutionContext(
                PlayerExecutionContext.newBuilder()
                    .setRequestId(operation.toString())
                    .setAccountId(account.toString())
                    .setSessionId(session.toString())
                    .setTenantId(target.getTenantUuid())
                    .setRealmId(target.getRealmUuid())
                    .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
                    .setGameInstanceId(target.getGameInstanceUuid())
                    .setPlayableStateScope("SHARED"))
            .build());
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity(
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source) {
    var descriptor = source.creationDescriptor();
    var projection = source.authored().projections().getFirst();
    return CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
        CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
            .setSchemaVersion(1)
            .setDescriptorUuid(descriptor.descriptorId())
            .setDescriptorRevisionUuid(descriptor.descriptorRevisionId())
            .setDescriptorDigest(descriptor.digest())
            .setAuthoredBindingUuid(projection.bindingId().toString())
            .setAuthoredBindingDigest(projection.bindingDigest())
            .build());
  }

  private UUID reserveAdmission(CanonicalPlayerCreatedRosterRequest request) {
    var retention = new CanonicalGameplayRosterRetentionRepository(dsl);
    return transaction.execute(ignored -> retention.reserveAdmission(request));
  }

  private record CaptureInputs(
      CanonicalPlayerCreatedRosterRequest request,
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source,
      GetCanonicalGameplayRosterReferenceResponse original) {}

  private static DriverManagerDataSource dataSource(String schema) {
    String url = postgres.getJdbcUrl();
    if (schema != null) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration migrationConfiguration(
      DriverManagerDataSource dataSource, String schema) {
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
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    return configuration;
  }
}
