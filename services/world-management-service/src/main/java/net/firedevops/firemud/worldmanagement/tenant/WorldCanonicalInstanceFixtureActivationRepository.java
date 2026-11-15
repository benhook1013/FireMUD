package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixedPresetResources;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One-use, SQL-guarded storage owner for the local fixture PREPARING-to-ACTIVE CAS. */
public final class WorldCanonicalInstanceFixtureActivationRepository {
  private final DSLContext dsl;

  public WorldCanonicalInstanceFixtureActivationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /** Runs only inside the explicit writable READ COMMITTED activation transaction. */
  public byte[] activateInOwnerTransaction(
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInstanceFixtureActivationRequest request,
      RunOwnedWorldFixedPresetResources.FixedPresetPlan presetPlan,
      byte[] requestBytes) {
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(presetPlan, "presetPlan");
    Objects.requireNonNull(requestBytes, "requestBytes");
    byte[] lifecycleEvidenceBytes =
        new WorldCanonicalInstanceFixtureActivationResult(request).canonicalBytes();
    requireWritableReadCommittedTransaction();
    if (!association.isRunOwnedFixture()
        || !association.identity().canonicalTenantId().equals(request.canonicalTenantId())
        || !association
            .identity()
            .canonicalGameInstanceId()
            .equals(request.canonicalGameInstanceId())
        || !association
            .runOwnedFixture()
            .fixtureProvenanceDigest()
            .equals(request.fixturePolicySealEvidenceDigest())
        || association.runOwnedFixture().preparingLifecycleEpoch() != 1L
        || !presetPlan
            .fixtureOperationId()
            .equals(association.runOwnedFixture().fixtureOperationId().toString())
        || !java.util.Arrays.equals(request.canonicalBytes(), requestBytes)) {
      throw invalidOwnerEvidence();
    }
    byte[] preparingEvidence = preparingEvidenceBytes(association, requestBytes, presetPlan);
    String requestDigest = digest(requestBytes);
    String preparingEvidenceDigest = digest(preparingEvidence);
    String resultDigest = digest(lifecycleEvidenceBytes);
    Record result =
        dsl.fetchOne(
            "SELECT result_bytes FROM world_activate_run_owned_fixture(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            request.activationRequestId(),
            request.canonicalGameInstanceId(),
            request.expectedLifecycleEpoch(),
            request.expectedRowVersion(),
            request.fixturePolicySealEvidenceDigest(),
            requestBytes,
            requestDigest,
            preparingEvidence,
            preparingEvidenceDigest,
            presetPlan.planJson(),
            presetPlan.expectedContentDigest(),
            resultDigest,
            lifecycleEvidenceBytes);
    if (result == null || result.get("result_bytes", byte[].class) == null) {
      throw invalidOwnerEvidence();
    }
    return WorldCanonicalInstanceFixtureActivationResult.fromStored(
            request, result.get("result_bytes", byte[].class))
        .canonicalBytes();
  }

  /** Reads the result in the lifecycle owner's independent read-only REPEATABLE READ snapshot. */
  public Optional<byte[]> readCommittedResultInOwnerTransaction(
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInstanceFixtureActivationRequest request,
      RunOwnedWorldFixedPresetResources.FixedPresetPlan presetPlan,
      byte[] requestBytes) {
    requireReadOnlyRepeatableReadTransaction();
    return readCommittedResultFromSnapshot(association, request, presetPlan, requestBytes);
  }

