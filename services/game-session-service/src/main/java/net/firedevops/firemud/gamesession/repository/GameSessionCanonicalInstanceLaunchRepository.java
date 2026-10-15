package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.FixtureCanonicalRealmCatalog;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fixture allocation on the canonical Game Session launch owner; Human owners remain separate. */
public final class GameSessionCanonicalInstanceLaunchRepository {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public GameSessionCanonicalInstanceLaunchRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      GameSessionCanonicalRealmCatalogRepository catalogRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is missing or invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setReadOnly(true);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Fixture-only typed input. Raw wire records and unchecked projections cannot call this owner.
   */
  public FixtureCanonicalInstanceLaunch createVerifiedFixtureStartingLaunch(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    requireNoActiveOwnerTransaction();
    evidence.requireVerifiedFixture();
    if (!workloadNamespace.equals(evidence.sourceEvidence().targetNamespace())) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Fixture source differs from configured launch owner");
    }
    AuthoredWorldLaunchDescriptorEvidence descriptor = fixtureDescriptor(evidence);
    FixtureCanonicalRealmCatalog catalog =
        catalogRepository.establishVerifiedFixtureRealm(evidence);
    FixtureCanonicalInstanceLaunch committed =
        writeTransaction.execute(
            status -> {
              requireOwnerTransaction();
              dsl.execute(
                  "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                  "canonical-launch:"
                      + workloadNamespace
                      + ":"
                      + descriptor.controlPlaneRequestId());
              FixtureCanonicalRealmCatalog actual =
                  catalogRepository.readVerifiedFixtureRealmInOwnerTransaction(evidence);
              if (!catalog.equals(actual)) {
                throw new InvalidCanonicalInstanceLaunchException(
                    "Fixture catalog changed before STARTING");
              }
              Record existing =
                  readLaunchRowByOperationInTransaction(descriptor.controlPlaneRequestId(), true);
              if (existing != null) return readFixtureLaunch(existing, evidence, actual, false);
              if (dsl.fetchOne(
                      "SELECT id FROM game_instances WHERE tenant_id = ? AND run_owned_start_request_id = ? FOR UPDATE",
                      actual.association().gameSessionTenantId(),
                      descriptor.controlPlaneRequestId())
                  != null) {
                throw new InvalidCanonicalInstanceLaunchException(
                    "Runtime identity has no exact immutable fixture association");
              }
              UUID instanceUuid = newNonNilUuid();
              Record runtime =
                  dsl.fetchOne(
                      "INSERT INTO game_instances (tenant_id, runtime_version, owner_account_id, status, row_version,"
                          + " game_instance_uuid, game_template_id, launch_descriptor_id, version_id, release_bundle_id,"
                          + " generation_config_revision, version_state_epoch, run_owned_start_request_id,"
                          + " run_owned_start_request_digest, run_owned_start_published_release_bundle_ref)"
                          + " VALUES (?, ?, ?, 'STARTING', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                      actual.association().gameSessionTenantId(),
                      Long.toString(descriptor.versionId()),
                      evidence.fixtureCapability().ownerAccountId(),
                      instanceUuid,
                      descriptor.gameTemplateId(),
                      descriptor.launchDescriptorId(),
                      descriptor.versionId(),
                      descriptor.releaseBundleId(),
                      descriptor.generationConfigRevision(),
                      descriptor.versionStateEpoch(),
                      descriptor.controlPlaneRequestId(),
                      evidence.fixtureRuntimeRequestDigest(),
                      descriptor.publishedReleaseBundleRef());
              long instanceId = required(runtime, "id", Long.class);
              insertFixtureAssociation(evidence, descriptor, actual, instanceId, instanceUuid);
              return readFixtureLaunch(
                  readLaunchRowByOperationInTransaction(descriptor.controlPlaneRequestId(), false),
                  evidence,
                  actual,
                  true);
            });
    Objects.requireNonNull(committed, "Fixture launch transaction returned no result");
    FixtureCanonicalInstanceLaunch independent = readVerifiedFixtureLaunch(evidence);
    if (independent.gameInstanceId() != committed.gameInstanceId()
        || !independent.gameInstanceUuid().equals(committed.gameInstanceUuid())) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Fixture launch independent readback changed allocation");
    }
    return independent;
  }

  public FixtureCanonicalInstanceLaunch readVerifiedFixtureLaunch(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    requireNoActiveOwnerTransaction();
    evidence.requireVerifiedFixture();
    return Objects.requireNonNull(
        readTransaction.execute(
            status ->
                readFixtureLaunch(
                    readLaunchRowByOperationInTransaction(
                        evidence.fixtureCapability().operationId(), false),
                    evidence,
                    catalogRepository.readVerifiedFixtureRealmInOwnerTransaction(evidence),
                    false)),
        "Fixture launch independent read returned no result");
  }

  /**
   * Locks and revalidates the exact fixture catalog, launch association, and live runtime row in
   * the caller's canonical initial-admission owner transaction.
   *
   * <p>The public result record is not authority. Only the immutable verified receipt and this
   * owner-transaction read can produce the target used by a fixture intent or terminal write.
   */
  public FixtureCanonicalInstanceLaunch lockVerifiedFixtureLaunchInOwnerTransaction(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    requireOwnerTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fixture initial-admission source lock requires a writable owner transaction");
    }
    Objects.requireNonNull(evidence, "evidence").requireVerifiedFixture();
    var descriptor = fixtureDescriptor(evidence);
    if (!workloadNamespace.equals(evidence.sourceEvidence().targetNamespace())) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Fixture source differs from the configured launch owner");
    }

    Record lockedCatalog =
        dsl.fetchOne(
            "SELECT realm_id FROM game_session_canonical_realm_catalog "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? FOR UPDATE",
            workloadNamespace,
            evidence.sourceEvidence().canonicalTenantId(),
            catalogRepository
                .readVerifiedFixtureRealmInOwnerTransaction(evidence)
                .snapshot()
                .realmId());
    if (lockedCatalog == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Exact fixture catalog row is unavailable for initial-admission locking");
    }

    Record launch = readLaunchRowByOperationInTransaction(descriptor.controlPlaneRequestId(), true);
    if (launch == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Exact fixture launch association is unavailable for initial-admission locking");
    }
    long tenantId = required(launch, "game_session_tenant_id", Long.class);
    long instanceId = required(launch, "game_instance_id", Long.class);
    UUID instanceUuid = required(launch, "game_instance_uuid", UUID.class);
    Record runtimeLock =
        dsl.fetchOne(
            "SELECT id FROM game_instances "
                + "WHERE tenant_id = ? AND id = ? AND game_instance_uuid = ? FOR UPDATE",
            tenantId,
            instanceId,
            instanceUuid);
    if (runtimeLock == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Exact fixture runtime allocation is unavailable for initial-admission locking");
    }

    Record current =
        readLaunchRowByOperationInTransaction(descriptor.controlPlaneRequestId(), false);
    FixtureCanonicalRealmCatalog catalog =
        catalogRepository.readVerifiedFixtureRealmInOwnerTransaction(evidence);
    return readFixtureLaunch(current, evidence, catalog, false);
  }

  private static AuthoredWorldLaunchDescriptorEvidence fixtureDescriptor(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    evidence.requireVerifiedFixture();
    var source = evidence.sourceEvidence();
    var cap = evidence.fixtureCapability();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            source.targetNamespace(),
            cap.operationId(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            cap.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
        request, evidence.launchDescriptor());
  }

  private String fixtureBindingJson(RunOwnedInitialAdmissionLaunchEvidence evidence) {
    try {
      return JSON.writeValueAsString(
          Map.of(
              "descriptor",
              fixtureDescriptor(evidence),
              "fixturePolicySeal",
              evidence.fixturePolicySeal()));
    } catch (JsonProcessingException invalid) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Fixture binding cannot be retained", invalid);
    }
  }

  private void insertFixtureAssociation(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      FixtureCanonicalRealmCatalog catalog,
      long instanceId,
      UUID instanceUuid) {
    var association = catalog.association();
    var fresh = association.sourceEvidence();
    var realm = catalog.snapshot();
    var seal = evidence.fixturePolicySeal();
    requireOne(
        dsl.execute(
            "INSERT INTO game_session_canonical_instance_launch"
                + " (target_namespace, control_plane_request_id, game_session_tenant_id, canonical_tenant_id,"
                + " tenant_association_operation_id, tenant_association_request_id, tenant_source_schema_version,"
                + " tenant_source_target_namespace, tenant_source_request_id, tenant_source_canonical_tenant_id,"
                + " tenant_source_game_row_id, tenant_source_game_tenant_key, tenant_source_provenance_kind,"
                + " tenant_association_kind, game_instance_id, game_instance_uuid, canonical_realm_id, world_slug,"
                + " playable_state_namespace_id, playable_state_scope, public_production, captured_starting_row_version,"
                + " game_template_id, launch_descriptor_id, version_id, release_bundle_id, generation_config_revision,"
                + " version_state_epoch, complete_launch_binding_evidence, catalog_creation_request_id, catalog_revision,"
                + " catalog_source_intake_operation_id, catalog_source_intake_request_id, fixture_policy_request_bytes,"
                + " fixture_policy_seal_operation_id, fixture_policy_seal_request_digest, fixture_policy_seal_evidence_digest)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND', ?, ?, ?, ?, ?, 'SHARED', TRUE,"
                + " 0, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)",
            workloadNamespace,
            descriptor.controlPlaneRequestId(),
            association.gameSessionTenantId(),
            fresh.canonicalTenantId(),
            association.associationOperationId(),
            fresh.creationRequestId(),
            fresh.schemaVersion(),
            fresh.targetNamespace(),
            fresh.creationRequestId(),
            fresh.canonicalTenantId(),
            fresh.sourceGameRowId(),
            fresh.sourceGameTenantKey(),
            fresh.provenanceKind(),
            instanceId,
            instanceUuid,
            realm.realmId(),
            realm.worldSlug(),
            realm.playableStateNamespaceId(),
            descriptor.gameTemplateId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            descriptor.releaseBundleId(),
            descriptor.generationConfigRevision(),
            descriptor.versionStateEpoch(),
            fixtureBindingJson(evidence),
            realm.creationRequestId(),
            realm.catalogRevision(),
            realm.sourceIntakeReceipt().operationId(),
            realm.sourceIntakeReceipt().intakeRequestId(),
            evidence.fixturePolicyRequestBytes(),
            seal.operationId(),
            seal.requestDigest(),
            seal.evidenceDigest()),
        "fixture canonical launch association");
  }

  private FixtureCanonicalInstanceLaunch readFixtureLaunch(
      Record row,
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      FixtureCanonicalRealmCatalog catalog,
      boolean starting) {
    evidence.requireVerifiedFixture();
    var descriptor = fixtureDescriptor(evidence);
    var realm = catalog.snapshot();
    var association = catalog.association();
    var fresh = evidence.creationEvidence();
    var seal = evidence.fixturePolicySeal();
    if (row == null
        || row.get("original_start_session_tuple", byte[].class) != null
        || !Arrays.equals(
            evidence.fixturePolicyRequestBytes(),
            row.get("fixture_policy_request_bytes", byte[].class))
        || !seal.operationId().equals(row.get("fixture_policy_seal_operation_id", UUID.class))
        || !seal.requestDigest().equals(row.get("fixture_policy_seal_request_digest", String.class))
        || !seal.evidenceDigest()
            .equals(row.get("fixture_policy_seal_evidence_digest", String.class))
        || !workloadNamespace.equals(row.get("target_namespace", String.class))
        || !descriptor
            .controlPlaneRequestId()
            .equals(row.get("control_plane_request_id", String.class))
        || association.gameSessionTenantId() != required(row, "game_session_tenant_id", Long.class)
        || !fresh.canonicalTenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || !association
            .associationOperationId()
            .equals(row.get("tenant_association_operation_id", UUID.class))
        || !fresh.creationRequestId().equals(row.get("tenant_association_request_id", UUID.class))
        || fresh.schemaVersion() != required(row, "tenant_source_schema_version", Integer.class)
        || !fresh.targetNamespace().equals(row.get("tenant_source_target_namespace", String.class))
        || !fresh.creationRequestId().equals(row.get("tenant_source_request_id", UUID.class))
        || !fresh
            .canonicalTenantId()
            .equals(row.get("tenant_source_canonical_tenant_id", UUID.class))
        || fresh.sourceGameRowId() != required(row, "tenant_source_game_row_id", Long.class)
        || !fresh
            .sourceGameTenantKey()
            .equals(row.get("tenant_source_game_tenant_key", String.class))
        || !fresh.provenanceKind().equals(row.get("tenant_source_provenance_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("tenant_association_kind", String.class))
        || !realm.realmId().equals(row.get("canonical_realm_id", UUID.class))
        || !realm
            .playableStateNamespaceId()
            .equals(row.get("playable_state_namespace_id", UUID.class))
        || !realm.worldSlug().equals(row.get("world_slug", String.class))
        || !"SHARED".equals(row.get("playable_state_scope", String.class))
        || !Boolean.TRUE.equals(row.get("public_production", Boolean.class))
        || !realm.creationRequestId().equals(row.get("catalog_creation_request_id", UUID.class))
        || realm.catalogRevision() != required(row, "catalog_revision", Long.class)
        || !realm
            .sourceIntakeReceipt()
            .operationId()
            .equals(row.get("catalog_source_intake_operation_id", UUID.class))
        || !realm
            .sourceIntakeReceipt()
            .intakeRequestId()
            .equals(row.get("catalog_source_intake_request_id", UUID.class))
        || !sameJson(
            fixtureBindingJson(evidence),
            required(row, "gs_complete_launch_binding_json", String.class))
        || !evidence
            .fixtureRuntimeRequestDigest()
            .equals(row.get("gs_runtime_request_digest", String.class))
        || descriptor.gameTemplateId() != required(row, "game_template_id", Long.class)
        || !descriptor.launchDescriptorId().equals(row.get("launch_descriptor_id", String.class))
        || descriptor.versionId() != required(row, "version_id", Long.class)
        || descriptor.releaseBundleId() != required(row, "release_bundle_id", Long.class)
        || !descriptor
            .generationConfigRevision()
            .equals(row.get("generation_config_revision", String.class))
        || descriptor.versionStateEpoch() != required(row, "version_state_epoch", Long.class)) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Retained launch is not this exact verified fixture provenance");
    }
    UUID instanceUuid = required(row, "game_instance_uuid", UUID.class);
    long captured = required(row, "captured_starting_row_version", Long.class);
    long currentVersion = required(row, "gs_current_row_version", Long.class);
    var status = parseStatus(required(row, "gs_current_status", String.class));
    if (!instanceUuid.equals(row.get("gs_runtime_instance_uuid", UUID.class))
        || association.gameSessionTenantId() != required(row, "gs_runtime_tenant_id", Long.class)
        || evidence.fixtureCapability().ownerAccountId()
            != required(row, "gs_runtime_owner_account_id", Long.class)
        || descriptor.gameTemplateId() != required(row, "gs_runtime_game_template_id", Long.class)
        || !descriptor
            .launchDescriptorId()
            .equals(row.get("gs_runtime_launch_descriptor_id", String.class))
        || descriptor.versionId() != required(row, "gs_runtime_version_id", Long.class)
        || descriptor.releaseBundleId() != required(row, "gs_runtime_release_bundle_id", Long.class)
        || !descriptor
            .generationConfigRevision()
            .equals(row.get("gs_runtime_generation_config_revision", String.class))
        || descriptor.versionStateEpoch()
            != required(row, "gs_runtime_version_state_epoch", Long.class)
        || !descriptor
            .controlPlaneRequestId()
            .equals(row.get("gs_runtime_request_id", String.class))
        || !descriptor
            .publishedReleaseBundleRef()
            .equals(row.get("gs_runtime_release_ref", String.class))
        || currentVersion < captured
        || captured != 0L
        || (starting
            && (currentVersion != captured
                || status
                    != CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                        .STARTING))) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Fixture association differs from actual runtime owner row");
    }
    return new FixtureCanonicalInstanceLaunch(
        required(row, "game_instance_id", Long.class),
        instanceUuid,
        catalog,
        status,
        currentVersion);
  }

  private static boolean sameJson(String left, String right) {
    try {
      return JSON.readTree(left).equals(JSON.readTree(right));
    } catch (JsonProcessingException invalid) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored fixture JSON is malformed", invalid);
    }
  }

  public record FixtureCanonicalInstanceLaunch(
      long gameInstanceId,
      UUID gameInstanceUuid,
      FixtureCanonicalRealmCatalog catalog,
      CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus status,
      long rowVersion) {}

  private Record readLaunchRowByOperationInTransaction(
      String controlPlaneRequestId, boolean forUpdate) {
    requireOwnerTransaction();
    String suffix = forUpdate ? " FOR UPDATE OF launch" : "";
    return dsl.fetchOne(
        "SELECT launch.*, runtime.status AS gs_current_status,"
            + " runtime.row_version AS gs_current_row_version,"
            + " runtime.game_instance_uuid AS gs_runtime_instance_uuid,"
            + " runtime.tenant_id AS gs_runtime_tenant_id,"
            + " runtime.runtime_version AS gs_runtime_version,"
            + " runtime.owner_account_uuid AS gs_runtime_owner_account_uuid,"
            + " runtime.owner_account_id AS gs_runtime_owner_account_id,"
            + " runtime.game_template_id AS gs_runtime_game_template_id,"
            + " runtime.launch_descriptor_id AS gs_runtime_launch_descriptor_id,"
            + " runtime.version_id AS gs_runtime_version_id,"
            + " runtime.release_bundle_id AS gs_runtime_release_bundle_id,"
            + " runtime.generation_config_revision AS gs_runtime_generation_config_revision,"
            + " runtime.version_state_epoch AS gs_runtime_version_state_epoch,"
            + " runtime.run_owned_start_request_id AS gs_runtime_request_id,"
            + " runtime.run_owned_start_request_digest AS gs_runtime_request_digest,"
            + " runtime.run_owned_start_published_release_bundle_ref AS gs_runtime_release_ref,"
            + " launch.complete_launch_binding_evidence::text AS gs_complete_launch_binding_json"
            + " FROM game_session_canonical_instance_launch launch"
            + " LEFT JOIN game_instances runtime"
            + " ON runtime.tenant_id = launch.game_session_tenant_id"
            + " AND runtime.id = launch.game_instance_id"
            + " AND runtime.game_instance_uuid = launch.game_instance_uuid"
            + " WHERE launch.target_namespace = ? AND launch.control_plane_request_id = ?"
            + suffix,
        workloadNamespace,
        controlPlaneRequestId);
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
      parseStatus(String value) {
    try {
      return CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.valueOf(
          value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored Game Instance has an unsupported current lifecycle status", unknown);
    }
  }

  private static UUID newNonNilUuid() {
    UUID value = UUID.randomUUID();
    if (NIL_UUID.equals(value)) {
      throw new IllegalStateException("Game Session UUID allocator returned nil");
    }
    return value;
  }

  private static void requireNoActiveOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical launch owner operation requires its own independent transaction");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session canonical launch storage access requires an active owner transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    if (row == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Game Session canonical launch storage returned no row");
    }
    T value = row.get(field, type);
    if (value == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored canonical launch is missing required evidence: " + field);
    }
    return value;
  }

  private static void requireOne(int affectedRows, String label) {
    if (affectedRows != 1) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Game Session " + label + " insert did not affect exactly one row");
    }
  }

  public static final class InvalidCanonicalInstanceLaunchException extends IllegalStateException {
    public InvalidCanonicalInstanceLaunchException(String message) {
      super(message);
    }

    public InvalidCanonicalInstanceLaunchException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
