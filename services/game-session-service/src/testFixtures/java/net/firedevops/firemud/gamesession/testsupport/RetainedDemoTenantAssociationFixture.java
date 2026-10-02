package net.firedevops.firemud.gamesession.testsupport;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.AssociationReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshotRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synthetic immutable owner evidence for the demo retained-tenant command-test fixture.
 *
 * <p>The signed source metadata is synthetic and does not establish a real Game Design source row,
 * authenticated peer, or live owner approval.
 */
public final class RetainedDemoTenantAssociationFixture {
  public static final long DEMO_LEGACY_TENANT_ID = 1L;
  public static final UUID DEMO_CANONICAL_TENANT_ID =
      UUID.fromString("32ef3d8c-af20-4eb0-85cb-4339a03a618a");

  private static final UUID APPROVAL_OPERATION_ID =
      UUID.fromString("25a881bb-2200-4528-af0a-abbd67041944");
  private static final UUID ASSOCIATION_REQUEST_ID =
      UUID.fromString("45ec8370-f5e7-44ad-8bc7-bdbdafc4ae36");
  private static final long SYNTHETIC_SOURCE_GAME_ROW_ID = 900001L;
  private static final String SYNTHETIC_SOURCE_GAME_TENANT_KEY = "demo-owner-fixture";
  private static final String SYNTHETIC_SIGNER_KEY_ID = "synthetic-demo-owner-key";
  private static final String SYNTHETIC_APPROVED_BY = "synthetic-demo-fixture";
  private static final String SYNTHETIC_APPROVAL_REFERENCE = "synthetic-command-test-only";
  private static final String SYNTHETIC_SIGNED_AT = "2026-01-01T00:00:00Z";

  private final DSLContext dsl;
  private final GameSessionRetainedTenantSnapshotRepository snapshotRepository;
  private final GameSessionRetainedTenantAssociationRepository associationRepository;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;
  private AssociationReceipt committedReceipt;

  public RetainedDemoTenantAssociationFixture(
      DSLContext dsl,
      GameSessionRetainedTenantSnapshotRepository snapshotRepository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.snapshotRepository =
        Objects.requireNonNull(snapshotRepository, "snapshotRepository must not be null");
    this.workloadNamespace = Objects.requireNonNull(workloadNamespace, "workloadNamespace");
    this.associationRepository =
        new GameSessionRetainedTenantAssociationRepository(
            dsl, snapshotRepository, workloadNamespace);
    this.ownerTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Registers once, after the fixture has inserted its actual Game Session rows, then returns the
   * exact committed historical receipt on later baselines. Other retained keys stay unmapped.
   */
  public synchronized Optional<AssociationReceipt> ensureAssociation(long legacyTenantId) {
    if (legacyTenantId != DEMO_LEGACY_TENANT_ID) {
      return Optional.empty();
    }
    if (committedReceipt != null) {
      return Optional.of(readExact(committedReceipt));
    }

    AssociationReceipt existing = readExistingFixtureAssociation();
    if (existing != null) {
      committedReceipt = existing;
      return Optional.of(existing);
    }

    GameSessionRetainedTenantSnapshot captured =
        Objects.requireNonNull(
            ownerTransaction.execute(
                status ->
                    snapshotRepository.capture(
                        workloadNamespace, Long.toString(DEMO_LEGACY_TENANT_ID))),
            "fixture snapshot capture must return evidence");
    LegacyGameSessionTenantAssociationReceipt approval = syntheticApproval(captured);
    AssociationReceipt registered =
        Objects.requireNonNull(
            ownerTransaction.execute(
                status -> associationRepository.register(ASSOCIATION_REQUEST_ID, approval)),
            "fixture association registration must return a receipt");
    AssociationReceipt committed = readExact(registered);
    if (!registered.equals(committed)) {
      throw new IllegalStateException(
          "Synthetic demo tenant association committed readback contradicts registration");
    }
    committedReceipt = committed;
    return Optional.of(committed);
  }

  private AssociationReceipt readExistingFixtureAssociation() {
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, association_request_id, canonical_tenant_id "
                + "FROM game_session_retained_tenant_association "
                + "WHERE target_namespace = ? AND legacy_game_session_tenant_id = ?",
            workloadNamespace,
            DEMO_LEGACY_TENANT_ID);
    if (row == null) {
      return null;
    }
    UUID operationId = Objects.requireNonNull(row.get("operation_id", UUID.class));
    UUID requestId = Objects.requireNonNull(row.get("association_request_id", UUID.class));
    UUID canonicalTenantId = Objects.requireNonNull(row.get("canonical_tenant_id", UUID.class));
    if (!ASSOCIATION_REQUEST_ID.equals(requestId)
        || !DEMO_CANONICAL_TENANT_ID.equals(canonicalTenantId)) {
      throw new IllegalStateException(
          "Existing demo retained-tenant association contradicts its declared fixture identity");
    }
    AssociationReceipt receipt =
        associationRepository
            .read(
                operationId, requestId, canonicalTenantId, DEMO_LEGACY_TENANT_ID, workloadNamespace)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Existing demo retained-tenant association readback is missing"));
    requireSyntheticApproval(receipt);
    return receipt;
  }

