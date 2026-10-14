package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationAcquisitionClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldStartSessionAccountParticipationVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class WorldStartSessionAccountParticipationVerifierTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");

  @AfterEach
  void clearAmbientState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactPeerAndWorkloadOnlyContextAreRequiredBeforeAccountAcquisition() {
    var acquirer =
        mock(AccountStartSessionWorldParticipationAcquisitionClient.class);
    var verifier = new WorldStartSessionAccountParticipationVerifier(NAMESPACE, acquirer);
    Input input = mock(Input.class);
    OriginalStartSessionOperationInput operationInput = operationInput();

    assertThatThrownBy(() -> verifier.verifyAndHold(input, operationInput))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(acquirer);

    var wrongPeer = peer("account-service", NAMESPACE);
    var context = Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    Context previous = context.attach();
    try {
      assertThatThrownBy(() -> verifier.verifyAndHold(input, operationInput))
          .isInstanceOf(SecurityException.class);
    } finally {
      context.detach(previous);
    }
    verifyNoInteractions(acquirer);

    var gameSessionContext =
        Context.ROOT.withValue(
            GrpcPeerIdentity.CONTEXT_KEY, peer("game-session-service", NAMESPACE));
    previous = gameSessionContext.attach();
    try {
      SessionContext.setContext("22222222-2222-4222-8222-222222222222", List.of(), Map.of());
      assertThatThrownBy(() -> verifier.verifyAndHold(input, operationInput))
          .isInstanceOf(SecurityException.class);
    } finally {
      SessionContext.clear();
      gameSessionContext.detach(previous);
    }
    verifyNoInteractions(acquirer);
  }

  @Test
  void ambientWorldTransactionIsRejectedBeforeAccountAcquisition() {
    var acquirer =
        mock(AccountStartSessionWorldParticipationAcquisitionClient.class);
    var verifier = new WorldStartSessionAccountParticipationVerifier(NAMESPACE, acquirer);
    Input input = mock(Input.class);
    OriginalStartSessionOperationInput operationInput = operationInput();
    var context =
        Context.ROOT.withValue(
            GrpcPeerIdentity.CONTEXT_KEY, peer("game-session-service", NAMESPACE));
    Context previous = context.attach();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> verifier.verifyAndHold(input, operationInput))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient World transaction");
    } finally {
      TransactionSynchronizationManager.clear();
      context.detach(previous);
    }
    verifyNoInteractions(acquirer, input);
  }

  @Test
  void accountAcquisitionRequiresTheExactGameSessionAttemptAndFence() {
    var accountClient = mock(AccountStartSessionWorldParticipationAcquisitionClient.class);
    var verifier = new WorldStartSessionAccountParticipationVerifier(NAMESPACE, accountClient);
    OriginalStartSessionOperationInput operationInput = operationInput();
    Input input =
        inputFor(uuid("99999999-9999-4999-8999-999999999999"), 37L, "STARTING");
    var context =
        Context.ROOT.withValue(
            GrpcPeerIdentity.CONTEXT_KEY, peer("game-session-service", NAMESPACE));
    Context previous = context.attach();
    try {
      assertThatThrownBy(() -> verifier.verifyAndHold(input, operationInput))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("owner attempt differs");
    } finally {
      context.detach(previous);
    }
    verifyNoInteractions(accountClient);

    Input wrongFence =
        inputFor(operationInput.gameSessionOwnerAttemptId(), 38L, "STARTING");
    previous = context.attach();
    try {
      assertThatThrownBy(() -> verifier.verifyAndHold(wrongFence, operationInput))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("owner attempt differs");
    } finally {
      context.detach(previous);
    }
    verifyNoInteractions(accountClient);

  }

  private static Input inputFor(
      UUID associationAttempt,
      long associationFence,
      String currentStatus) {
    Input input = mock(Input.class);
    var worldEvidence = mock(WorldCanonicalInstanceAssociation.GameSessionReadEvidence.class);
    when(worldEvidence.targetNamespace()).thenReturn(NAMESPACE);
    when(worldEvidence.canonicalTenantId()).thenReturn(TENANT_ID);
    when(worldEvidence.controlPlaneRequestId()).thenReturn("original-start-session");
    when(worldEvidence.currentGameSessionStatus()).thenReturn(currentStatus);
    when(input.gameSessionReadEvidence()).thenReturn(worldEvidence);
    when(input.gameSessionOwnerAttemptId()).thenReturn(associationAttempt);
    when(input.gameSessionOwnerFence()).thenReturn(associationFence);
    var receipt = mock(WorldCompleteLaunchBindingReceipt.class);
    var binding = mock(CompleteLaunchBindingEvidence.class);
    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    when(receipt.evidence()).thenReturn(binding);
    when(binding.descriptor()).thenReturn(descriptor);
    when(descriptor.gameTemplateId()).thenReturn(91L);
    when(input.completeLaunchBinding()).thenReturn(receipt);
    return input;
  }

  private static OriginalStartSessionOperationInput operationInput() {
    UUID actor = uuid("33333333-3333-4333-8333-333333333333");
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, actor),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic owner-operation input for peer-guard proof");
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "original-start-session", actor, action);
    var postTuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "a".repeat(64),
            uuid("44444444-4444-4444-8444-444444444444"),
            19L,
            authorityEvidenceBundle(preTuple, actor),
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    return new OriginalStartSessionOperationInput(
        postTuple.canonicalBytes(),
        uuid("55555555-5555-4555-8555-555555555555"),
        uuid("66666666-6666-4666-8666-666666666666"),
        37L);
  }

  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID actor) {
    String tenant = tuple.action().scope().tenantId().toString();
    Instant expiresAt = Instant.parse("2030-01-01T00:05:00Z");
    Instant now = expiresAt.minusSeconds(60L);
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
            "issuanceOperationId", uuid("77777777-7777-4777-8777-777777777777").toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration",
            1L,
            "accountAuthorityGeneration",
            2L,
            "tenantAuthorityGeneration",
            Map.of(tenant, 3L),
            "membershipAuthorityGeneration",
            Map.of(tenant, 4L),
            "privateRealmGrantVersions",
            List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            actor.toString(),
            "controlUiTokenJti",
            uuid("88888888-8888-4888-8888-888888888888").toString(),
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
                Map.of("tenantId", tenant, "targetNamespace", NAMESPACE),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actor.toString(),
                "applicableTenantId",
                tenant),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operationIdentity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authorityTuple,
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
    } catch (IOException exception) {
      throw new IllegalStateException("Could not encode the synthetic owner tuple", exception);
    }
  }

  private static String canonicalTimestamp(Instant instant) {
    return Instant.ofEpochMilli(instant.toEpochMilli()).toString();
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
