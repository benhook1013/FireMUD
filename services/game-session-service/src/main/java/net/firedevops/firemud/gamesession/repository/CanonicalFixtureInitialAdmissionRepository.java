package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof.Outcome;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalInitialAdmissionOwner.WorldTransport;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixture purpose on the existing canonical intent, attempt, pointer and event owners.
 * Unregistered.
 */
public final class CanonicalFixtureInitialAdmissionRepository {
  private static final String INTENT = "game_session_canonical_initial_admission_intent";
  private static final String ATTEMPT = "game_session_canonical_initial_admission_attempt";
  private final DSLContext dsl;
  private final RunOwnedCanonicalLaunchPreparationOwner fixedSource;
  private final GameSessionCanonicalInstanceLaunchRepository launchOwner;
  private final TransactionTemplate transaction;
  private final WorldTransport world;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Required owner-scoped repository/DSL collaborators retain managed transaction identity; "
              + "they cannot be defensively copied and are not exposed by this owner.")
  public CanonicalFixtureInitialAdmissionRepository(
      DSLContext dsl,
      RunOwnedCanonicalLaunchPreparationOwner fixedSource,
      GameSessionCanonicalInstanceLaunchRepository launchOwner,
      PlatformTransactionManager transactionManager,
      WorldTransport world) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fixedSource = Objects.requireNonNull(fixedSource);
    this.launchOwner = Objects.requireNonNull(launchOwner);
    this.world = Objects.requireNonNull(world);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Commits the original fixed provenance before any remote hold request. */
  public void reserveFixtureIntent(WorldFixtureInitialAdmissionHoldRequest request) {
    requireOutsideTransaction();
    requireFreshOrigin(request);
    var evidence = fixedSource.loadFixedVerifiedEvidence();
    transaction.executeWithoutResult(
        status -> {
          requireOwnerTransaction();
          var launch = lockSource(request, evidence);
          var existing = intent(request, true);
          if (existing != null) {
            requireRetained(existing, request, evidence);
            return;
          }
          requireStarting(launch);
          lockAdmissionRepresentations();
          requireNoPriorPointer(request, launch);
          var s = request.selector();
          var seal = evidence.fixturePolicySeal();
          dsl.execute(
              "INSERT INTO "
                  + INTENT
                  + " (target_namespace, initial_admission_request_id, request_digest, "
                  + "canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id, playable_state_scope, "
                  + "catalog_visible, catalog_public_production, catalog_revision, catalog_creation_request_id, "
                  + "catalog_request_digest, catalog_receipt_digest, tenant_association_operation_id, game_session_tenant_id, "
                  + "canonical_game_instance_id, game_instance_id, canonical_version_id, runtime_version_id, control_plane_request_id, "
                  + "launch_descriptor_id, captured_starting_row_version, current_row_version, descriptor_request_digest, "
                  + "descriptor_result_digest, release_attestation_digest, canonical_hold_request_bytes, canonical_lifecycle_request_bytes, "
                  + "state, created_at, updated_at, catalog_source_intake_operation_id, catalog_source_intake_request_id, "
                  + "authority_kind, fixture_request_bytes, fixture_policy_request_bytes, fixture_policy_seal_evidence_digest) "
                  + "SELECT c.target_namespace, ?, ?, c.canonical_tenant_id, c.world_slug, c.realm_id, c.playable_state_namespace_id, "
                  + "c.state_scope, c.visible, c.public_production, c.catalog_revision, c.creation_request_id, c.request_digest, c.receipt_digest, "
                  + "l.tenant_association_operation_id, l.game_session_tenant_id, l.game_instance_uuid, l.game_instance_id, ?, l.version_id, "
                  + "l.control_plane_request_id, l.launch_descriptor_id, l.captured_starting_row_version, 0, "
                  + "l.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest', "
                  + "l.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest', NULL, ?, ?, 'PENDING_HOLD', "
                  + "clock_timestamp(), clock_timestamp(), c.source_intake_operation_id, c.source_intake_request_id, "
                  + "'RUN_OWNED_FIXTURE', ?, ?, ? FROM game_session_canonical_realm_catalog c "
                  + "JOIN game_session_canonical_instance_launch l ON l.target_namespace = c.target_namespace AND l.canonical_realm_id = c.realm_id "
                  + "WHERE c.target_namespace = ? AND c.realm_id = ? AND l.game_instance_uuid = ?",
              s.initialAdmissionRequestId(),
              s.initialAdmissionRequestDigest(),
              s.canonicalVersionId(),
              s.canonicalRequestBytes(),
              request.activation().canonicalBytes(),
              request.canonicalBytes(),
              evidence.fixturePolicyRequestBytes(),
              seal.evidenceDigest(),
              s.targetNamespace(),
              s.realmId(),
              s.canonicalGameInstanceId());
          requireRetained(Objects.requireNonNull(intent(request, true)), request, evidence);
        });
    requireRetained(Objects.requireNonNull(intent(request, false)), request, evidence);
  }

  /** Attaches one exact World-issued identity and reserves the existing canonical attempt. */
  public void attachFixtureHold(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity hold) {
    requireOutsideTransaction();
    requireFreshOrigin(request);
    requireHold(request, hold);
    var evidence = fixedSource.loadFixedVerifiedEvidence();
    transaction.executeWithoutResult(
        status -> {
          requireOwnerTransaction();
          var launch = lockSource(request, evidence);
          var retained =
              Objects.requireNonNull(intent(request, true), "Intent must precede hold acquisition");
          requireRetained(retained, request, evidence);
          byte[] oldHold = retained.get("hold_identity_bytes", byte[].class);
          if (oldHold != null) {
            if (!Arrays.equals(oldHold, hold.canonicalBytes()))
              throw conflict("Fixture hold changed on retry");
            return;
          }
          requireStarting(launch);
          if (dsl.execute(
                  "UPDATE "
                      + INTENT
                      + " SET state = 'HOLD_ATTACHED', hold_identity_bytes = ?, "
                      + "updated_at = clock_timestamp() WHERE target_namespace = ? AND initial_admission_request_id = ? AND state = 'PENDING_HOLD'",
                  hold.canonicalBytes(),
                  request.selector().targetNamespace(),
                  request.selector().initialAdmissionRequestId())
              != 1) throw conflict("Fixture intent changed before hold attachment");
          var s = request.selector();
          dsl.execute(
              "INSERT INTO "
                  + ATTEMPT
                  + " (target_namespace, initial_admission_request_id, request_digest, canonical_tenant_id, "
                  + "world_slug, realm_id, playable_state_namespace_id, playable_state_scope, canonical_game_instance_id, canonical_version_id, "
                  + "game_session_tenant_id, game_instance_id, runtime_version_id, expected_catalog_revision, origin_kind, active_lifecycle_epoch, "
                  + "hold_id, hold_fence, hold_binding_digest, status, created_at, updated_at, authority_kind, fixture_request_bytes, "
                  + "fixture_policy_request_bytes, fixture_policy_seal_evidence_digest) "
                  + "SELECT target_namespace, initial_admission_request_id, request_digest, canonical_tenant_id, world_slug, realm_id, "
                  + "playable_state_namespace_id, playable_state_scope, canonical_game_instance_id, canonical_version_id, game_session_tenant_id, "
                  + "game_instance_id, runtime_version_id, catalog_revision, 'NO_PRIOR_POINTER', 2, ?, ?, ?, 'PENDING', "
                  + "clock_timestamp(), clock_timestamp(), authority_kind, fixture_request_bytes, fixture_policy_request_bytes, "
                  + "fixture_policy_seal_evidence_digest FROM "
                  + INTENT
                  + " WHERE target_namespace = ? AND initial_admission_request_id = ?",
              hold.holdId(),
              hold.holdFence(),
              hold.holdBindingDigest(),
              s.targetNamespace(),
              s.initialAdmissionRequestId());
        });
    readFixtureTerminal(request)
        .orElseThrow(() -> conflict("Attached fixture attempt readback missing"));
  }

  /**
   * Authenticates exact remote ACTIVE/hold outside SQL before fresh CAS, never for terminal replay.
   */
  public void commitFixture(WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity hold) {
    requireOutsideTransaction();
    requireFreshOrigin(request);
    requireHold(request, hold);
    var retained =
        readFixtureTerminal(request)
            .orElseThrow(() -> conflict("Fixture attempt must precede commit"));
    if (!retained.holdId().equals(hold.holdId()) || !retained.holdFence().equals(hold.holdFence()))
      throw conflict("Fixture commit hold differs from original attempt");
    if (retained.outcome() == Outcome.COMMITTED) return;
    if (retained.outcome() == Outcome.ABORTED)
      throw conflict("Definitively ABORTED fixture cannot commit");
    world.verifyActive(request, hold);
    terminalize(request, hold, null);
  }

  /** Irreversible exact abort. A timeout or pointer absence is not abort evidence. */
  public void abortFixture(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity hold, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new IllegalArgumentException("Bounded abort reason required");
    requireOutsideTransaction();
    requireFreshOrigin(request);
    requireHold(request, hold);
    var retained =
        readFixtureTerminal(request)
            .orElseThrow(() -> conflict("Fixture attempt must precede abort"));
    if (!retained.holdId().equals(hold.holdId()) || !retained.holdFence().equals(hold.holdFence()))
      throw conflict("Fixture abort hold differs from original attempt");
    if (retained.outcome() == Outcome.COMMITTED) throw conflict("Committed fixture cannot abort");
    if (retained.outcome() == Outcome.ABORTED) {
      if (!reason.equals(
          Objects.requireNonNull(attempt(request, false)).get("abort_reason", String.class)))
        throw conflict("Fixture terminal abort reason is immutable");
      return;
    }
    terminalize(request, hold, reason);
  }

  private void terminalize(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity hold, String reason) {
    requireOutsideTransaction();
    requireFreshOrigin(request);
    requireHold(request, hold);
    var evidence = fixedSource.loadFixedVerifiedEvidence();
    transaction.executeWithoutResult(
        status -> {
          requireOwnerTransaction();
          var launch = lockSource(request, evidence);
          var i = Objects.requireNonNull(intent(request, true));
          requireRetained(i, request, evidence);
          if (!Arrays.equals(i.get("hold_identity_bytes", byte[].class), hold.canonicalBytes()))
            throw conflict("Exact attached hold required");
          var a =
              Objects.requireNonNull(
                  attempt(request, true), "Fixture attempt must precede terminal");
          requireRetained(a, request, evidence);
          String target = reason == null ? "COMMITTED" : "ABORTED";
          if (!"PENDING".equals(a.get("status", String.class))) {
            if (!target.equals(a.get("status", String.class))
                || !Objects.equals(reason, a.get("abort_reason", String.class)))
              throw conflict("Fixture terminal is immutable");
            return;
          }
          Long pointer = null;
          Long event = null;
          OffsetDateTime now =
              dsl.fetchSingle(
                      "SELECT GREATEST(clock_timestamp(), CAST(? AS TIMESTAMPTZ) + INTERVAL '1 microsecond', "
                          + "CAST(? AS TIMESTAMPTZ) + INTERVAL '1 microsecond')",
                      a.get("updated_at", OffsetDateTime.class),
                      i.get("updated_at", OffsetDateTime.class))
                  .get(0, OffsetDateTime.class)
                  .withOffsetSameInstant(ZoneOffset.UTC);
          if (reason == null) {
            requireStarting(launch);
            lockAdmissionRepresentations();
            requireNoPriorPointer(request, launch);
            if (dsl.execute(
                    "UPDATE game_instances SET status = 'RUNNING', row_version = row_version + 1 "
                        + "WHERE tenant_id = ? AND id = ? AND game_instance_uuid = ? AND status = 'STARTING' AND row_version = 0",
                    launch.catalog().association().gameSessionTenantId(),
                    launch.gameInstanceId(),
                    launch.gameInstanceUuid())
                != 1) throw conflict("Fixture STARTING to RUNNING CAS changed");
            pointer = insertPointerOrEvent(request, hold, launch, evidence, now, false);
            event = insertPointerOrEvent(request, hold, launch, evidence, now, true);
          }
          var s = request.selector();
          if (dsl.execute(
                  "UPDATE "
                      + ATTEMPT
                      + " SET status = ?, pointer_id = ?, pointer_version = ?, audit_event_id = ?, "
                      + "abort_reason = ?, updated_at = CAST(? AS TIMESTAMPTZ), terminal_at = CAST(? AS TIMESTAMPTZ) WHERE target_namespace = ? AND initial_admission_request_id = ? AND status = 'PENDING'",
                  target,
                  pointer,
                  reason == null ? 1L : null,
                  event,
                  reason,
                  now,
                  now,
                  s.targetNamespace(),
                  s.initialAdmissionRequestId())
              != 1) throw conflict("Fixture terminal CAS changed");
          if (dsl.execute(
                  "UPDATE "
                      + INTENT
                      + " SET state = 'TERMINAL', updated_at = CAST(? AS TIMESTAMPTZ), terminal_at = CAST(? AS TIMESTAMPTZ) "
                      + "WHERE target_namespace = ? AND initial_admission_request_id = ? AND state = 'HOLD_ATTACHED'",
                  now,
                  now,
                  s.targetNamespace(),
                  s.initialAdmissionRequestId())
              != 1) throw conflict("Fixture intent terminal CAS changed");
        });
    readFixtureTerminal(request).orElseThrow(() -> conflict("Fixture terminal readback missing"));
  }

  /**
   * Independent retained proof; terminal replay does not require fresh mutable launch/ACTIVE state.
   */
  public Optional<WorldFixtureInitialAdmissionTerminalProof> readFixtureTerminal(
      WorldFixtureInitialAdmissionHoldRequest request) {
    requireOutsideTransaction();
    requireFreshOrigin(request);
    var evidence = fixedSource.loadFixedVerifiedEvidence();
    var row = attempt(request, false);
    if (row == null) return Optional.empty();
    requireRetained(row, request, evidence);
    var i = Objects.requireNonNull(intent(request, false));
    requireRetained(i, request, evidence);
    var catalogSource = launchOwner.readVerifiedFixtureCatalog(evidence);
    var catalog = catalogSource.snapshot();
    requireRetainedCatalog(i, catalog);
    requireImmutableLaunch(i, evidence);
    if (!catalogSource
            .association()
            .associationOperationId()
            .equals(i.get("tenant_association_operation_id", UUID.class))
        || !Long.valueOf(catalogSource.association().gameSessionTenantId())
            .equals(i.get("game_session_tenant_id", Long.class)))
      throw conflict("Fixture intent differs from immutable actual tenant association");
    for (String field :
        new String[] {"game_session_tenant_id", "game_instance_id", "runtime_version_id"}) {
      if (!Objects.equals(row.get(field), i.get(field)))
        throw conflict("Fixture attempt private allocation differs from retained intent");
    }
    if (!Long.valueOf(request.selector().expectedCatalogRevision())
            .equals(row.get("expected_catalog_revision", Long.class))
        || !Long.valueOf(request.selector().activeLifecycleEpoch())
            .equals(row.get("active_lifecycle_epoch", Long.class))
        || !request
            .selector()
            .initialAdmissionOrigin()
            .name()
            .equals(row.get("origin_kind", String.class))
        || row.get("expected_prior_pointer_version") != null)
      throw conflict(
          "Fixture attempt origin or catalog/ACTIVE fence differs from original request");
    var hold = HoldIdentity.fromStored(i.get("hold_identity_bytes", byte[].class));
    requireHold(request, hold);
    if (!hold.holdId().equals(row.get("hold_id", UUID.class))
        || !hold.holdFence().equals(row.get("hold_fence", UUID.class))
        || !hold.holdBindingDigest().equals(row.get("hold_binding_digest", String.class)))
      throw conflict("Retained fixture hold differs from attempt");
    Outcome outcome = Outcome.valueOf(row.get("status", String.class));
    if (outcome != Outcome.PENDING) {
      requireTerminalDigest(row, i);
      if (!"TERMINAL".equals(i.get("state", String.class))
          || !Objects.equals(i.get("terminal_at"), row.get("terminal_at")))
        throw conflict("Fixture intent and terminal disagree");
      if (outcome == Outcome.COMMITTED) {
        var proof =
            dsl.fetchOne(
                "SELECT p.id FROM gameplay_admission_pointer p JOIN gameplay_admission_pointer_event e "
                    + "ON e.id = ? AND e.initial_admission_request_id = p.initial_admission_request_id "
                    + "AND e.target_namespace = p.target_namespace AND e.pointer_version = p.pointer_version "
                    + "WHERE p.id = ? AND p.representation_version = 3 AND e.representation_version = 3 "
                    + "AND p.target_namespace = ? AND p.initial_admission_request_id = ? AND p.initial_admission_request_digest = ? "
                    + "AND p.initial_admission_hold_id = ? AND p.initial_admission_hold_fence = ? AND p.pointer_version = 1 "
                    + "AND p.admission_state = 'OPEN' AND e.admission_state = 'OPEN' AND e.initial_admission_request_digest = p.initial_admission_request_digest "
                    + "AND e.initial_admission_hold_id = p.initial_admission_hold_id AND e.initial_admission_hold_fence = p.initial_admission_hold_fence "
                    + "AND (p.canonical_tenant_id, p.world_slug, p.realm_id, p.playable_state_namespace_id, p.state_scope, p.canonical_game_instance_id, "
                    + "p.canonical_version_id, p.catalog_revision, p.initial_admission_active_epoch, p.initial_admission_origin_kind) = (?, ?, ?, ?, ?, ?, ?, ?, 2, 'NO_PRIOR_POINTER') "
                    + "AND (e.canonical_tenant_id, e.world_slug, e.realm_id, e.playable_state_namespace_id, e.state_scope, e.canonical_game_instance_id, "
                    + "e.canonical_version_id, e.catalog_revision, e.initial_admission_active_epoch, e.initial_admission_origin_kind) = "
                    + "(p.canonical_tenant_id, p.world_slug, p.realm_id, p.playable_state_namespace_id, p.state_scope, p.canonical_game_instance_id, "
                    + "p.canonical_version_id, p.catalog_revision, p.initial_admission_active_epoch, p.initial_admission_origin_kind) "
                    + "AND (p.tenant_id, p.game_instance_id, p.runtime_version_id) = (?, ?, ?) "
                    + "AND (e.tenant_id, e.game_instance_id, e.runtime_version_id) = (p.tenant_id, p.game_instance_id, p.runtime_version_id) "
                    + "AND p.initial_admission_prior_pointer_version IS NULL AND e.initial_admission_prior_pointer_version IS NULL "
                    + "AND p.initial_admission_hold_binding_digest = ? AND e.initial_admission_hold_binding_digest = p.initial_admission_hold_binding_digest "
                    + "AND p.last_updated_by = 'game-session-canonical-initial-admission' AND e.actor_principal = p.last_updated_by "
                    + "AND p.last_update_reason = 'World-held initial admission' AND e.reason = p.last_update_reason "
                    + "AND e.control_plane_request_id = p.initial_admission_request_id "
                    + "AND p.updated_at = ? AND e.occurred_at = p.updated_at AND e.prepared_version_upgrade_id IS NULL "
                    + "AND (p.world_display_name, p.realm_slug, p.realm_display_name, p.visible, p.public_production_realm, p.character_creation_policy) = (?, ?, ?, ?, ?, ?) "
                    + "AND (e.world_display_name, e.realm_slug, e.realm_display_name, e.visible, e.public_production_realm, e.character_creation_policy) = "
                    + "(p.world_display_name, p.realm_slug, p.realm_display_name, p.visible, p.public_production_realm, p.character_creation_policy) "
                    + "AND p.requires_character_selection IS NULL AND e.requires_character_selection IS NULL",
                row.get("audit_event_id"),
                row.get("pointer_id"),
                request.selector().targetNamespace(),
                request.selector().initialAdmissionRequestId(),
                request.selector().initialAdmissionRequestDigest(),
                hold.holdId(),
                hold.holdFence(),
                request.selector().canonicalTenantId(),
                request.selector().worldSlug(),
                request.selector().realmId(),
                request.selector().playableStateNamespaceId(),
                request.selector().playableStateScope(),
                request.selector().canonicalGameInstanceId(),
                request.selector().canonicalVersionId(),
                request.selector().expectedCatalogRevision(),
                row.get("game_session_tenant_id"),
                row.get("game_instance_id"),
                row.get("runtime_version_id"),
                hold.holdBindingDigest(),
                row.get("terminal_at", OffsetDateTime.class)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime(),
                catalog.sourceIntakeReceipt().source().worldDisplayName(),
                catalog.realmSlug(),
                catalog.realmDisplayName(),
                catalog.visible(),
                catalog.publicProduction(),
                catalog.characterCreationPolicy());
        if (proof == null) throw conflict("Fixture pointer/audit terminal proof missing");
      } else {
        for (String table :
            new String[] {"gameplay_admission_pointer", "gameplay_admission_pointer_event"}) {
          if (dsl.fetchOne(
                  "SELECT 1 FROM "
                      + table
                      + " WHERE target_namespace = ? AND initial_admission_request_id = ? LIMIT 1",
                  request.selector().targetNamespace(),
                  request.selector().initialAdmissionRequestId())
              != null) throw conflict("Fixture abort contradicts retained pointer/audit evidence");
        }
      }
    }
    var at = row.get("terminal_at", OffsetDateTime.class);
    return Optional.of(
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            hold.holdId(),
            hold.holdFence(),
            outcome,
            row.get("pointer_version", Long.class),
            row.get("audit_event_id", Long.class),
            outcome == Outcome.ABORTED,
            at == null ? null : at.toInstant()));
  }

  public HoldIdentity readAttachedFixtureHold(WorldFixtureInitialAdmissionHoldRequest request) {
    requireOutsideTransaction();
    var evidence = fixedSource.loadFixedVerifiedEvidence();
    var row = Objects.requireNonNull(intent(request, false));
    requireRetained(row, request, evidence);
    var hold = HoldIdentity.fromStored(row.get("hold_identity_bytes", byte[].class));
    requireHold(request, hold);
    return hold;
  }

  public Optional<WorldFixtureInitialAdmissionTerminalProof> readFixtureTerminal(
      String namespace, String requestId) {
    requireOutsideTransaction();
    var source = fixedSource.loadFixedVerifiedEvidence();
    if (!source.sourceEvidence().targetNamespace().equals(namespace))
      throw conflict("Fixed fixture namespace mismatch");
    var row =
        dsl.fetchOne(
            "SELECT fixture_request_bytes FROM "
                + ATTEMPT
                + " WHERE target_namespace = ? AND initial_admission_request_id = ? "
                + "AND authority_kind = 'RUN_OWNED_FIXTURE'",
            namespace,
            requestId);
    return row == null
        ? Optional.empty()
        : readFixtureTerminal(
            WorldFixtureInitialAdmissionHoldRequest.fromStored(
                row.get("fixture_request_bytes", byte[].class)));
  }

  private FixtureCanonicalInstanceLaunch lockSource(
      WorldFixtureInitialAdmissionHoldRequest request,
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    var launch = launchOwner.lockVerifiedFixtureLaunchInOwnerTransaction(evidence);
    var s = request.selector();
    var c = launch.catalog().snapshot();
    if (!s.targetNamespace().equals(c.targetNamespace())
        || !s.canonicalTenantId().equals(c.tenantId())
        || !s.worldSlug().equals(c.worldSlug())
        || !s.realmId().equals(c.realmId())
        || !s.playableStateNamespaceId().equals(c.playableStateNamespaceId())
        || !s.playableStateScope().equals(c.stateScope())
        || !s.canonicalGameInstanceId().equals(launch.gameInstanceUuid())
        || s.expectedCatalogRevision() != c.catalogRevision()
        || !s.canonicalVersionId()
            .toString()
            .equals(evidence.publishedReleaseBundle().getCanonicalVersionId())
        || !request
            .activation()
            .fixturePolicySealEvidenceDigest()
            .equals(evidence.fixturePolicySeal().evidenceDigest()))
      throw conflict(
          "Fixture request differs from actual catalog, launch or immutable producer provenance");
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            s.targetNamespace(),
            s.canonicalTenantId(),
            s.worldSlug(),
            s.realmId(),
            s.playableStateNamespaceId(),
            s.playableStateScope(),
            s.canonicalGameInstanceId(),
            s.canonicalVersionId(),
            s.activeLifecycleEpoch(),
            s.expectedCatalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            s.initialAdmissionRequestId());
    if (!digest.equals(s.initialAdmissionRequestDigest()))
      throw conflict("Fixture selector digest mismatch");
    return launch;
  }

  private void requireNoPriorPointer(
      WorldFixtureInitialAdmissionHoldRequest request, FixtureCanonicalInstanceLaunch launch) {
    var s = request.selector();
    var realm = launch.catalog().snapshot();
    for (String table :
        new String[] {"gameplay_admission_pointer", "gameplay_admission_pointer_event"}) {
      if (dsl.fetchOne(
              "SELECT 1 FROM "
                  + table
                  + " WHERE realm_id = ? OR (target_namespace = ? AND canonical_tenant_id = ?) "
                  + "OR (world_slug = ? AND realm_slug = ?) OR (tenant_id = ? AND game_instance_id = ?) LIMIT 1",
              s.realmId(),
              s.targetNamespace(),
              s.canonicalTenantId(),
              s.worldSlug(),
              realm.realmSlug(),
              launch.catalog().association().gameSessionTenantId(),
              launch.gameInstanceId())
          != null) throw conflict("Fixture first OPEN cannot adopt any prior pointer or audit");
    }
    if (dsl.fetchOne(
                "SELECT 1 FROM game_session_canonical_closed_admission_pointer_request WHERE target_namespace = ? "
                    + "AND (canonical_tenant_id = ? OR realm_id = ?) LIMIT 1",
                s.targetNamespace(),
                s.canonicalTenantId(),
                s.realmId())
            != null
        || dsl.fetchOne(
                "SELECT 1 FROM gameplay_initial_admission_bind_catalog WHERE tenant_id = ? AND (realm_id = ? "
                    + "OR (world_slug = ? AND realm_slug = ?)) LIMIT 1",
                launch.catalog().association().gameSessionTenantId(),
                s.realmId(),
                s.worldSlug(),
                realm.realmSlug())
            != null
        || dsl.fetchOne(
                "SELECT 1 FROM gameplay_initial_admission_bind_attempt WHERE tenant_id = ? AND (realm_id = ? OR game_instance_id = ?) LIMIT 1",
                launch.catalog().association().gameSessionTenantId(),
                s.realmId(),
                launch.gameInstanceId())
            != null) throw conflict("Fixture origin has retained admission history");
  }

  private long insertPointerOrEvent(
      WorldFixtureInitialAdmissionHoldRequest request,
      HoldIdentity hold,
      FixtureCanonicalInstanceLaunch launch,
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      OffsetDateTime now,
      boolean event) {
    var s = request.selector();
    String table = event ? "gameplay_admission_pointer_event" : "gameplay_admission_pointer";
    String auditColumns =
        event
            ? "actor_principal, reason, control_plane_request_id, occurred_at"
            : "last_updated_by, last_update_reason, created_at, updated_at";
    var row =
        dsl.fetchOne(
            "INSERT INTO "
                + table
                + " (world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, game_instance_id, "
                + "pointer_version, visible, requires_character_selection, state_scope, character_creation_policy, public_production_realm, catalog_revision, "
                + "realm_id, playable_state_namespace_id, representation_version, target_namespace, canonical_tenant_id, admission_state, canonical_game_instance_id, "
                + "canonical_version_id, runtime_version_id, initial_admission_request_id, initial_admission_request_digest, initial_admission_origin_kind, "
                + "initial_admission_active_epoch, initial_admission_hold_id, initial_admission_hold_fence, initial_admission_hold_binding_digest, "
                + auditColumns
                + ") "
                + "SELECT c.world_slug, c.realm_slug, ?, c.realm_display_name, ?, ?, 1, c.visible, NULL, c.state_scope, c.character_creation_policy, "
                + "c.public_production, c.catalog_revision, c.realm_id, c.playable_state_namespace_id, 3, c.target_namespace, c.canonical_tenant_id, 'OPEN', "
                + "?, ?, ?, ?, ?, 'NO_PRIOR_POINTER', 2, ?, ?, ?, 'game-session-canonical-initial-admission', 'World-held initial admission', ?, ? "
                + "FROM game_session_canonical_realm_catalog c WHERE c.target_namespace = ? AND c.realm_id = ? RETURNING id",
            launch.catalog().snapshot().sourceIntakeReceipt().source().worldDisplayName(),
            launch.catalog().association().gameSessionTenantId(),
            launch.gameInstanceId(),
            s.canonicalGameInstanceId(),
            s.canonicalVersionId(),
            evidence.launchDescriptor().getVersionId(),
            s.initialAdmissionRequestId(),
            s.initialAdmissionRequestDigest(),
            hold.holdId(),
            hold.holdFence(),
            hold.holdBindingDigest(),
            event ? s.initialAdmissionRequestId() : now.toLocalDateTime(),
            now.toLocalDateTime(),
            s.targetNamespace(),
            s.realmId());
    if (row == null) throw conflict("Fixture pointer or audit write produced no row");
    return row.get("id", Long.class);
  }

  private Record intent(WorldFixtureInitialAdmissionHoldRequest request, boolean lock) {
    return row(INTENT, request, lock);
  }

  private Record attempt(WorldFixtureInitialAdmissionHoldRequest request, boolean lock) {
    return row(ATTEMPT, request, lock);
  }

  private Record row(String table, WorldFixtureInitialAdmissionHoldRequest request, boolean lock) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + table
            + " WHERE target_namespace = ? AND initial_admission_request_id = ?"
            + (lock ? " FOR UPDATE" : ""),
        request.selector().targetNamespace(),
        request.selector().initialAdmissionRequestId());
  }

  private static void requireRetained(
      Record row,
      WorldFixtureInitialAdmissionHoldRequest request,
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    var selector = request.selector();
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            selector.targetNamespace(),
            selector.canonicalTenantId(),
            selector.worldSlug(),
            selector.realmId(),
            selector.playableStateNamespaceId(),
            selector.playableStateScope(),
            selector.canonicalGameInstanceId(),
            selector.canonicalVersionId(),
            selector.activeLifecycleEpoch(),
            selector.expectedCatalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            selector.initialAdmissionRequestId());
    if (!selector.initialAdmissionRequestDigest().equals(requestDigest)
        || !selector
            .canonicalVersionId()
            .toString()
            .equals(evidence.publishedReleaseBundle().getCanonicalVersionId())
        || !selector.targetNamespace().equals(row.get("target_namespace", String.class))
        || !selector
            .initialAdmissionRequestId()
            .equals(row.get("initial_admission_request_id", String.class))
        || !selector.initialAdmissionRequestDigest().equals(row.get("request_digest", String.class))
        || !selector.canonicalTenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || !selector.worldSlug().equals(row.get("world_slug", String.class))
        || !selector.realmId().equals(row.get("realm_id", UUID.class))
        || !selector
            .playableStateNamespaceId()
            .equals(row.get("playable_state_namespace_id", UUID.class))
        || !selector.playableStateScope().equals(row.get("playable_state_scope", String.class))
        || !selector
            .canonicalGameInstanceId()
            .equals(row.get("canonical_game_instance_id", UUID.class))
        || !selector.canonicalVersionId().equals(row.get("canonical_version_id", UUID.class))
        || !"RUN_OWNED_FIXTURE".equals(row.get("authority_kind", String.class))
        || !Arrays.equals(request.canonicalBytes(), row.get("fixture_request_bytes", byte[].class))
        || !Arrays.equals(
            evidence.fixturePolicyRequestBytes(),
            row.get("fixture_policy_request_bytes", byte[].class))
        || !evidence
            .fixturePolicySeal()
            .evidenceDigest()
            .equals(row.get("fixture_policy_seal_evidence_digest", String.class)))
      throw conflict("Retained fixture authority or full original provenance differs");
  }

  /**
   * Qualifies only immutable allocation/descriptor provenance; current runtime is deliberately
   * absent.
   */
  private void requireImmutableLaunch(
      Record intent, RunOwnedInitialAdmissionLaunchEvidence evidence) {
    Record launch =
        dsl.fetchOne(
            "SELECT l.*, "
                + "l.complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest' AS descriptor_request_digest, "
                + "l.complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest' AS descriptor_result_digest "
                + "FROM game_session_canonical_instance_launch l WHERE l.target_namespace = ? AND l.game_instance_uuid = ?",
            intent.get("target_namespace"),
            intent.get("canonical_game_instance_id"));
    if (launch == null) throw conflict("Immutable fixture launch allocation is unavailable");
    for (String field :
        new String[] {
          "target_namespace",
          "canonical_tenant_id",
          "world_slug",
          "playable_state_namespace_id",
          "playable_state_scope",
          "game_session_tenant_id",
          "game_instance_id",
          "control_plane_request_id",
          "launch_descriptor_id",
          "tenant_association_operation_id",
          "captured_starting_row_version",
          "catalog_creation_request_id",
          "catalog_revision",
          "catalog_source_intake_operation_id",
          "catalog_source_intake_request_id",
          "descriptor_request_digest",
          "descriptor_result_digest"
        }) {
      if (!Objects.equals(launch.get(field), intent.get(field)))
        throw conflict("Immutable fixture launch differs from original intent");
    }
    var descriptor = evidence.launchDescriptor();
    if (launch.get("original_start_session_tuple") != null
        || intent.get("original_start_session_tuple") != null
        || intent.get("release_attestation_digest") != null
        || !Objects.equals(launch.get("canonical_realm_id"), intent.get("realm_id"))
        || !Objects.equals(
            launch.get("game_instance_uuid"), intent.get("canonical_game_instance_id"))
        || !Objects.equals(
            launch.get("fixture_canonical_version_id"), intent.get("canonical_version_id"))
        || !Objects.equals(launch.get("version_id"), intent.get("runtime_version_id"))
        || !Arrays.equals(
            evidence.fixturePolicyRequestBytes(),
            launch.get("fixture_policy_request_bytes", byte[].class))
        || !evidence
            .fixturePolicySeal()
            .evidenceDigest()
            .equals(launch.get("fixture_policy_seal_evidence_digest", String.class))
        || !evidence
            .fixtureCapability()
            .operationId()
            .equals(intent.get("control_plane_request_id", String.class))
        || !descriptor
            .getLaunchDescriptorId()
            .equals(intent.get("launch_descriptor_id", String.class))
        || !Long.valueOf(descriptor.getVersionId())
            .equals(intent.get("runtime_version_id", Long.class))
        || !Long.valueOf(0).equals(intent.get("captured_starting_row_version", Long.class))
        || !Long.valueOf(0).equals(intent.get("current_row_version", Long.class))
        || !descriptor
            .getAuthoredWorldBinding()
            .getRequestDigest()
            .equals(intent.get("descriptor_request_digest", String.class))
        || !descriptor
            .getAuthoredWorldBinding()
            .getResultDigest()
            .equals(intent.get("descriptor_result_digest", String.class)))
      throw conflict("Immutable fixture launch provenance differs from fixed complete receipt");
  }

  private static void requireRetainedCatalog(Record intent, CanonicalRealmCatalogSnapshot catalog) {
    if (!catalog.targetNamespace().equals(intent.get("target_namespace", String.class))
        || !catalog.tenantId().equals(intent.get("canonical_tenant_id", UUID.class))
        || !catalog.worldSlug().equals(intent.get("world_slug", String.class))
        || !catalog.realmId().equals(intent.get("realm_id", UUID.class))
        || !catalog
            .playableStateNamespaceId()
            .equals(intent.get("playable_state_namespace_id", UUID.class))
        || !catalog.stateScope().equals(intent.get("playable_state_scope", String.class))
        || !Boolean.valueOf(catalog.visible()).equals(intent.get("catalog_visible", Boolean.class))
        || !Boolean.valueOf(catalog.publicProduction())
            .equals(intent.get("catalog_public_production", Boolean.class))
        || !Long.valueOf(catalog.catalogRevision())
            .equals(intent.get("catalog_revision", Long.class))
        || !catalog
            .creationRequestId()
            .equals(intent.get("catalog_creation_request_id", UUID.class))
        || !catalog.requestDigest().equals(intent.get("catalog_request_digest", String.class))
        || !catalog.receiptDigest().equals(intent.get("catalog_receipt_digest", String.class))
        || !catalog
            .sourceIntakeReceipt()
            .operationId()
            .equals(intent.get("catalog_source_intake_operation_id", UUID.class))
        || !catalog
            .sourceIntakeReceipt()
            .intakeRequestId()
            .equals(intent.get("catalog_source_intake_request_id", UUID.class)))
      throw conflict("Fixture intent differs from independently qualified immutable catalog");
  }

  /** Re-derives V1067's exact length-framed terminal commitment from independent retained rows. */
  private static void requireTerminalDigest(Record attempt, Record intent) {
    String status = attempt.get("status", String.class);
    boolean committed = "COMMITTED".equals(status);
    if (attempt.get(committed ? "abort_proof_digest" : "commit_proof_digest") != null
        || (committed && attempt.get("abort_reason") != null)
        || (!committed
            && (attempt.get("pointer_id") != null
                || attempt.get("pointer_version") != null
                || attempt.get("audit_event_id") != null))
        || (!committed
            && (attempt.get("abort_reason", String.class) == null
                || attempt.get("abort_reason", String.class).isBlank())))
      throw conflict("Fixture terminal contains contradictory outcome evidence");
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
    addDigestField(
        digest,
        "game-session-fixture-initial-admission-terminal/v1".getBytes(StandardCharsets.UTF_8));
    addDigestField(digest, attempt.get("fixture_request_bytes", byte[].class));
    addDigestField(digest, attempt.get("fixture_policy_request_bytes", byte[].class));
    addDigestField(digest, intent.get("hold_identity_bytes", byte[].class));
    String terminalAt =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", java.util.Locale.ROOT)
            .withZone(ZoneOffset.UTC)
            .format(attempt.get("terminal_at", OffsetDateTime.class).toInstant());
    for (String value :
        new String[] {
          status,
          attempt.get("fixture_policy_seal_evidence_digest", String.class),
          nullableText(attempt.get("pointer_id")),
          nullableText(attempt.get("pointer_version")),
          nullableText(attempt.get("audit_event_id")),
          terminalAt,
          nullableText(attempt.get("abort_reason"))
        }) {
      addDigestField(digest, value.getBytes(StandardCharsets.UTF_8));
    }
    String expected = "sha256:" + HexFormat.of().formatHex(digest.digest());
    if (!expected.equals(
        attempt.get(committed ? "commit_proof_digest" : "abort_proof_digest", String.class)))
      throw conflict("Fixture terminal digest differs from exact retained result");
  }

  private static void addDigestField(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static String nullableText(Object value) {
    return value == null ? "" : value.toString();
  }

  public static void requireFreshOrigin(WorldFixtureInitialAdmissionHoldRequest request) {
    Objects.requireNonNull(request);
    if (request.selector().initialAdmissionOrigin() != InitialAdmissionOrigin.NO_PRIOR_POINTER
        || request.selector().expectedPriorPointerVersion() != null)
      throw conflict("Fixture authority permits fresh NO_PRIOR_POINTER only");
  }

  private static void requireHold(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity hold) {
    Objects.requireNonNull(hold);
    if (!Arrays.equals(request.selector().canonicalRequestBytes(), hold.canonicalRequestBytes()))
      throw conflict("World hold does not echo exact fixture selector");
  }

  private static void requireStarting(FixtureCanonicalInstanceLaunch launch) {
    if (launch.status() != CurrentGameInstanceStatus.STARTING || launch.rowVersion() != 0)
      throw conflict("Fresh fixture OPEN requires actual original STARTING allocation");
  }

  private void lockAdmissionRepresentations() {
    dsl.execute(
        "LOCK TABLE gameplay_admission_pointer, gameplay_admission_pointer_event, game_session_canonical_closed_admission_pointer_request, "
            + "gameplay_initial_admission_bind_catalog, gameplay_initial_admission_bind_attempt IN SHARE ROW EXCLUSIVE MODE");
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw conflict("Writable owner transaction required");
    dsl.connectionResult(
        c -> {
          if (c.getAutoCommit()
              || c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
            throw conflict("Joined READ COMMITTED owner connection required");
          return null;
        });
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw conflict(
          "Fixture operations require committed reads outside ambient writer transaction");
  }

  private static IllegalStateException conflict(String message) {
    return new CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionConflictException(
        message);
  }
}
