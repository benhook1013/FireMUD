package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionProvisionalDecision;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionStatus;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionGameplayAdmissionDecisionClientTest {
  private static final UUID BINDING_DECISION_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER_DECISION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID NON_V4_DECISION_ID =
      UUID.fromString("11111111-1111-1111-8111-111111111111");
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";
  private static final String HIGH = "9007199254740993";
  private static final String CHECKPOINT_PREFIX = "account:auth-authority:v1:";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactProvisionalResponseReturnsOriginalCarrierAndUsesBoundedDeadline(@TempDir Path directory)
      throws Exception {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub = mockStub();
    AccountGameplayAdmissionLeaseEvidence original = evidence();
    when(stub.getCanonicalGameplayAdmissionDecision(any()))
        .thenReturn(validResponse(BINDING_DECISION_ID, original));
    GameSessionGameplayAdmissionDecisionClient client = newClient(stub, "test", directory);

    AccountGameplayAdmissionProvisionalDecision result = client.read(BINDING_DECISION_ID, original);

    assertThat(result.bindingDecisionId()).isEqualTo(BINDING_DECISION_ID);
    assertThat(result.evidence()).isSameAs(original);
    assertThat(result.toString())
        .doesNotContain(original.canonicalJson(), original.sha256(), ACCOUNT, TENANT);
    ArgumentCaptor<GetCanonicalGameplayAdmissionDecisionRequest> requestCaptor =
        ArgumentCaptor.forClass(GetCanonicalGameplayAdmissionDecisionRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).getCanonicalGameplayAdmissionDecision(requestCaptor.capture());
    GetCanonicalGameplayAdmissionDecisionRequest request = requestCaptor.getValue();
    assertThat(request.getSchemaVersion()).isEqualTo(1);
    assertThat(request.getBindingDecisionUuid()).isEqualTo(BINDING_DECISION_ID.toString());
    assertThat(request.getLeaseEvidence())
        .isEqualTo(ByteString.copyFrom(original.canonicalJson(), StandardCharsets.UTF_8));
  }

  @Test
  void rejectsUnsupportedSchemaChangedDecisionOrMalformedResponseUuid(@TempDir Path directory)
      throws Exception {
    AccountGameplayAdmissionLeaseEvidence original = evidence();
    GetCanonicalGameplayAdmissionDecisionResponse valid =
        validResponse(BINDING_DECISION_ID, original);
    assertRejected(valid.toBuilder().setSchemaVersion(2).build(), original, directory);
    assertRejected(
        valid.toBuilder().setBindingDecisionUuid(OTHER_DECISION_ID.toString()).build(),
        original,
        directory);
    assertRejected(
        valid.toBuilder().setBindingDecisionUuid("not-a-uuid").build(), original, directory);
    assertRejected(
        valid.toBuilder().setBindingDecisionUuid("11111111-1111-1111-8111-111111111111").build(),
        original,
        directory);
  }

  @Test
  void rejectsChangedOriginalBytesDigestDefaultOrUnknownStatusAndUnknownFields(
      @TempDir Path directory) throws Exception {
    AccountGameplayAdmissionLeaseEvidence original = evidence();
    GetCanonicalGameplayAdmissionDecisionResponse valid =
        validResponse(BINDING_DECISION_ID, original);
    assertRejected(
        valid.toBuilder()
            .setLeaseEvidence(ByteString.copyFromUtf8(original.canonicalJson() + " "))
            .build(),
        original,
        directory);
    assertRejected(
        valid.toBuilder().setLeaseEvidenceSha256("b".repeat(64)).build(), original, directory);
    assertRejected(
        valid.toBuilder()
            .setStatus(
                CanonicalGameplayAdmissionDecisionStatus
                    .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_UNSPECIFIED)
            .build(),
        original,
        directory);
    assertRejected(valid.toBuilder().setStatusValue(77).build(), original, directory);
    assertRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build(),
        original,
        directory);
  }

  @Test
  void rejectsMalformedSelectorsAndWrongNamespaceBeforeRpc(@TempDir Path directory)
      throws Exception {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub = mockStub();
    AccountGameplayAdmissionLeaseEvidence original = evidence();
    GameSessionGameplayAdmissionDecisionClient client = newClient(stub, "test", directory);

    assertThatThrownBy(() -> client.read(null, original))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.read(new UUID(0L, 0L), original))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.read(NON_V4_DECISION_ID, original))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, null))
        .isInstanceOf(IllegalArgumentException.class);

    GameSessionGameplayAdmissionDecisionClient wrongNamespaceClient =
        newClient(stub, "other", directory);
    assertThatThrownBy(() -> wrongNamespaceClient.read(BINDING_DECISION_ID, original))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    verifyNoInteractions(stub);
  }

  @Test
  void rejectsAmbientAccountTransactionBeforeNetwork(@TempDir Path directory) throws Exception {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub = mockStub();
    GameSessionGameplayAdmissionDecisionClient client = newClient(stub, "test", directory);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, evidence()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account transaction");

    verifyNoInteractions(stub);
  }

  @Test
  void propagatesCanonicalNonOkTransportFailureAndRejectsUseBeforeInitOrAfterClose(
      @TempDir Path directory) throws Exception {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("owner unavailable"));
    when(stub.getCanonicalGameplayAdmissionDecision(any())).thenThrow(unavailable);
    GameSessionGameplayAdmissionDecisionClient client = newClient(stub, "test", directory);

    assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, evidence())).isSameAs(unavailable);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);

    GameSessionGameplayAdmissionDecisionClient uninitializedClient =
        newUninitializedClient("test", directory);
    assertThatThrownBy(() -> uninitializedClient.read(BINDING_DECISION_ID, evidence()))
        .isInstanceOf(IllegalStateException.class);
    uninitializedClient.close();
    assertThatThrownBy(() -> uninitializedClient.read(BINDING_DECISION_ID, evidence()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(uninitializedClient::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void rejectsPlaintextMissingAndClasspathMtlsConfigurationBeforeChannelCreation(
      @TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = tlsProperties(directory);
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GameSessionGameplayAdmissionDecisionClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new GameSessionGameplayAdmissionDecisionClient(
                    new ServiceEndpointsProperties(), null, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    missingFiles.setCertChain(directory.resolve("missing.crt").toString());
    missingFiles.setPrivateKey(directory.resolve("missing.key").toString());
    missingFiles.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(
            () ->
                new GameSessionGameplayAdmissionDecisionClient(
                    new ServiceEndpointsProperties(), missingFiles, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readable file-backed");

    CommonGrpcClientProperties classpathMaterial = tlsProperties(directory);
    classpathMaterial.setPrivateKey("classpath:account-client.key");
    assertThatThrownBy(
            () ->
                new GameSessionGameplayAdmissionDecisionClient(
                    new ServiceEndpointsProperties(), classpathMaterial, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readable file-backed");
    verifyNoInteractions(channelFactory);
  }

  private static void assertRejected(
      GetCanonicalGameplayAdmissionDecisionResponse response,
      AccountGameplayAdmissionLeaseEvidence original,
      Path directory)
      throws Exception {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub = mockStub();
    when(stub.getCanonicalGameplayAdmissionDecision(any())).thenReturn(response);
    GameSessionGameplayAdmissionDecisionClient client = newClient(stub, "test", directory);

    assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, original))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Game Session admission decision response is invalid");
  }

  private static GameSessionGameplayAdmissionDecisionClient newClient(
      GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub,
      String namespace,
      Path directory)
      throws Exception {
    // Mocked stub and initialized flag exercise response validation, not a TLS server handshake.
    GameSessionGameplayAdmissionDecisionClient client =
        new GameSessionGameplayAdmissionDecisionClient(
            new ServiceEndpointsProperties(),
            tlsProperties(directory),
            mock(GrpcChannelFactory.class),
            namespace);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    Field initializedField =
        GameSessionGameplayAdmissionDecisionClient.class.getDeclaredField("initialized");
    initializedField.setAccessible(true);
    initializedField.setBoolean(client, true);
    return client;
  }

  private static GameSessionGameplayAdmissionDecisionClient newUninitializedClient(
      String namespace, Path directory) throws Exception {
    return new GameSessionGameplayAdmissionDecisionClient(
        new ServiceEndpointsProperties(),
        tlsProperties(directory),
        mock(GrpcChannelFactory.class),
        namespace);
  }

  private static GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
      mockStub() {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub =
        mock(GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static GetCanonicalGameplayAdmissionDecisionResponse validResponse(
      UUID bindingDecisionId, AccountGameplayAdmissionLeaseEvidence original) {
    return GetCanonicalGameplayAdmissionDecisionResponse.newBuilder()
        .setSchemaVersion(1)
        .setBindingDecisionUuid(bindingDecisionId.toString())
        .setLeaseEvidence(ByteString.copyFrom(original.canonicalJson(), StandardCharsets.UTF_8))
        .setLeaseEvidenceSha256(original.sha256())
        .setStatus(
            CanonicalGameplayAdmissionDecisionStatus
                .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_PROVISIONAL)
        .build();
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "test");
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", OTHER);
    value.put("leaseId", TENANT);
    value.put("leaseFence", HIGH);
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String field :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) {
      scope.put(field, OTHER);
    }
    scope.put("accountId", ACCOUNT);
    scope.put("tenantId", TENANT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String field :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch")) {
      scope.put(field, HIGH);
    }
    value.put("bindingScope", scope);
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", "1");
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of(TENANT, HIGH));
    tuple.put("membershipAuthorityGeneration", Map.of(TENANT, HIGH));
    tuple.put("privateRealmGrantVersions", List.of());
    value.put("authorityTuple", tuple);
    value.put("issuanceFence", HIGH);
    value.put(
        "membershipBaseline",
        new LinkedHashMap<>(
            Map.of(
                "membershipLifecycleState",
                "ACTIVE",
                "membershipVersion",
                new LinkedHashMap<>(Map.of(TENANT, HIGH)),
                "membershipAuthorityGeneration",
                HIGH)));
    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (String suffix :
        List.of(
            "account/" + ACCOUNT,
            "issuer/firemud-account-service",
            "membership/" + ACCOUNT + "/" + TENANT,
            "tenant/" + TENANT)) {
      checkpoints.add(
          new LinkedHashMap<>(
              Map.of(
                  "outboxStreamKey",
                  CHECKPOINT_PREFIX + suffix,
                  "outboxSequence",
                  suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : HIGH)));
    }
    value.put("outboxCheckpoints", checkpoints);
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", ACCOUNT);
    for (String field : List.of("operationId", "issuanceRequestId", "tokenJti")) {
      token.put(field, OTHER);
    }
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    for (String field : List.of("tokenGeneration", "issuanceFence", "tokenIdentityFence")) {
      token.put(field, HIGH);
    }
    token.put("issuedAt", "999");
    token.put("notBefore", "999");
    token.put("expiresAt", "1300");
    value.put("tokenIdentityEvidence", token);
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  private static CommonGrpcClientProperties tlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(
        Files.writeString(directory.resolve("account-client.crt"), "test-cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("account-client.key"), "test-key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("game-session-ca.crt"), "test-ca").toString());
    return tls;
  }
}