  private Optional<byte[]> readCommittedResultFromSnapshot(
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInstanceFixtureActivationRequest request,
      RunOwnedWorldFixedPresetResources.FixedPresetPlan presetPlan,
      byte[] requestBytes) {
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(presetPlan, "presetPlan");
    Objects.requireNonNull(requestBytes, "requestBytes");
    if (!association.isRunOwnedFixture()
        || !association.identity().canonicalTenantId().equals(request.canonicalTenantId())
        || !association
            .identity()
            .canonicalGameInstanceId()
            .equals(request.canonicalGameInstanceId())
        || !java.util.Arrays.equals(request.canonicalBytes(), requestBytes)) {
      throw invalidOwnerEvidence();
    }
    String requestDigest = digest(requestBytes);
    String preparingEvidenceDigest =
        digest(preparingEvidenceBytes(association, requestBytes, presetPlan));
    String presetPlanDigest = digest(presetPlan.planJson().getBytes(StandardCharsets.UTF_8));
    Record row =
        dsl.fetchOne(
            "SELECT o.result_bytes, o.result_digest FROM world_run_owned_fixture_activation_operation o "
                + "JOIN world_instance w ON w.id=o.world_instance_id "
                + "WHERE o.activation_request_id=? AND o.canonical_game_instance_id=? "
                + "AND o.world_instance_id=? AND o.request_digest=? "
                + "AND o.preparing_evidence_digest=? AND o.preset_plan_digest=? "
                + "AND o.preset_plan_json=? "
                + "AND o.fixture_policy_seal_evidence_digest=? AND o.preparation_input_digest=? "
                + "AND o.outcome='COMMITTED' AND o.expected_lifecycle_epoch=1 "
                + "AND o.expected_row_version=0 AND o.result_lifecycle_epoch=2 "
                + "AND o.result_row_version=1 AND w.status='ACTIVE' "
                + "AND w.lifecycle_epoch=2 AND w.row_version=1",
            request.activationRequestId(),
            request.canonicalGameInstanceId(),
            association.worldInstanceId(),
            requestDigest,
            preparingEvidenceDigest,
            presetPlanDigest,
            presetPlan.planJson(),
            request.fixturePolicySealEvidenceDigest(),
            association.runOwnedFixture().preparationInputDigest());
    if (row == null) return Optional.empty();
    byte[] result = row.get("result_bytes", byte[].class);
    String resultDigest = row.get("result_digest", String.class);
    if (result == null || !digest(result).equals(resultDigest)) throw invalidOwnerEvidence();
    return Optional.of(
        WorldCanonicalInstanceFixtureActivationResult.fromStored(request, result).canonicalBytes());
  }

  private static byte[] preparingEvidenceBytes(
      WorldCanonicalInstanceAssociation association,
      byte[] requestBytes,
      RunOwnedWorldFixedPresetResources.FixedPresetPlan plan) {
    var fixture = association.runOwnedFixture();
    String json =
        "{\"authorityKind\":\"RUN_OWNED_FIXTURE\","
            + "\"canonicalGameInstanceId\":\""
            + association.identity().canonicalGameInstanceId()
            + "\","
            + "\"fixtureOperationId\":\""
            + fixture.fixtureOperationId()
            + "\","
            + "\"fixturePolicySealEvidenceDigest\":\""
            + fixture.fixtureProvenanceDigest()
            + "\","
            + "\"preparationInputDigest\":\""
            + fixture.preparationInputDigest()
            + "\","
            + "\"presetPlanDigest\":\""
            + digest(plan.planJson().getBytes(StandardCharsets.UTF_8))
            + "\","
            + "\"requestBytesBase64\":\""
            + Base64.getEncoder().encodeToString(requestBytes)
            + "\","
            + "\"schema\":\"world-canonical-instance-fixture-preparing-evidence/v1\","
            + "\"sourceReceiptDigest\":\""
            + fixture.sourceIntakeReceipt().receiptDigest()
            + "\","
            + "\"versionIdentityOperationId\":\""
            + association.versionIdentityOperationId()
            + "\","
            + "\"worldInstanceId\":\""
            + association.worldInstanceId()
            + "\","
            + "\"worldPrivateTenantKey\":\""
            + fixture.worldPrivateKeys().tenantId()
            + "\","
            + "\"worldPrivateGameInstanceKey\":\""
            + fixture.worldPrivateKeys().gameInstanceId()
            + "\","
            + "\"worldPrivateVersionKey\":\""
            + fixture.worldPrivateKeys().versionId()
            + "\"}";
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Fixture preparing evidence cannot be canonicalized", impossible);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException("Fixture activation requires its writable owner transaction");
    }
    requirePhysicalTransaction("read committed", "off");
  }

  private void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Fixture activation result read requires its independent read-only REPEATABLE READ owner transaction");
    }
    requirePhysicalTransaction("repeatable read", "on");
  }

  private void requirePhysicalTransaction(String isolation, String readOnly) {
    Record state =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (state == null
        || !isolation.equals(state.get("isolation", String.class))
        || !readOnly.equals(state.get("read_only", String.class))) {
      throw new IllegalStateException("Fixture activation owner transaction state differs");
    }
  }

  private static IllegalStateException invalidOwnerEvidence() {
    return new IllegalStateException("RUN_OWNED_WORLD_ACTIVATION_EVIDENCE_MISMATCH");
  }
}
