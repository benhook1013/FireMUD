package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, non-activating Game Session owner primitive for a fresh canonical STARTING row.
 *
 * <p>This repository is deliberately not a Spring component and is not an Account grant or
 * redemption adapter. Its caller must already have independently redeemed the exact Account
 * authorization and supply the resulting canonical owner UUID and immutable operator request
 * identity/digest. This class has no World lifecycle, pointer, or runtime-projection dependency.
 */
public final class GameSessionCanonicalStartingInstanceRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern REQUEST_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final String STARTING = "STARTING";
  private static final String LAUNCH_ASSOCIATION_TABLE = "game_session_canonical_instance_launch";

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final CanonicalGameInstanceLaunchAssociationRepository launchAssociationRepository;

  public GameSessionCanonicalStartingInstanceRepository(DSLContext dsl, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.launchAssociationRepository =
        new CanonicalGameInstanceLaunchAssociationRepository(dsl, workloadNamespace);
  }

  /**
   * Creates or exactly replays one fresh owner row and its complete launch association.
   *
   * <p>The insert and association capture must run in one writable READ COMMITTED transaction. The
   * descriptor's candidate script patch and runtime flags remain preserved in the complete binding;
   * the owner row remains semantically UNPINNED until Game Session's pin owner commits an exact pin
   * tuple. No numeric Game Design source ID or Account database row is used as identity.
   */
  public StartingInstance createStarting(
      FreshGameSessionTenantAssociation tenantAssociation,
      CompleteLaunchBindingEvidence launchBinding,
      UUID canonicalOwnerAccountUuid,
      String controlPlaneRequestId,
      String canonicalRequestDigest) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(tenantAssociation, "tenantAssociation");
    Objects.requireNonNull(launchBinding, "launchBinding");
    requireNonNil(canonicalOwnerAccountUuid, "canonicalOwnerAccountUuid");
    requireRequestId(controlPlaneRequestId);
    requireRequestDigest(canonicalRequestDigest);

    var descriptor = launchBinding.descriptor();
    descriptor.requireValid();
    launchBinding.releaseAttestation().requireValid(descriptor);
    if (!workloadNamespace.equals(descriptor.targetNamespace())
        || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "Operator request identity or workload namespace differs from the complete launch binding");
    }
    requireFreshTenantTuple(tenantAssociation, descriptor);

    // This is the same serialization key used by association capture, before any owner lookup.
    launchAssociationRepository.lockRequestForStartingGameInstance(controlPlaneRequestId);
    requireExactPersistedSource(tenantAssociation.sourceEvidence(), launchBinding);

    Record existingAssociation = findLaunchAssociationForRequest(controlPlaneRequestId);
    if (existingAssociation != null) {
      long storedTenantId = requiredLong(existingAssociation, "game_session_tenant_id");
      long storedInstanceId = requiredLong(existingAssociation, "game_instance_id");
      UUID storedInstanceUuid = requiredUuid(existingAssociation, "game_instance_uuid");
      if (storedTenantId != tenantAssociation.legacyGameSessionTenantId()) {
        throw new CanonicalStartingInstanceConflictException(
            "Control-plane request is already bound to another Game Session tenant");
      }
      Record existingRuntime = lockRuntime(storedTenantId, storedInstanceId);
      requireRequestOwnerAndRuntime(
          existingRuntime,
          tenantAssociation,
          launchBinding,
          canonicalOwnerAccountUuid,
          controlPlaneRequestId,
          canonicalRequestDigest,
          storedInstanceUuid);

      CanonicalGameInstanceLaunchAssociation association =
          launchAssociationRepository.capture(tenantAssociation, storedInstanceId, launchBinding);
      requireAssociationMatches(association, storedTenantId, storedInstanceUuid);
      return new StartingInstance(storedInstanceId, storedInstanceUuid, association);
    }

    Record orphan =
        dsl.fetchOne(
            "SELECT id FROM game_instances "
                + "WHERE tenant_id = ? AND run_owned_start_request_id = ? FOR UPDATE",
            tenantAssociation.legacyGameSessionTenantId(),
            controlPlaneRequestId);
    if (orphan != null) {
      throw new CanonicalStartingInstanceConflictException(
          "A Game Session request row exists without its canonical launch association");
    }

    UUID gameInstanceUuid = UUID.randomUUID();
    requireNonNil(gameInstanceUuid, "allocated gameInstanceUuid");
    long gameInstanceId =
        insertStartingRuntime(
            tenantAssociation.legacyGameSessionTenantId(),
            launchBinding,
            canonicalOwnerAccountUuid,
            controlPlaneRequestId,
            canonicalRequestDigest,
            gameInstanceUuid);

    // V26's initially deferred owner trigger requires this association before transaction commit.
    CanonicalGameInstanceLaunchAssociation association =
        launchAssociationRepository.capture(tenantAssociation, gameInstanceId, launchBinding);
    Record persistedRuntime =
        lockRuntime(tenantAssociation.legacyGameSessionTenantId(), gameInstanceId);
    requireRequestOwnerAndRuntime(
        persistedRuntime,
        tenantAssociation,
        launchBinding,
        canonicalOwnerAccountUuid,
        controlPlaneRequestId,
        canonicalRequestDigest,
        gameInstanceUuid);
    if (!STARTING.equals(requiredText(persistedRuntime, "status"))
        || requiredLong(persistedRuntime, "row_version") != 0L) {
      throw new IllegalStateException(
          "New canonical Game Session owner row did not remain at its initial STARTING fence");
    }
    requireAssociationMatches(
        association, tenantAssociation.legacyGameSessionTenantId(), gameInstanceUuid);
    return new StartingInstance(gameInstanceId, gameInstanceUuid, association);
  }

  private long insertStartingRuntime(
      long privateGameSessionTenantId,
      CompleteLaunchBindingEvidence launchBinding,
      UUID canonicalOwnerAccountUuid,
      String controlPlaneRequestId,
      String canonicalRequestDigest,
      UUID gameInstanceUuid) {
    var descriptor = launchBinding.descriptor();
    requireColumnText(descriptor.launchDescriptorId(), 64, "launchDescriptorId");
    if (descriptor.remapSetIdPresent()) {
      requireColumnText(descriptor.remapSetId(), 64, "remapSetId");
    }

    // The canonical typed binding, including runtimeFlagsJson and candidate script data, is
    // persisted by capture below. Mutable feature_flag rows are not instance launch evidence.
    // A candidate patch is not an admitted pin; all current pin fields therefore start absent.
    Record row =
        dsl.fetchOne(
            "INSERT INTO game_instances "
                + "(tenant_id, runtime_version, script_patch_version, "
                + "script_patch_base_version_id, owner_account_id, owner_account_uuid, status, "
                + "row_version, game_template_id, launch_descriptor_id, version_id, "
                + "release_bundle_id, version_state_epoch, generation_config_revision, remap_set_id, "
                + "script_patch_pinned_at, script_patch_pinned_by, script_patch_pinned_reason, "
                + "script_patch_pinned_control_plane_request_id, script_pin_epoch, "
                + "run_owned_start_request_id, run_owned_start_request_digest, "
                + "run_owned_start_published_release_bundle_ref, run_owned_start_preparing_epoch, "
                + "run_owned_start_active_epoch, game_instance_uuid) "
                + "VALUES (?, ?, NULL, NULL, NULL, ?, 'STARTING', 0, ?, ?, ?, ?, ?, ?, ?, "
                + "NULL, NULL, NULL, NULL, NULL, ?, ?, ?, NULL, NULL, ?) RETURNING id",
            privateGameSessionTenantId,
            Long.toString(descriptor.versionId()),
            canonicalOwnerAccountUuid,
            descriptor.gameTemplateId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            descriptor.releaseBundleId(),
            descriptor.versionStateEpoch(),
            descriptor.generationConfigRevision(),
            descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null,
            controlPlaneRequestId,
            canonicalRequestDigest,
            descriptor.publishedReleaseBundleRef(),
            gameInstanceUuid);
    if (row == null) {
      throw new IllegalStateException(
          "Game Session STARTING owner row insert returned no identity");
    }
    return requiredLong(row, "id");
  }

  private Record findLaunchAssociationForRequest(String controlPlaneRequestId) {
    return dsl.fetchOne(
        "SELECT game_session_tenant_id, game_instance_id, game_instance_uuid FROM "
            + LAUNCH_ASSOCIATION_TABLE
            + " WHERE target_namespace = ? AND control_plane_request_id = ? FOR UPDATE",
        workloadNamespace,
        controlPlaneRequestId);
  }

  private Record lockRuntime(long tenantId, long gameInstanceId) {
    Record row =
        dsl.fetchOne(
            "SELECT id, tenant_id, game_instance_uuid, owner_account_id, owner_account_uuid, "
                + "runtime_version, status, row_version, game_template_id, launch_descriptor_id, "
                + "version_id, release_bundle_id, version_state_epoch, generation_config_revision, "
                + "remap_set_id, run_owned_start_request_id, run_owned_start_request_digest, "
                + "run_owned_start_published_release_bundle_ref "
                + "FROM game_instances WHERE tenant_id = ? AND id = ? FOR UPDATE",
            tenantId,
            gameInstanceId);
    if (row == null) {
      throw new CanonicalStartingInstanceConflictException(
          "Canonical launch association refers to a missing Game Session owner row");
    }
    return row;
  }

  private void requireRequestOwnerAndRuntime(
      Record row,
      FreshGameSessionTenantAssociation tenantAssociation,
      CompleteLaunchBindingEvidence launchBinding,
      UUID canonicalOwnerAccountUuid,
      String controlPlaneRequestId,
      String canonicalRequestDigest,
      UUID expectedGameInstanceUuid) {
    var descriptor = launchBinding.descriptor();
    if (requiredLong(row, "tenant_id") != tenantAssociation.legacyGameSessionTenantId()
        || !requiredUuid(row, "game_instance_uuid").equals(expectedGameInstanceUuid)
        || !canonicalOwnerAccountUuid.equals(row.get("owner_account_uuid", UUID.class))
        || row.get("owner_account_id", Long.class) != null
        || !controlPlaneRequestId.equals(requiredText(row, "run_owned_start_request_id"))
        || !canonicalRequestDigest.equals(requiredText(row, "run_owned_start_request_digest"))
        || !Long.toString(descriptor.versionId()).equals(requiredText(row, "runtime_version"))
        || requiredLong(row, "game_template_id") != descriptor.gameTemplateId()
        || !requiredText(row, "launch_descriptor_id").equals(descriptor.launchDescriptorId())
        || requiredLong(row, "version_id") != descriptor.versionId()
        || requiredLong(row, "release_bundle_id") != descriptor.releaseBundleId()
        || requiredLong(row, "version_state_epoch") != descriptor.versionStateEpoch()
        || !requiredText(row, "generation_config_revision")
            .equals(descriptor.generationConfigRevision())
        || !Objects.equals(
            row.get("remap_set_id", String.class),
            descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null)
        || !requiredText(row, "run_owned_start_published_release_bundle_ref")
            .equals(descriptor.publishedReleaseBundleRef())) {
      throw new CanonicalStartingInstanceConflictException(
          "Canonical launch retry changed owner, request digest, or exact descriptor tuple");
    }
  }

  private void requireExactPersistedSource(
      RuntimeTenantIdentityEvidence expected, CompleteLaunchBindingEvidence launchBinding) {
    var descriptor = launchBinding.descriptor();
    Record row =
        dsl.fetchOne(
            "SELECT intake.source_schema_version AS schema_version, intake.target_namespace, "
                + "intake.source_registration_request_id AS registration_request_id, "
                + "intake.source_operation_id AS operation_id, "
                + "intake.source_request_digest AS request_digest, "
                + "intake.canonical_tenant_id, intake.tenant_slug, intake.world_slug, "
                + "intake.world_display_name, intake.source_game_row_id, "
                + "intake.source_game_tenant_key, "
                + "intake.source_provenance_kind AS provenance_kind, "
                + "intake.source_evidence_digest AS evidence_digest "
                + "FROM game_session_authored_world_source_intake intake "
                + "JOIN game_session_authored_world_tenant_source_binding binding "
                + "ON binding.target_namespace = intake.target_namespace "
                + "AND binding.canonical_tenant_id = intake.canonical_tenant_id "
                + "AND binding.tenant_slug = intake.tenant_slug "
                + "AND binding.source_game_row_id = intake.source_game_row_id "
                + "AND binding.source_game_tenant_key = intake.source_game_tenant_key "
                + "AND binding.provenance_kind = intake.source_provenance_kind "
                + "WHERE intake.target_namespace = ? "
                + "AND intake.source_registration_request_id = ? "
                + "AND intake.source_operation_id = ? FOR KEY SHARE OF intake, binding",
            workloadNamespace,
            expected.requestId(),
            descriptor.authoredWorldSourceOperationId());
    if (row == null) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "Complete launch binding has no exact persisted authored-world source receipt");
    }

    AuthoredWorldSourceEvidence persisted;
    try {
      persisted =
          new AuthoredWorldSourceEvidence(
              requiredInt(row, "schema_version"),
              requiredText(row, "target_namespace"),
              requiredUuid(row, "registration_request_id"),
              requiredUuid(row, "operation_id"),
              requiredText(row, "request_digest"),
              requiredUuid(row, "canonical_tenant_id"),
              requiredText(row, "tenant_slug"),
              requiredText(row, "world_slug"),
              requiredText(row, "world_display_name"),
              requiredLong(row, "source_game_row_id"),
              requiredText(row, "source_game_tenant_key"),
              requiredText(row, "provenance_kind"),
              requiredText(row, "evidence_digest"));
    } catch (RuntimeException malformed) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "Persisted authored-world source evidence is malformed", malformed);
    }

    if (!"NEW_GAME_ROW".equals(persisted.provenanceKind())
        || !expected.targetNamespace().equals(persisted.targetNamespace())
        || !expected.requestId().equals(persisted.registrationRequestId())
        || !expected.canonicalTenantId().equals(persisted.canonicalTenantId())
        || expected.sourceGameRowId() != persisted.sourceGameRowId()
        || !expected.sourceGameTenantKey().equals(persisted.sourceGameTenantKey())
        || !expected.provenanceKind().equals(persisted.provenanceKind())
        || !descriptor.canonicalTenantId().equals(persisted.canonicalTenantId())
        || !descriptor.worldSlug().equals(persisted.worldSlug())
        || !descriptor.authoredWorldSourceOperationId().equals(persisted.operationId())
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(persisted.evidenceDigest())) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "Complete launch binding differs from its exact fresh authored-world source");
    }
  }

  private static void requireFreshTenantTuple(
      FreshGameSessionTenantAssociation tenantAssociation,
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor) {
    RuntimeTenantIdentityEvidence source = tenantAssociation.sourceEvidence();
    if (source.schemaVersion() != 1
        || !"NEW_GAME_ROW".equals(source.provenanceKind())
        || !descriptor.canonicalTenantId().equals(source.canonicalTenantId())
        || !descriptor.targetNamespace().equals(source.targetNamespace())) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "Fresh Game Session tenant association differs from the complete launch binding");
    }
  }

  private static void requireAssociationMatches(
      CanonicalGameInstanceLaunchAssociation association,
      long expectedTenantId,
      UUID expectedGameInstanceUuid) {
    if (association.gameSessionTenantId() != expectedTenantId
        || !association.gameInstanceUuid().equals(expectedGameInstanceUuid)) {
      throw new CanonicalStartingInstanceConflictException(
          "Canonical launch association readback differs from its Game Session owner row");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical STARTING instance creation requires a writable owner transaction");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equals(settings.get("transaction_isolation", String.class))
        || !"off".equals(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical STARTING instance creation requires writable READ COMMITTED isolation");
    }
  }

  private static void requireColumnText(String value, int maximum, String label) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          label + " does not fit the retained Game Session owner column");
    }
  }

  private static void requireRequestId(String requestId) {
    if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "controlPlaneRequestId must contain 1 to 128 nonblank characters");
    }
  }

  private static void requireRequestDigest(String requestDigest) {
    if (requestDigest == null || !REQUEST_DIGEST.matcher(requestDigest).matches()) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(
          "canonicalRequestDigest must be the exact lowercase SHA-256 value accepted by V10");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new InvalidCanonicalStartingInstanceEvidenceException(label + " must be non-nil");
    }
  }

  private static String requiredText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner row is missing " + field);
    }
    return value;
  }

  private static long requiredLong(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner row is missing " + field);
    }
    return value;
  }

  private static int requiredInt(Record row, String field) {
    Integer value = row.get(field, Integer.class);
    if (value == null) {
      throw new IllegalStateException("Persisted authored-world source is missing " + field);
    }
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalStateException("Persisted Game Session owner row has invalid " + field);
    }
    return value;
  }

  /** Original private numeric row, immutable canonical UUID, and the complete captured binding. */
  public record StartingInstance(
      long gameInstanceId,
      UUID gameInstanceUuid,
      CanonicalGameInstanceLaunchAssociation launchAssociation) {
    public StartingInstance {
      if (gameInstanceId <= 0) {
        throw new IllegalArgumentException("gameInstanceId must be positive");
      }
      requireNonNil(gameInstanceUuid, "gameInstanceUuid");
      Objects.requireNonNull(launchAssociation, "launchAssociation");
      if (!gameInstanceUuid.equals(launchAssociation.gameInstanceUuid())) {
        throw new IllegalArgumentException(
            "Starting instance UUID differs from its launch association");
      }
    }
  }

  public static final class CanonicalStartingInstanceConflictException
      extends IllegalStateException {
    public CanonicalStartingInstanceConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidCanonicalStartingInstanceEvidenceException
      extends IllegalArgumentException {
    public InvalidCanonicalStartingInstanceEvidenceException(String message) {
      super(message);
    }

    public InvalidCanonicalStartingInstanceEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
