package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionLookup;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionOperation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionState;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.PreparationDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class WorldCanonicalStartSessionTerminalReadTest {
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID INSTANCE_ID = UUID.fromString("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final String NAMESPACE = "world-runtime";
  private static final String REQUEST_ID = "world-terminal-read-test";
  private static final String WORKLOAD_IDENTITY =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String AUTHORIZATION_REFERENCE_FINGERPRINT =
      "arfp/v1/test-key/" + "b".repeat(64);
  private static final String PREPARATION_INPUT_JSON =
      "{ \"selected\" : \"immutable source\", \"revision\": 3 }\n";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearAmbientTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void committedAndAbortedReadsProjectExactIdentityAndWorldFenceWithoutMutation() {
    for (ExecutionState state : List.of(ExecutionState.COMMITTED, ExecutionState.ABORTED)) {
      WorldCanonicalInstanceExecutionIdentity identity =
          identity(Instant.now().plusSeconds(300L), false);
      long worldFence = 83L;
      long privateWorldRowKey = 991L;
      ExecutionOperation operation =
          new ExecutionOperation(
              state, worldFence, state == ExecutionState.COMMITTED ? privateWorldRowKey : null);
      var repository = mock(WorldCanonicalInstancePreparationRepository.class);
      var recoveryVerifier = mock(OriginalOperationRecoveryVerifier.class);
      var commitVerifier =
          mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
      when(repository.readExactExecution(identity))
          .thenReturn(new ExecutionLookup(Optional.of(operation), true));
      var service =
          new WorldCanonicalInstancePreparationService(
              repository, commitVerifier, recoveryVerifier);

      WorldStartSessionExecutionTerminal terminal =
          service.recoverExactTerminal(identity).orElseThrow();
      WorldStartSessionExecutionTerminal decoded =
          WorldStartSessionExecutionTerminal.fromStored(terminal.canonicalBytes());

      assertThat(decoded.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(decoded.originalPostAuthorizationTuple())
          .containsExactly(identity.originalPostAuthorizationTuple());
      assertThat(decoded.accountWorldParticipationId())
          .isEqualTo(identity.accountWorldParticipationId());
      assertThat(decoded.accountWorldParticipationFence())
          .isEqualTo(identity.accountWorldParticipationFence());
      assertThat(decoded.gameSessionOwnerAttemptId())
          .isEqualTo(identity.gameSessionOwnerAttemptId());
      assertThat(decoded.gameSessionOwnerFence()).isEqualTo(identity.gameSessionOwnerFence());
      assertThat(decoded.targetNamespace()).isEqualTo(identity.targetNamespace());
      assertThat(decoded.canonicalTenantId()).isEqualTo(identity.canonicalTenantId());
      assertThat(decoded.controlPlaneRequestId()).isEqualTo(identity.controlPlaneRequestId());
      assertThat(decoded.canonicalGameInstanceId()).isEqualTo(identity.canonicalGameInstanceId());
      assertThat(decoded.preparationInputDigest()).isEqualTo(identity.preparationInputDigest());
      assertThat(decoded.preparationInputJson()).isEqualTo(identity.preparationInputJson());
      assertThat(decoded.worldExecutionFence()).isEqualTo(worldFence);
      assertThat(decoded.outcome())
          .isEqualTo(
              state == ExecutionState.COMMITTED
                  ? WorldStartSessionExecutionTerminal.Outcome.COMMITTED
                  : WorldStartSessionExecutionTerminal.Outcome.ABORTED);
      String canonical = new String(decoded.canonicalBytes(), StandardCharsets.UTF_8);
      assertThat(canonical).doesNotContain("worldInstanceId");

      var order = inOrder(recoveryVerifier, repository);
      order.verify(recoveryVerifier).verifyOriginalOperation(identity);
      order.verify(repository).readExactExecution(identity);
      verifyNoMoreInteractions(recoveryVerifier, repository);
      verifyNoInteractions(commitVerifier);
    }
  }

  @Test
  void absentAndPendingOperationsRemainUnresolved() {
    WorldCanonicalInstanceExecutionIdentity identity =
        identity(Instant.now().plusSeconds(300L), false);
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier = mock(OriginalOperationRecoveryVerifier.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    when(repository.readExactExecution(identity))
        .thenReturn(
            new ExecutionLookup(Optional.empty(), true),
            new ExecutionLookup(
                Optional.of(new ExecutionOperation(ExecutionState.PENDING, 83L, null)), true));
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    assertThat(service.recoverExactTerminal(identity)).isEmpty();
    assertThat(service.recoverExactTerminal(identity)).isEmpty();

    verify(recoveryVerifier, org.mockito.Mockito.times(2)).verifyOriginalOperation(identity);
    verify(repository, org.mockito.Mockito.times(2)).readExactExecution(identity);
    verifyNoMoreInteractions(recoveryVerifier, repository);
    verifyNoInteractions(commitVerifier);
  }

  @Test
  void expiredOriginalAuthorizationCanOnlyProduceStableExactReadbackWithExplicitVerifier() {
    WorldCanonicalInstanceExecutionIdentity identity =
        identity(Instant.now().minusSeconds(5L), false);
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier = mock(OriginalOperationRecoveryVerifier.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    var operation = new ExecutionOperation(ExecutionState.ABORTED, 83L, null);
    when(repository.readExactExecution(identity))
        .thenReturn(
            new ExecutionLookup(Optional.of(operation), false),
            new ExecutionLookup(Optional.of(operation), false));
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    byte[] first = service.recoverExactTerminal(identity).orElseThrow().canonicalBytes();
    byte[] second = service.recoverExactTerminal(identity).orElseThrow().canonicalBytes();

    assertThat(second).containsExactly(first);
    verify(recoveryVerifier, org.mockito.Mockito.times(2)).verifyOriginalOperation(identity);
    verify(repository, org.mockito.Mockito.times(2)).readExactExecution(identity);
    verifyNoMoreInteractions(recoveryVerifier, repository);
    verifyNoInteractions(commitVerifier);
  }

  @Test
  void preservesEqualIndependentlyOwnedFenceValuesInTerminal() {
    WorldCanonicalInstanceExecutionIdentity identity =
        identity(Instant.now().plusSeconds(300L), true);
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier = mock(OriginalOperationRecoveryVerifier.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    when(repository.readExactExecution(identity))
        .thenReturn(
            new ExecutionLookup(
                Optional.of(new ExecutionOperation(ExecutionState.COMMITTED, 31L, 991L)), false));
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    WorldStartSessionExecutionTerminal terminal =
        service.recoverExactTerminal(identity).orElseThrow();

    assertThat(terminal.accountWorldParticipationFence()).isEqualTo(31L);
    assertThat(terminal.gameSessionOwnerFence()).isEqualTo(31L);
    assertThat(terminal.worldExecutionFence()).isEqualTo(31L);
  }

  @Test
  void defaultDenialVerifierDenialAndAmbientTransactionPrecedeStorage() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    WorldCanonicalInstanceExecutionIdentity identity =
        mock(WorldCanonicalInstanceExecutionIdentity.class);

    assertThatThrownBy(
            () ->
                new WorldCanonicalInstancePreparationService(repository)
                    .recoverExactTerminal(identity))
        .isInstanceOf(PreparationDeniedException.class)
        .hasMessageContaining("no authenticated original-operation verifier");
    verifyNoInteractions(repository);

    var recoveryVerifier = mock(OriginalOperationRecoveryVerifier.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    doThrow(new PreparationDeniedException("denied"))
        .when(recoveryVerifier)
        .verifyOriginalOperation(identity);
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);
    assertThatThrownBy(() -> service.recoverExactTerminal(identity))
        .isInstanceOf(PreparationDeniedException.class)
        .hasMessage("denied");
    verifyNoInteractions(repository, commitVerifier);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.recoverExactTerminal(identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    verifyNoInteractions(repository);
  }

  private static WorldCanonicalInstanceExecutionIdentity identity(
      Instant originalExpiry, boolean equalFences) {
    UUID actorAccountId = UUID.randomUUID();
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, actorAccountId),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic World terminal-read fixture");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            REQUEST_ID, actorAccountId, action);
    StartSessionAuthorityEvidenceBundle.BundleReference bundleReference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    StartSessionPostAuthorizationExecutionTuple postTuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            WORKLOAD_IDENTITY,
            AUTHORIZATION_REFERENCE_FINGERPRINT,
            UUID.randomUUID(),
            19L,
            authorityEvidenceBundle(preTuple, actorAccountId, originalExpiry),
            bundleReference);
    long accountParticipationFence = 31L;
    long gameSessionOwnerFence = equalFences ? accountParticipationFence : 37L;
    return new WorldCanonicalInstanceExecutionIdentity(
        postTuple.canonicalBytes(),
        UUID.randomUUID(),
        accountParticipationFence,
        UUID.randomUUID(),
        gameSessionOwnerFence,
        INSTANCE_ID,
        PREPARATION_INPUT_JSON);
  }

  /** Canonical integrity fixture only; these synthetic bytes do not authenticate Account. */
  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID actorAccountId, Instant expiresAt) {
    String tenantId = tuple.action().scope().tenantId().toString();
    Instant now = Instant.now();
    Map<String, Object> projection =
        Map.of(
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceId",
            "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion",
            "17",
            "projectionStatus",
            "CURRENT",
            "evaluatedAt",
            canonicalTimestamp(now.minusSeconds(30L)),
            "expiresAt",
            canonicalTimestamp(expiresAt));
    Map<String, Object> operationIdentity =
        Map.of(
            "issuanceOperationId",
            UUID.randomUUID().toString(),
            "controlPlaneRequestId",
            tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
            Map.of(
                "requestIdentityKind",
                "controlPlaneRequestId",
                "requestId",
                tuple.controlPlaneRequestId()),
            "mutationDigest",
            tuple.mutationDigest());
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration",
            1L,
            "accountAuthorityGeneration",
            2L,
            "tenantAuthorityGeneration",
            Map.of(tenantId, 3L),
            "membershipAuthorityGeneration",
            Map.of(tenantId, 4L),
            "privateRealmGrantVersions",
            List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            actorAccountId.toString(),
            "controlUiTokenJti",
            UUID.randomUUID().toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actorAccountId.toString(),
                "applicableTenantId",
                tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operationIdentity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authorityTuple,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Could not encode synthetic StartSession evidence", exception);
    }
  }

  private static String canonicalTimestamp(Instant instant) {
    return Instant.ofEpochMilli(instant.toEpochMilli()).toString();
  }
}
