package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessPromotionProof;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionPreparation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtSignerDesiredStateRepositoryTest {
  private static final Binding BINDING =
      new Binding(
          "prod",
          "prod-cluster-1",
          "firemud-prod",
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final TrustFence TRUST =
      new TrustFence(
          "11111111-1111-4111-8111-111111111111",
          "22222222-2222-4222-8222-222222222222",
          "a".repeat(64),
          "binding-r1");
  private static final UUID SECRET_UID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String READINESS_EVIDENCE_PREIMAGE = "{\"verifiedProbes\":[{}]}";
  private static final EnrollmentIdentity ENROLLMENT =
      new EnrollmentIdentity(
          TRUST.expectedClusterIncarnationUid(),
          TRUST.expectedNamespaceUid(),
          TRUST.bindingDigest(),
          TRUST.configRevision(),
          "b".repeat(64),
          "api-r1",
          "44444444-4444-4444-8444-444444444444",
          "12",
          "c".repeat(64));

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void durableGenerationEntryPointsRequireAnAccountTransaction() throws Exception {
    assertMandatory("initialize", Binding.class, EnrollmentIdentity.class);
    assertMandatory("read", Binding.class);
    assertMandatory("ensureCurrentGenerationRequest", Binding.class, TrustFence.class);
    assertMandatoryWritable(
        "abortUnpreparedGeneration", Binding.class, TrustFence.class, GenerationResult.class);
    assertMandatory("readCurrentGenerationRequest", Binding.class, TrustFence.class);
    assertMandatory(
        "recordSecretObservation",
        Binding.class,
        TrustFence.class,
        UUID.class,
        String.class,
        String.class,
        String.class);
    assertMandatory(
        "recordGenerationResult",
        Binding.class,
        TrustFence.class,
        UUID.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class);
    assertMandatoryWritable("readPreparedGenerationForRecovery", Binding.class, TrustFence.class);
    assertMandatoryWritable(
        "readAndMarkPreparedPromotionDispatched", Binding.class, TrustFence.class);
    assertMandatoryWritable(
        "readCurrentCommittedSigner", Binding.class, TrustFence.class, String.class, String.class);
    assertMandatoryWritable(
        "commitPreparedPromotion",
        Binding.class,
        TrustFence.class,
        UUID.class,
        ReadinessPromotionProof.class);
    assertMandatoryWritable(
        "prepareCurrentGeneration",
        Binding.class,
        TrustFence.class,
        PromotionPreparation.class,
        ReadinessPromotionProof.class);
  }

  @Test
  void promotionCommitRequiresOwnerCreatedReadinessProofBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository repository =
        new AccountJwtSignerDesiredStateRepository(dsl);

    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        repository.commitPreparedPromotion(
                            BINDING,
                            TRUST,
                            UUID.fromString("44444444-4444-4444-8444-444444444444"),
                            null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("readiness proof");
    verifyNoInteractions(dsl);
  }

  @Test
  void lockedPromotionReadsRejectReadOnlyTransactionsBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository repository =
        new AccountJwtSignerDesiredStateRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.readPreparedGenerationForRecovery(BINDING, TRUST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Writable Account transaction");
      assertThatThrownBy(() -> repository.readAndMarkPreparedPromotionDispatched(BINDING, TRUST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Writable Account transaction");
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void preparedPromotionDispatchReturnsReadbackAndRetryDoesNotRepeatCas() throws Exception {
    Conversation conversation = new Conversation();
    GenerationRequest request = inWritableTransaction(conversation::ensureRequest);
    GenerationRequest pending =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));
    PublicJwk jwk = publicJwk(request.targetKid());
    GenerationResult result =
        inWritableTransaction(
            () ->
                conversation.repository.recordGenerationResult(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    pending.generationRequestDigest(),
                    SECRET_UID.toString(),
                    "12",
                    "13",
                    jwk.json()));
    conversation.preparePromotionForDispatch(request, result);

    var first =
        inWritableTransaction(
            () -> conversation.repository.readAndMarkPreparedPromotionDispatched(BINDING, TRUST));
    assertThat(first.promotion().privatePromotionDispatched()).isTrue();
    assertThat(conversation.promotionDispatchCasCount).isEqualTo(1);

    var retry =
        inWritableTransaction(
            () -> conversation.repository.readAndMarkPreparedPromotionDispatched(BINDING, TRUST));
    assertThat(retry.promotion().privatePromotionDispatched()).isTrue();
    assertThat(retry).isEqualTo(first);
    assertThat(conversation.promotionDispatchCasCount).isEqualTo(1);
  }

  @Test
  void preparedPromotionDispatchRejectsExpiredReadinessBeforeDurableDispatch() throws Exception {
    Conversation conversation = new Conversation();
    GenerationRequest request = inWritableTransaction(conversation::ensureRequest);
    GenerationRequest pending =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));
    PublicJwk jwk = publicJwk(request.targetKid());
    GenerationResult result =
        inWritableTransaction(
            () ->
                conversation.repository.recordGenerationResult(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    pending.generationRequestDigest(),
                    SECRET_UID.toString(),
                    "12",
                    "13",
                    jwk.json()));
    conversation.preparePromotionForDispatch(request, result);
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () -> conversation.repository.readCurrentGenerationRequest(BINDING, TRUST)))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException.class)
        .hasMessageContaining(
            "Existing PREPARED signer state requires unavailable lifecycle evidence");
    conversation.expireReadinessPlan();

    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.readAndMarkPreparedPromotionDispatched(
                            BINDING, TRUST)))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException.class)
        .hasMessageContaining("expired before private dispatch");
    assertThat(conversation.promotionDispatchCasCount).isZero();
    assertThat(conversation.executedStatements)
        .noneMatch(sql -> sql.contains("UPDATE account_jwt_signer_promotion_operations"));
  }

  @Test
  void preparedPromotionDispatchCasRechecksReadinessDeadlineAtomically() throws Exception {
    Conversation conversation = new Conversation();
    GenerationRequest request = inWritableTransaction(conversation::ensureRequest);
    GenerationRequest pending =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));
    PublicJwk jwk = publicJwk(request.targetKid());
    GenerationResult result =
        inWritableTransaction(
            () ->
                conversation.repository.recordGenerationResult(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    pending.generationRequestDigest(),
                    SECRET_UID.toString(),
                    "12",
                    "13",
                    jwk.json()));
    conversation.preparePromotionForDispatch(request, result);
    conversation.expireReadinessAtDispatchCas();

    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.readAndMarkPreparedPromotionDispatched(
                            BINDING, TRUST)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.VersionConflictException.class);
    assertThat(conversation.promotionDispatchCasCount).isZero();
    assertThat(conversation.executedStatements)
        .anySatisfy(
            sql -> {
              assertThat(sql).contains("WITH readiness_clock AS MATERIALIZED");
              assertThat(sql).contains("clock_timestamp()");
              assertThat(sql).contains("plan.expires_at_epoch_seconds");
              assertThat(sql).contains("entry.expires_at_epoch_seconds");
              assertThat(sql).doesNotContain("CURRENT_TIMESTAMP");
            });
  }

  @Test
  void exactDispatchedReadinessProofCanCommitAfterExpiryButChangedProofCannot() throws Exception {
    Conversation conversation = new Conversation();
    GenerationRequest request = inWritableTransaction(conversation::ensureRequest);
    GenerationRequest pending =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));
    PublicJwk jwk = publicJwk(request.targetKid());
    GenerationResult result =
        inWritableTransaction(
            () ->
                conversation.repository.recordGenerationResult(
                    BINDING,
                    TRUST,
                    request.operationId(),
                    pending.generationRequestDigest(),
                    SECRET_UID.toString(),
                    "12",
                    "13",
                    jwk.json()));
    conversation.preparePromotionForDispatch(request, result);
    ReadinessPromotionProof exactProof =
        conversation.readinessProof(
            result, READINESS_EVIDENCE_PREIMAGE.getBytes(StandardCharsets.UTF_8));

    conversation.expireReadinessPlan();
    conversation.recordPromotionReceipts(result);
    int statementsBeforeUndeliveredRecovery = conversation.executedStatements.size();
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.commitPreparedPromotion(
                            BINDING, TRUST, conversation.preparedPromotionId, exactProof)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.QuarantinedStateException.class)
        .hasMessageContaining(
            "Promotion dispatch state is inconsistent with its receipts or terminal status");
    assertThat(
            conversation.executedStatements.subList(
                statementsBeforeUndeliveredRecovery, conversation.executedStatements.size()))
        .noneMatch(sql -> sql.contains("SET status = 'COMMITTED'"));

    conversation.preparedPromotionValues.put("private_promotion_receipt_digest", null);
    conversation.preparedPromotionValues.put("private_promotion_observed_resource_version", null);
    conversation.preparedPromotionValues.put("active_jwks_receipt_digest", null);
    conversation.preparedPromotionValues.put("active_jwks_public_data_digest", null);
    conversation.preparedPromotionValues.put("active_jwks_observed_resource_version", null);
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.readAndMarkPreparedPromotionDispatched(
                            BINDING, TRUST)))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException.class)
        .hasMessageContaining("expired before private dispatch");
    assertThat(conversation.promotionDispatchCasCount).isZero();
    conversation.readinessPlanExpired = false;

    inWritableTransaction(
        () -> conversation.repository.readAndMarkPreparedPromotionDispatched(BINDING, TRUST));
    conversation.recordPromotionReceipts(result);
    conversation.expireReadinessPlan();
    Record expiredPlan = conversation.readinessPlanRow();
    assertThat(expiredPlan.get("checked_epoch", Long.class))
        .isGreaterThan(expiredPlan.get("expires_at_epoch_seconds", Long.class));

    TrustFence changedTrust =
        new TrustFence(
            TRUST.expectedClusterIncarnationUid(),
            TRUST.expectedNamespaceUid(),
            TRUST.bindingDigest(),
            "binding-r2");
    int statementsBeforeChangedTrust = conversation.executedStatements.size();
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.commitPreparedPromotion(
                            BINDING, changedTrust, conversation.preparedPromotionId, exactProof)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.BindingMismatchException.class)
        .hasMessageContaining("pinned enrollment");
    assertThat(
            conversation.executedStatements.subList(
                statementsBeforeChangedTrust, conversation.executedStatements.size()))
        .noneMatch(sql -> sql.contains("SET status = 'COMMITTED'"));

    conversation.changePreparedStateForCommit();
    int statementsBeforeChangedState = conversation.executedStatements.size();
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.commitPreparedPromotion(
                            BINDING, TRUST, conversation.preparedPromotionId, exactProof)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.QuarantinedStateException.class)
        .hasMessageContaining("exact desired-state fence");
    assertThat(
            conversation.executedStatements.subList(
                statementsBeforeChangedState, conversation.executedStatements.size()))
        .noneMatch(sql -> sql.contains("SET status = 'COMMITTED'"));
    conversation.restorePreparedStateForCommit();

    byte[] changedPreimage = READINESS_EVIDENCE_PREIMAGE.getBytes(StandardCharsets.UTF_8);
    changedPreimage[0] ^= 1;
    ReadinessPromotionProof changedProof = conversation.readinessProof(result, changedPreimage);
    int statementsBeforeChangedProof = conversation.executedStatements.size();
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.commitPreparedPromotion(
                            BINDING, TRUST, conversation.preparedPromotionId, changedProof)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class)
        .hasMessageContaining("frozen PREPARED evidence");
    assertThat(
            conversation.executedStatements.subList(
                statementsBeforeChangedProof, conversation.executedStatements.size()))
        .noneMatch(sql -> sql.contains("SET status = 'COMMITTED'"));

    String exactPrivateReceiptDigest =
        (String) conversation.preparedPromotionValues.get("private_promotion_receipt_digest");
    conversation.preparedPromotionValues.put("private_promotion_receipt_digest", "0".repeat(64));
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.commitPreparedPromotion(
                            BINDING, TRUST, conversation.preparedPromotionId, exactProof)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.QuarantinedStateException.class)
        .hasMessageContaining("private promotion receipt digest is invalid");
    conversation.preparedPromotionValues.put("status", "PREPARED");
    conversation.preparedPromotionValues.put(
        "private_promotion_receipt_digest", exactPrivateReceiptDigest);

    var committed =
        inWritableTransaction(
            () ->
                conversation.repository.commitPreparedPromotion(
                    BINDING, TRUST, conversation.preparedPromotionId, exactProof));
    assertThat(committed.promotion().status()).isEqualTo("COMMITTED");
    assertThat(committed.desiredState().recordVersion()).isEqualTo(4L);
    String terminalUpdate =
        conversation.executedStatements.stream()
            .filter(sql -> sql.contains("SET status = 'COMMITTED'"))
            .findFirst()
            .orElseThrow();
    assertThat(terminalUpdate)
        .contains("readiness_evidence_preimage")
        .contains("entry.verified_at_epoch_seconds < entry.expires_at_epoch_seconds")
        .doesNotContain("plan.expires_at_epoch_seconds", "CURRENT_TIMESTAMP", "clock_timestamp");
  }

  @Test
  void accountCreatesAndReplaysOnlyItsOwnBoundGenerationRequest() {
    Conversation conversation = new Conversation();

    GenerationRequest first = inWritableTransaction(conversation::ensureRequest);
    GenerationRequest retry = inWritableTransaction(conversation::ensureRequest);

    assertThat(first).isEqualTo(retry);
    assertThat(first.phase()).isEqualTo(GenerationPhase.OBSERVE_PRIVATE_SECRET);
    assertThat(first.operationId().version()).isEqualTo(4);
    assertThat(first.operationDigest()).hasSize(64);
    assertThat(first.desiredStateVersion()).isEqualTo(2);
    assertThat(first.targetGeneration()).isEqualTo("1");
    assertThat(first.targetKid()).startsWith("jwt-1-");
    assertThat(first.expectedActive()).isEmpty();
    assertThat(first.expectedPublishedActive()).isEmpty();
    assertThat(first.privateSecretName()).isEqualTo("jwt-signing-keys");
    assertThat(first.expectedSecretResourceVersion()).isEmpty();
    assertThat(first.publicKeyFingerprint()).isEmpty();
    assertThat(first.publicJwkJson()).isEmpty();
    assertThat(conversation.operationInsert).isNotNull();
    assertThat(conversation.executedStatements)
        .anyMatch(sql -> sql.contains("account_jwt_signer_generation_operations"))
        .anyMatch(sql -> sql.contains("generation_operation_id"))
        .noneMatch(sql -> sql.contains("target_public_key_fingerprint"));
    verify(conversation.dsl, times(2)).execute(anyString(), any(Object[].class));
  }

  @Test
  void historicalAbortedGenerationHighWaterPreventsReuseWithoutActiveSigner() {
    Conversation conversation = new Conversation(1L);

    GenerationRequest next = inWritableTransaction(conversation::ensureRequest);

    assertThat(next.targetGeneration()).isEqualTo("2");
    assertThat(next.expectedActive()).isEmpty();
    assertThat(next.expectedPublishedActive()).isEmpty();
    assertThat(conversation.executedStatements)
        .anyMatch(sql -> sql.contains("account_jwt_signer_generation_operations"));
    verify(conversation.dsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.argThat(
                sql ->
                    sql.contains("COALESCE(MAX(target_generation), 0)")
                        && sql.contains("account_jwt_signer_generation_operations")),
            any(Object[].class));
  }

  @Test
  void generationRequestDefensivelyCopiesItsValidatedPrivateSlots() {
    Conversation conversation = new Conversation();
    GenerationRequest source = inWritableTransaction(conversation::ensureRequest);
    List<String> suppliedSlots = new ArrayList<>(source.allowedPrivateSlots());

    GenerationRequest copied =
        new GenerationRequest(
            source.phase(),
            source.operationId(),
            source.operationDigest(),
            source.generationRequestDigest(),
            source.desiredStateVersion(),
            source.binding(),
            source.trustFence(),
            source.privateSecretName(),
            source.targetGeneration(),
            source.targetKid(),
            source.targetAlgorithm(),
            source.operationAction(),
            suppliedSlots,
            source.expectedActive(),
            source.expectedPublishedActive(),
            source.secretUid(),
            source.expectedSecretResourceVersion(),
            source.generationReceiptDigest(),
            source.publicKeyFingerprint(),
            source.publicJwkJson(),
            source.observedSecretResourceVersion());

    suppliedSlots.clear();

    assertThat(copied.allowedPrivateSlots()).containsExactly("pending");
    assertThatThrownBy(() -> copied.allowedPrivateSlots().add("current"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void committedActiveSignerNeedsAnExplicitDurableRotationRequest() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository repository =
        new AccountJwtSignerDesiredStateRepository(dsl);
    Map<String, Object> values = new HashMap<>();
    values.put("environment_id", BINDING.environmentId());
    values.put("cluster_id", BINDING.clusterId());
    values.put("kubernetes_namespace", BINDING.namespace());
    values.put("custody_mode", BINDING.mode().value());
    values.put("private_secret_name", AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME);
    values.put(
        "public_jwks_config_map_name",
        AccountJwtSignerDesiredStateRepository.PUBLIC_JWKS_CONFIG_MAP_NAME);
    values.put("record_version", 8L);
    values.put("durable_active_generation", 4L);
    values.put("durable_active_kid", "jwt-4-active");
    values.put("published_active_generation", 4L);
    values.put("published_active_kid", "jwt-4-active");
    values.put("generation_operation_id", null);
    values.put("prepared_operation_id", null);
    values.put(
        "enrollment_cluster_incarnation_uid",
        UUID.fromString(TRUST.expectedClusterIncarnationUid()));
    values.put("enrollment_namespace_uid", UUID.fromString(TRUST.expectedNamespaceUid()));
    values.put("enrollment_materializer_binding_digest", TRUST.bindingDigest());
    values.put("enrollment_materializer_config_revision", TRUST.configRevision());
    values.put("enrollment_api_binding_digest", "b".repeat(64));
    values.put("enrollment_api_config_revision", "api-r1");
    values.put(
        "enrollment_public_config_map_uid",
        UUID.fromString("33333333-3333-4333-8333-333333333333"));
    values.put("enrollment_public_config_map_resource_version", "12");
    values.put("enrollment_public_config_map_snapshot_digest", "c".repeat(64));
    Record activeState = record(values);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(activeState);

    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () -> repository.ensureCurrentGenerationRequest(BINDING, TRUST)))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.MissingGenerationOperationException.class)
        .hasMessageContaining("durable rotation request");
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void secretObservationUnlocksOnlyTheExactGenerationFenceAndChangedReplayConflicts() {
    Conversation conversation = new Conversation();
    GenerationRequest operation = inWritableTransaction(conversation::ensureRequest);

    GenerationRequest pending =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));
    GenerationRequest replay =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));

    assertThat(pending.phase()).isEqualTo(GenerationPhase.GENERATE_PENDING);
    assertThat(pending.generationRequestDigest()).hasSize(64);
    assertThat(pending.secretUid()).isEqualTo(SECRET_UID.toString());
    assertThat(pending.expectedSecretResourceVersion()).isEqualTo("12");
    assertThat(pending.publicKeyFingerprint()).isEmpty();
    assertThat(replay).isEqualTo(pending);
    int executedBeforeConflict = conversation.executedStatements.size();

    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.recordSecretObservation(
                            BINDING,
                            TRUST,
                            operation.operationId(),
                            operation.operationDigest(),
                            SECRET_UID.toString(),
                            "changed-rv")))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class);
    assertThat(conversation.executedStatements).hasSize(executedBeforeConflict);
  }

  @Test
  void generationReceiptDerivesCanonicalRsaFingerprintAndRemainsNonPromoting() throws Exception {
    Conversation conversation = new Conversation();
    GenerationRequest operation = inWritableTransaction(conversation::ensureRequest);
    GenerationRequest pending =
        inWritableTransaction(
            () ->
                conversation.repository.recordSecretObservation(
                    BINDING,
                    TRUST,
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID.toString(),
                    "12"));
    PublicJwk jwk = publicJwk(operation.targetKid());

    GenerationResult result =
        inWritableTransaction(
            () ->
                conversation.repository.recordGenerationResult(
                    BINDING,
                    TRUST,
                    operation.operationId(),
                    pending.generationRequestDigest(),
                    SECRET_UID.toString(),
                    "12",
                    "13",
                    jwk.json()));
    GenerationResult replay =
        inWritableTransaction(
            () ->
                conversation.repository.recordGenerationResult(
                    BINDING,
                    TRUST,
                    operation.operationId(),
                    pending.generationRequestDigest(),
                    SECRET_UID.toString(),
                    "12",
                    "13",
                    jwk.json()));

    assertThat(result).isEqualTo(replay);
    assertThat(result.publicKeyFingerprint()).isEqualTo(jwk.fingerprint());
    assertThat(result.expectedPriorResourceVersion()).isEqualTo("12");
    assertThat(result.observedResourceVersion()).isEqualTo("13");
    assertThat(result.publicJwkJson()).doesNotContain("\"d\"");
    int executedBeforePromotionAttempt = conversation.executedStatements.size();
    PromotionPreparation callerSuppliedPreparation =
        new PromotionPreparation(
            result.operationId(),
            result,
            ENROLLMENT,
            "b".repeat(64),
            "api-r1",
            "44444444-4444-4444-8444-444444444444",
            "14",
            "d".repeat(64),
            "e".repeat(64),
            "f".repeat(64),
            "1".repeat(64),
            "2".repeat(64),
            "{\"keys\":[" + result.publicJwkJson() + "]}");
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.prepareCurrentGeneration(
                            BINDING, TRUST, callerSuppliedPreparation, null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Account-verified readiness proof is required");
    assertThat(conversation.executedStatements).hasSize(executedBeforePromotionAttempt);
  }

  @Test
  void absentOperationAndWrongCurrentOperationFailBeforeAnyMutation() {
    DSLContext emptyDsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository emptyRepository =
        new AccountJwtSignerDesiredStateRepository(emptyDsl);
    Record emptyState = stateRow(BINDING, 1, null, null, null, null, null);
    when(emptyDsl.fetchOne(anyString(), any(Object[].class))).thenReturn(emptyState);
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () -> emptyRepository.readCurrentGenerationRequest(BINDING, TRUST)))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.MissingGenerationOperationException.class);
    verify(emptyDsl, never()).execute(anyString(), any(Object[].class));

    Conversation conversation = new Conversation();
    GenerationRequest operation = inWritableTransaction(conversation::ensureRequest);
    UUID wrongOperation = UUID.fromString("55555555-5555-4555-8555-555555555555");
    int executedBeforeStaleCall = conversation.executedStatements.size();
    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        conversation.repository.recordSecretObservation(
                            BINDING,
                            TRUST,
                            wrongOperation,
                            operation.operationDigest(),
                            SECRET_UID.toString(),
                            "12")))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.StaleGenerationOperationException.class);
    assertThat(conversation.executedStatements).hasSize(executedBeforeStaleCall);
  }

  @Test
  void privateOrMalformedPublicJwkIsRejectedBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository repository =
        new AccountJwtSignerDesiredStateRepository(dsl);

    assertThatThrownBy(
            () ->
                inWritableTransaction(
                    () ->
                        repository.recordGenerationResult(
                            BINDING,
                            TRUST,
                            UUID.fromString("22222222-2222-4222-8222-222222222222"),
                            "a".repeat(64),
                            SECRET_UID.toString(),
                            "12",
                            "13",
                            "{\"d\":\"private\"}")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(dsl, never()).fetchOne(anyString(), any(Object[].class));
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void initializationStaysExplicitAndNonAuthorizing() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository repository =
        new AccountJwtSignerDesiredStateRepository(dsl);
    Record initializedState = stateRow(BINDING, 1, null, null, null, null, null);
    when(dsl.execute(anyString(), any(Object[].class))).thenReturn(1);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(initializedState);

    var state = inWritableTransaction(() -> repository.initialize(BINDING, ENROLLMENT));

    assertThat(state.recordVersion()).isEqualTo(1);
    assertThat(state.durableActive()).isEmpty();
    assertThat(state.publishedActive()).isEmpty();
    assertThat(state.generationOperationId()).isEmpty();
    assertThat(state.preparedOperationId()).isEmpty();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> bindings = ArgumentCaptor.forClass(Object[].class);
    verify(dsl).execute(sql.capture(), bindings.capture());
    assertThat(sql.getValue())
        .contains("generation_operation_id")
        .contains("prepared_operation_id");
    Object[] values = bindings.getValue();
    assertThat(values[4])
        .isInstanceOf(UUID.class)
        .isEqualTo(UUID.fromString(ENROLLMENT.expectedClusterIncarnationUid()));
    assertThat(values[5])
        .isInstanceOf(UUID.class)
        .isEqualTo(UUID.fromString(ENROLLMENT.expectedNamespaceUid()));
    assertThat(values[10])
        .isInstanceOf(UUID.class)
        .isEqualTo(UUID.fromString(ENROLLMENT.publicConfigMapUid()));
  }

  private void assertMandatory(String methodName, Class<?>... parameterTypes)
      throws ReflectiveOperationException {
    Transactional transactional =
        AccountJwtSignerDesiredStateRepository.class
            .getMethod(methodName, parameterTypes)
            .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  private void assertMandatoryWritable(String methodName, Class<?>... parameterTypes)
      throws ReflectiveOperationException {
    Transactional transactional =
        AccountJwtSignerDesiredStateRepository.class
            .getMethod(methodName, parameterTypes)
            .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(transactional.readOnly()).isFalse();
  }

  private static Record stateRow(
      Binding binding,
      long version,
      Long durableGeneration,
      String durableKid,
      Long publishedGeneration,
      String publishedKid,
      UUID generationOperationId) {
    return stateRow(
        binding,
        version,
        durableGeneration,
        durableKid,
        publishedGeneration,
        publishedKid,
        generationOperationId,
        null);
  }

  private static Record stateRow(
      Binding binding,
      long version,
      Long durableGeneration,
      String durableKid,
      Long publishedGeneration,
      String publishedKid,
      UUID generationOperationId,
      UUID preparedOperationId) {
    Map<String, Object> values = new HashMap<>();
    values.put("environment_id", binding.environmentId());
    values.put("cluster_id", binding.clusterId());
    values.put("kubernetes_namespace", binding.namespace());
    values.put("custody_mode", binding.mode().value());
    values.put("private_secret_name", AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME);
    values.put(
        "public_jwks_config_map_name",
        AccountJwtSignerDesiredStateRepository.PUBLIC_JWKS_CONFIG_MAP_NAME);
    values.put("record_version", version);
    values.put("durable_active_generation", durableGeneration);
    values.put("durable_active_kid", durableKid);
    values.put("published_active_generation", publishedGeneration);
    values.put("published_active_kid", publishedKid);
    values.put("generation_operation_id", generationOperationId);
    values.put("prepared_operation_id", preparedOperationId);
    values.put(
        "enrollment_cluster_incarnation_uid",
        UUID.fromString(ENROLLMENT.expectedClusterIncarnationUid()));
    values.put("enrollment_namespace_uid", UUID.fromString(ENROLLMENT.expectedNamespaceUid()));
    values.put(
        "enrollment_materializer_binding_digest", ENROLLMENT.materializerTrustBindingDigest());
    values.put(
        "enrollment_materializer_config_revision", ENROLLMENT.materializerTrustConfigRevision());
    values.put("enrollment_api_binding_digest", ENROLLMENT.apiBindingDigest());
    values.put("enrollment_api_config_revision", ENROLLMENT.apiConfigRevision());
    values.put(
        "enrollment_public_config_map_uid", UUID.fromString(ENROLLMENT.publicConfigMapUid()));
    values.put(
        "enrollment_public_config_map_resource_version",
        ENROLLMENT.publicConfigMapResourceVersion());
    values.put(
        "enrollment_public_config_map_snapshot_digest", ENROLLMENT.publicConfigMapSnapshotDigest());
    return record(values);
  }

  private static Record generationOperationRow(Object[] parameters) {
    Map<String, Object> values = new HashMap<>();
    values.put("operation_id", parameters[0]);
    values.put("environment_id", parameters[1]);
    values.put("cluster_id", parameters[2]);
    values.put("kubernetes_namespace", parameters[3]);
    values.put("custody_mode", parameters[4]);
    values.put("operation_digest_version", 1);
    values.put("operation_digest", parameters[5]);
    values.put("expected_record_version", parameters[6]);
    values.put("expected_previous_generation", parameters[7]);
    values.put("expected_previous_kid", parameters[8]);
    values.put("expected_published_generation", parameters[9]);
    values.put("expected_published_kid", parameters[10]);
    values.put("target_generation", parameters[11]);
    values.put("target_kid", parameters[12]);
    values.put("target_algorithm", parameters[13]);
    values.put("expected_cluster_incarnation_uid", parameters[14]);
    values.put("expected_namespace_uid", parameters[15]);
    values.put("trust_binding_digest", parameters[16]);
    values.put("trust_config_revision", parameters[17]);
    values.put("operation_action", "MATERIALIZE_PENDING");
    values.put("allowed_private_slots_canonical_bytes", parameters[18]);
    return record(values);
  }

  private static Record secretObservationRow(Object[] parameters) {
    Map<String, Object> values = new HashMap<>();
    values.put("operation_id", parameters[0]);
    values.put("environment_id", parameters[1]);
    values.put("cluster_id", parameters[2]);
    values.put("kubernetes_namespace", parameters[3]);
    values.put("custody_mode", parameters[4]);
    values.put("operation_digest", parameters[5]);
    values.put("expected_record_version", parameters[6]);
    values.put("expected_cluster_incarnation_uid", parameters[7]);
    values.put("expected_namespace_uid", parameters[8]);
    values.put("trust_binding_digest", parameters[9]);
    values.put("trust_config_revision", parameters[10]);
    values.put("private_secret_name", AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME);
    values.put("secret_uid", parameters[11]);
    values.put("observed_resource_version", parameters[12]);
    values.put("observation_digest", parameters[13]);
    values.put("generation_request_digest", parameters[14]);
    return record(values);
  }

  private static Record generationResultRow(Object[] parameters) {
    Map<String, Object> values = new HashMap<>();
    values.put("operation_id", parameters[0]);
    values.put("environment_id", parameters[1]);
    values.put("cluster_id", parameters[2]);
    values.put("kubernetes_namespace", parameters[3]);
    values.put("custody_mode", parameters[4]);
    values.put("operation_digest", parameters[5]);
    values.put("generation_request_digest", parameters[6]);
    values.put("desired_state_version", parameters[7]);
    values.put("expected_cluster_incarnation_uid", parameters[8]);
    values.put("expected_namespace_uid", parameters[9]);
    values.put("trust_binding_digest", parameters[10]);
    values.put("trust_config_revision", parameters[11]);
    values.put("private_secret_name", AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME);
    values.put("secret_uid", parameters[12]);
    values.put("expected_prior_resource_version", parameters[13]);
    values.put("observed_resource_version", parameters[14]);
    values.put("target_generation", parameters[15]);
    values.put("target_kid", parameters[16]);
    values.put("target_algorithm", "RS256");
    values.put("public_key_fingerprint", parameters[17]);
    values.put("public_jwk_json", parameters[18]);
    values.put("receipt_digest", parameters[19]);
    return record(values);
  }

  private static String promotionRequestDigest(
      UUID promotionId,
      GenerationRequest generation,
      GenerationResult result,
      String publicJwksJson)
      throws Exception {
    Map<String, Object> preimage = new HashMap<>();
    preimage.put("digestVersion", "account-jwt-signer-promotion-operation/v1");
    preimage.put("operationId", promotionId.toString());
    preimage.put("generationOperationId", generation.operationId().toString());
    preimage.put("generationOperationDigest", generation.operationDigest());
    preimage.put("generationReceiptDigest", result.receiptDigest());
    preimage.put("environmentId", BINDING.environmentId());
    preimage.put("clusterId", BINDING.clusterId());
    preimage.put("namespace", BINDING.namespace());
    preimage.put("custodyMode", BINDING.mode().value());
    preimage.put("expectedRecordVersion", "2");
    preimage.put("expectedPreviousActive", Map.of("present", false));
    preimage.put("expectedPublishedActive", Map.of("present", false));
    preimage.put("targetGeneration", generation.targetGeneration());
    preimage.put("targetKid", generation.targetKid());
    preimage.put("targetAlgorithm", "RS256");
    preimage.put("targetPublicKeyFingerprint", result.publicKeyFingerprint());
    preimage.put("expectedPrivateSecretUid", result.secretUid());
    preimage.put("expectedPrivateSecretResourceVersion", result.observedResourceVersion());
    preimage.put("expectedPublicConfigMapUid", ENROLLMENT.publicConfigMapUid());
    preimage.put("expectedPublicJwksResourceVersion", "14");
    preimage.put("expectedClusterIncarnationUid", TRUST.expectedClusterIncarnationUid());
    preimage.put("expectedNamespaceUid", TRUST.expectedNamespaceUid());
    preimage.put("materializerTrustBindingDigest", TRUST.bindingDigest());
    preimage.put("materializerTrustConfigRevision", TRUST.configRevision());
    preimage.put("apiBindingDigest", ENROLLMENT.apiBindingDigest());
    preimage.put("apiConfigRevision", ENROLLMENT.apiConfigRevision());
    preimage.put("prepublicationIntentDigest", "d".repeat(64));
    preimage.put("prepublicationReceiptDigest", "e".repeat(64));
    preimage.put("mountedObservationDigest", "f".repeat(64));
    preimage.put("readinessPlanDigest", "1".repeat(64));
    preimage.put("readinessEvidenceDigest", sha256(READINESS_EVIDENCE_PREIMAGE));
    preimage.put("publicJwksSha256", sha256(publicJwksJson));
    preimage.put("operationAction", "PROMOTE_PENDING");
    preimage.put("allowedPrivateSlots", List.of("current", "pending", "previous"));
    byte[] canonical =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            JsonMapper.builder().build().writeValueAsString(preimage));
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static PublicJwk publicJwk(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
    String modulus = encodeUnsigned(key.getModulus());
    String exponent = encodeUnsigned(key.getPublicExponent());
    Map<String, Object> jwk = new java.util.TreeMap<>();
    jwk.put("alg", "RS256");
    jwk.put("e", exponent);
    jwk.put("key_ops", List.of("verify"));
    jwk.put("kid", kid);
    jwk.put("kty", "RSA");
    jwk.put("n", modulus);
    jwk.put("use", "sig");
    String json =
        new String(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                JsonMapper.builder().build().writeValueAsString(jwk)),
            StandardCharsets.UTF_8);
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        Rfc8785CanonicalJson.canonicalizeUtf8(
                            JsonMapper.builder()
                                .build()
                                .writeValueAsString(
                                    Map.of("e", exponent, "kty", "RSA", "n", modulus)))));
    return new PublicJwk(json, fingerprint);
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Record record(Map<String, Object> values) {
    Record row = mock(Record.class);
    when(row.get(anyString(), any(Class.class)))
        .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
    return row;
  }

  private static Record scalarLongRecord(long value) {
    Record row = mock(Record.class);
    when(row.get(0, Long.class)).thenReturn(value);
    return row;
  }

  private static <T> T inWritableTransaction(java.util.function.Supplier<T> operation) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      return operation.get();
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private record PublicJwk(String json, String fingerprint) {}

  private final class Conversation {
    private final DSLContext dsl = mock(DSLContext.class);
    private final AccountJwtSignerDesiredStateRepository repository =
        new AccountJwtSignerDesiredStateRepository(dsl);
    private final List<String> executedStatements = new java.util.ArrayList<>();
    private final long historicalGenerationHighWater;
    private Object[] operationInsert;
    private Object[] observationInsert;
    private Object[] resultInsert;
    private int stateReadCount;
    private UUID preparedPromotionId;
    private Map<String, Object> preparedPromotionValues;
    private int promotionDispatchCasCount;
    private boolean readinessPlanExpired;
    private boolean readinessExpiresAtDispatchCas;
    private boolean preparedStateChangedForCommit;

    private Conversation() {
      this(0L);
    }

    private Conversation(long historicalGenerationHighWater) {
      this.historicalGenerationHighWater = historicalGenerationHighWater;
      when(dsl.execute(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                Object[] arguments = invocation.getArguments();
                Object[] parameters =
                    arguments.length == 2 && arguments[1] instanceof Object[] bindings
                        ? bindings
                        : java.util.Arrays.copyOfRange(arguments, 1, arguments.length);
                executedStatements.add(sql);
                if (sql.contains("UPDATE account_jwt_signer_promotion_operations")) {
                  if (sql.contains(
                      "private_promotion_dispatched_at = readiness_clock.checked_at")) {
                    if (readinessExpiresAtDispatchCas) {
                      return 0;
                    }
                    promotionDispatchCasCount++;
                    preparedPromotionValues.put("private_promotion_dispatched", true);
                  } else if (sql.contains("SET status = 'COMMITTED'")) {
                    preparedPromotionValues.put("status", "COMMITTED");
                  }
                }
                if (sql.contains("account_jwt_signer_generation_operations")) {
                  operationInsert = parameters.clone();
                } else if (sql.contains("account_jwt_signer_secret_observations")) {
                  observationInsert = parameters.clone();
                } else if (sql.contains("account_jwt_signer_generation_results")) {
                  resultInsert = parameters.clone();
                }
                return 1;
              });
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("account_jwt_signer_promotion_operations")) {
                  return preparedPromotionValues == null ? null : record(preparedPromotionValues);
                }
                if (sql.contains("account_jwt_readiness_probe_plans")) {
                  return preparedPromotionValues == null ? null : readinessPlanRow();
                }
                if (sql.contains("account_jwt_readiness_probe_entries")) {
                  return record(Map.of("total", 1L, "verified", 1L));
                }
                if (sql.contains("account_jwt_signer_desired_states")) {
                  stateReadCount++;
                  if (preparedPromotionId != null) {
                    if ("COMMITTED".equals(preparedPromotionValues.get("status"))) {
                      long activeGeneration =
                          ((Number) preparedPromotionValues.get("target_generation")).longValue();
                      return stateRow(
                          BINDING,
                          4,
                          activeGeneration,
                          (String) preparedPromotionValues.get("target_kid"),
                          activeGeneration,
                          (String) preparedPromotionValues.get("target_kid"),
                          null,
                          null);
                    }
                    if (preparedStateChangedForCommit) {
                      return stateRow(
                          BINDING, 4, null, null, null, null, null, preparedPromotionId);
                    }
                    return stateRow(BINDING, 3, null, null, null, null, null, preparedPromotionId);
                  }
                  return stateReadCount == 1
                      ? stateRow(BINDING, 1, null, null, null, null, null)
                      : stateRow(
                          BINDING,
                          2,
                          null,
                          null,
                          null,
                          null,
                          operationInsert == null ? null : (UUID) operationInsert[0]);
                }
                if (sql.contains("COALESCE(MAX(target_generation), 0)")) {
                  return scalarLongRecord(historicalGenerationHighWater);
                }
                if (sql.contains("account_jwt_signer_generation_operations")) {
                  return operationInsert == null ? null : generationOperationRow(operationInsert);
                }
                if (sql.contains("account_jwt_signer_generation_results")) {
                  return resultInsert == null ? null : generationResultRow(resultInsert);
                }
                if (sql.contains("account_jwt_signer_secret_observations")) {
                  return observationInsert == null ? null : secretObservationRow(observationInsert);
                }
                return null;
              });
    }

    private GenerationRequest ensureRequest() {
      return repository.ensureCurrentGenerationRequest(BINDING, TRUST);
    }

    private void expireReadinessPlan() {
      readinessPlanExpired = true;
    }

    private void changePreparedStateForCommit() {
      preparedStateChangedForCommit = true;
    }

    private void restorePreparedStateForCommit() {
      preparedStateChangedForCommit = false;
    }

    private void expireReadinessAtDispatchCas() {
      readinessExpiresAtDispatchCas = true;
    }

    private Record readinessPlanRow() {
      Map<String, Object> values = new HashMap<>();
      values.put("plan_digest", preparedPromotionValues.get("readiness_plan_digest"));
      values.put("operation_digest", resultInsert[5]);
      values.put("generation_request_digest", resultInsert[6]);
      values.put("generation_receipt_digest", resultInsert[19]);
      values.put("desired_state_version", resultInsert[7]);
      values.put("target_generation", Long.parseLong(String.valueOf(resultInsert[15])));
      values.put("target_kid", resultInsert[16]);
      values.put("target_public_key_fingerprint", resultInsert[17]);
      values.put(
          "publication_intent_digest", preparedPromotionValues.get("prepublication_intent_digest"));
      values.put(
          "publication_receipt_digest",
          preparedPromotionValues.get("prepublication_receipt_digest"));
      values.put(
          "mounted_observation_digest", preparedPromotionValues.get("mounted_observation_digest"));
      values.put("validator_inventory_complete", true);
      values.put("not_before_epoch_seconds", 100L);
      values.put("expires_at_epoch_seconds", readinessPlanExpired ? 149L : 200L);
      values.put("checked_epoch", 150L);
      return record(values);
    }

    private ReadinessPromotionProof readinessProof(GenerationResult result, byte[] preimage)
        throws Exception {
      var plan = mock(AccountJwtReadinessProbeRepository.ReadinessProbePlan.class);
      when(plan.operationId()).thenReturn(result.operationId());
      when(plan.binding()).thenReturn(BINDING);
      when(plan.trustFence()).thenReturn(TRUST);
      when(plan.planDigest())
          .thenReturn((String) preparedPromotionValues.get("readiness_plan_digest"));
      when(plan.operationDigest()).thenReturn(result.operationDigest());
      when(plan.generationRequestDigest()).thenReturn(result.generationRequestDigest());
      when(plan.generationReceiptDigest()).thenReturn(result.receiptDigest());
      when(plan.publicationIntentDigest())
          .thenReturn((String) preparedPromotionValues.get("prepublication_intent_digest"));
      when(plan.publicationReceiptDigest())
          .thenReturn((String) preparedPromotionValues.get("prepublication_receipt_digest"));
      when(plan.mountedObservationDigest())
          .thenReturn((String) preparedPromotionValues.get("mounted_observation_digest"));
      when(plan.desiredStateVersion()).thenReturn(result.desiredStateVersion());
      when(plan.targetGeneration()).thenReturn(result.targetGeneration());
      when(plan.targetKid()).thenReturn(result.targetKid());
      when(plan.targetPublicKeyFingerprint()).thenReturn(result.publicKeyFingerprint());
      when(plan.validatorInventoryComplete()).thenReturn(true);

      var intent = mock(AccountJwtJwksPublicationRepository.PrepublicationIntent.class);
      when(intent.operationId()).thenReturn(result.operationId());
      when(intent.intentDigest())
          .thenReturn((String) preparedPromotionValues.get("prepublication_intent_digest"));
      when(intent.configMapUid()).thenReturn(ENROLLMENT.publicConfigMapUid());
      when(intent.apiBindingDigest()).thenReturn(ENROLLMENT.apiBindingDigest());
      when(intent.apiConfigRevision()).thenReturn(ENROLLMENT.apiConfigRevision());
      when(intent.jwksJson())
          .thenReturn((String) preparedPromotionValues.get("expected_public_jwks_json"));

      var receipt = mock(AccountJwtJwksPublicationRepository.PublicationReceipt.class);
      when(receipt.operationId()).thenReturn(result.operationId());
      when(receipt.receiptDigest())
          .thenReturn((String) preparedPromotionValues.get("prepublication_receipt_digest"));
      when(receipt.observedResourceVersion()).thenReturn("14");

      var mount = mock(AccountJwtJwksPublicationRepository.MountObservation.class);
      when(mount.operationId()).thenReturn(result.operationId());
      when(mount.observationDigest())
          .thenReturn((String) preparedPromotionValues.get("mounted_observation_digest"));

      var publication =
          mock(AccountJwtJwksPublicationRepository.PromotionPublicationEvidence.class);
      when(publication.intent()).thenReturn(intent);
      when(publication.receipt()).thenReturn(receipt);
      when(publication.mountedCorrespondence()).thenReturn(mount);

      var proof = mock(ReadinessPromotionProof.class);
      when(proof.plan()).thenReturn(plan);
      when(proof.generationResult()).thenReturn(result);
      when(proof.publication()).thenReturn(publication);
      when(proof.inventoryEvidenceReference())
          .thenReturn("protected-validator-inventory:" + "9".repeat(64));
      when(proof.inventoryEvidenceDigest()).thenReturn("9".repeat(64));
      when(proof.verifiedProbes())
          .thenReturn(
              List.of(mock(AccountJwtReadinessProbeRepository.VerifiedProbeEvidence.class)));
      when(proof.readinessEvidenceDigest())
          .thenReturn((String) preparedPromotionValues.get("readiness_evidence_digest"));
      when(proof.readinessEvidencePreimage()).thenReturn(preimage.clone());
      return proof;
    }

    private void recordPromotionReceipts(GenerationResult result) throws Exception {
      String promotionId = preparedPromotionId.toString();
      String operationDigest = (String) preparedPromotionValues.get("request_digest");
      String privateObservedVersion = "15";
      Map<String, Object> privateReceipt = new HashMap<>();
      privateReceipt.put("receiptVersion", 1);
      privateReceipt.put("promotionOperationId", promotionId);
      privateReceipt.put("promotionRequestDigest", operationDigest);
      privateReceipt.put("generationOperationId", result.operationId().toString());
      privateReceipt.put("generationOperationDigest", result.operationDigest());
      privateReceipt.put("generationReceiptDigest", result.receiptDigest());
      privateReceipt.put("secretResource", Map.of("kind", "Secret", "name", "jwt-signing-keys"));
      privateReceipt.put("secretUid", result.secretUid());
      privateReceipt.put("expectedPriorResourceVersion", result.observedResourceVersion());
      privateReceipt.put("observedResourceVersion", privateObservedVersion);
      privateReceipt.put("action", "PROMOTE_PENDING");
      privateReceipt.put("resultingSlots", List.of("current"));
      privateReceipt.put(
          "current",
          Map.of(
              "generation", result.targetGeneration(),
              "kid", result.targetKid(),
              "publicKeyFingerprint", result.publicKeyFingerprint()));
      privateReceipt.put("previous", Map.of("present", false));
      preparedPromotionValues.put(
          "private_promotion_observed_resource_version", privateObservedVersion);
      preparedPromotionValues.put(
          "private_promotion_receipt_digest", canonicalDigest(privateReceipt));

      String activeObservedVersion = "15";
      String jwksJson = (String) preparedPromotionValues.get("expected_public_jwks_json");
      String markerJson =
          (String) preparedPromotionValues.get("expected_active_generation_marker_json");
      String publicDataDigest =
          AccountJwtJwksPublicationRepository.publicDataDigest(jwksJson, markerJson);
      Map<String, Object> activeReceipt = new HashMap<>();
      activeReceipt.put("receiptVersion", "account-jwt-active-jwks-promotion-receipt/v1");
      activeReceipt.put("promotionOperationId", promotionId);
      activeReceipt.put("promotionRequestDigest", operationDigest);
      activeReceipt.put("generationOperationId", result.operationId().toString());
      activeReceipt.put("generationOperationDigest", result.operationDigest());
      activeReceipt.put("generationReceiptDigest", result.receiptDigest());
      activeReceipt.put("configMapName", "jwt-jwks");
      activeReceipt.put("configMapUid", ENROLLMENT.publicConfigMapUid());
      activeReceipt.put("priorResourceVersion", "14");
      activeReceipt.put("observedResourceVersion", activeObservedVersion);
      activeReceipt.put("action", "MARK_ACTIVE");
      activeReceipt.put("publicDataDigest", publicDataDigest);
      preparedPromotionValues.put("active_jwks_observed_resource_version", activeObservedVersion);
      preparedPromotionValues.put("active_jwks_public_data_digest", publicDataDigest);
      preparedPromotionValues.put("active_jwks_receipt_digest", canonicalDigest(activeReceipt));
    }

    private String canonicalDigest(Map<String, Object> value) throws Exception {
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JsonMapper.builder().build().writeValueAsString(value));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private void preparePromotionForDispatch(GenerationRequest generation, GenerationResult result)
        throws Exception {
      preparedPromotionId = UUID.fromString("66666666-6666-4666-8666-666666666666");
      String publicJwksJson = "{\"keys\":[" + result.publicJwkJson() + "]}";
      String requestDigest =
          promotionRequestDigest(preparedPromotionId, generation, result, publicJwksJson);
      preparedPromotionValues = new HashMap<>();
      preparedPromotionValues.put("operation_id", preparedPromotionId);
      preparedPromotionValues.put("environment_id", BINDING.environmentId());
      preparedPromotionValues.put("cluster_id", BINDING.clusterId());
      preparedPromotionValues.put("kubernetes_namespace", BINDING.namespace());
      preparedPromotionValues.put("custody_mode", BINDING.mode().value());
      preparedPromotionValues.put("request_digest_version", (short) 1);
      preparedPromotionValues.put("request_digest", requestDigest);
      preparedPromotionValues.put("expected_record_version", 2L);
      preparedPromotionValues.put("expected_previous_generation", null);
      preparedPromotionValues.put("expected_previous_kid", null);
      preparedPromotionValues.put("target_generation", Long.parseLong(result.targetGeneration()));
      preparedPromotionValues.put("target_kid", result.targetKid());
      preparedPromotionValues.put("target_algorithm", result.targetAlgorithm());
      preparedPromotionValues.put("target_public_key_fingerprint", result.publicKeyFingerprint());
      preparedPromotionValues.put(
          "expected_private_secret_resource_version", result.observedResourceVersion());
      preparedPromotionValues.put("expected_public_jwks_resource_version", "14");
      preparedPromotionValues.put("expected_public_active_generation", null);
      preparedPromotionValues.put("expected_public_active_kid", null);
      preparedPromotionValues.put("operation_action", "PROMOTE_PENDING");
      preparedPromotionValues.put(
          "allowed_private_slots_canonical_bytes",
          "[\"current\",\"pending\",\"previous\"]".getBytes(StandardCharsets.UTF_8));
      preparedPromotionValues.put("status", "PREPARED");
      preparedPromotionValues.put("generation_operation_id", generation.operationId());
      preparedPromotionValues.put(
          "expected_cluster_incarnation_uid",
          UUID.fromString(TRUST.expectedClusterIncarnationUid()));
      preparedPromotionValues.put(
          "expected_namespace_uid", UUID.fromString(TRUST.expectedNamespaceUid()));
      preparedPromotionValues.put("materializer_trust_binding_digest", TRUST.bindingDigest());
      preparedPromotionValues.put("materializer_trust_config_revision", TRUST.configRevision());
      preparedPromotionValues.put("api_binding_digest", ENROLLMENT.apiBindingDigest());
      preparedPromotionValues.put("api_config_revision", ENROLLMENT.apiConfigRevision());
      preparedPromotionValues.put("expected_private_secret_uid", SECRET_UID);
      preparedPromotionValues.put(
          "expected_public_config_map_uid", UUID.fromString(ENROLLMENT.publicConfigMapUid()));
      preparedPromotionValues.put("prepublication_intent_digest", "d".repeat(64));
      preparedPromotionValues.put("prepublication_receipt_digest", "e".repeat(64));
      preparedPromotionValues.put("mounted_observation_digest", "f".repeat(64));
      preparedPromotionValues.put("readiness_plan_digest", "1".repeat(64));
      preparedPromotionValues.put("readiness_evidence_digest", sha256(READINESS_EVIDENCE_PREIMAGE));
      preparedPromotionValues.put(
          "readiness_evidence_preimage",
          READINESS_EVIDENCE_PREIMAGE.getBytes(StandardCharsets.UTF_8));
      preparedPromotionValues.put("expected_public_jwks_json", publicJwksJson);
      preparedPromotionValues.put("expected_active_generation_marker_json", "{}");
      preparedPromotionValues.put("private_promotion_dispatched", false);
      preparedPromotionValues.put("private_promotion_observed_resource_version", null);
      preparedPromotionValues.put("private_promotion_receipt_digest", null);
      preparedPromotionValues.put("active_jwks_observed_resource_version", null);
      preparedPromotionValues.put("active_jwks_public_data_digest", null);
      preparedPromotionValues.put("active_jwks_receipt_digest", null);
      preparedPromotionValues.put("generation_operation_digest", generation.operationDigest());
      preparedPromotionValues.put("generation_receipt_digest", result.receiptDigest());
    }
  }
}