  private AssociationReceipt readExact(AssociationReceipt expected) {
    AssociationReceipt actual =
        associationRepository
            .read(
                expected.operationId(),
                ASSOCIATION_REQUEST_ID,
                DEMO_CANONICAL_TENANT_ID,
                DEMO_LEGACY_TENANT_ID,
                workloadNamespace)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed demo retained-tenant association readback is missing"));
    requireSyntheticApproval(actual);
    if (!expected.equals(actual)) {
      throw new IllegalStateException(
          "Historical demo retained-tenant association changed after committed readback");
    }
    return actual;
  }

  private LegacyGameSessionTenantAssociationReceipt syntheticApproval(
      GameSessionRetainedTenantSnapshot snapshot) {
    GameSessionTenantAssociationEvidence evidence =
        new GameSessionTenantAssociationEvidence(
            1,
            APPROVAL_OPERATION_ID,
            workloadNamespace,
            SYNTHETIC_SIGNER_KEY_ID,
            SYNTHETIC_APPROVED_BY,
            SYNTHETIC_APPROVAL_REFERENCE,
            SYNTHETIC_SIGNED_AT,
            Long.toString(DEMO_LEGACY_TENANT_ID),
            DEMO_CANONICAL_TENANT_ID,
            Long.toString(SYNTHETIC_SOURCE_GAME_ROW_ID),
            SYNTHETIC_SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW",
            snapshot.evidenceDigest());
    try {
      KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(evidence.preimage());
      String signature = Base64.getEncoder().encodeToString(signer.sign());
      return new LegacyGameSessionTenantAssociationReceipt(
          evidence, evidence.manifestDigest(), signature);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign synthetic demo owner evidence", exception);
    }
  }

  private static void requireSyntheticApproval(AssociationReceipt receipt) {
    GameSessionTenantAssociationEvidence approval = receipt.approval();
    if (!ASSOCIATION_REQUEST_ID.equals(receipt.associationRequestId())
        || !APPROVAL_OPERATION_ID.equals(approval.operationId())
        || !DEMO_CANONICAL_TENANT_ID.equals(approval.canonicalTenantId())
        || approval.legacyGameSessionTenantIdValue() != DEMO_LEGACY_TENANT_ID
        || approval.sourceGameRowIdValue() != SYNTHETIC_SOURCE_GAME_ROW_ID
        || !SYNTHETIC_SOURCE_GAME_TENANT_KEY.equals(approval.sourceGameTenantKey())
        || !SYNTHETIC_SIGNER_KEY_ID.equals(approval.signerKeyId())
        || !SYNTHETIC_APPROVED_BY.equals(approval.approvedBy())
        || !SYNTHETIC_APPROVAL_REFERENCE.equals(approval.approvalReference())
        || !SYNTHETIC_SIGNED_AT.equals(approval.signedAt())) {
      throw new IllegalStateException(
          "Persisted demo association does not contain the declared synthetic owner evidence");
    }
  }
}
