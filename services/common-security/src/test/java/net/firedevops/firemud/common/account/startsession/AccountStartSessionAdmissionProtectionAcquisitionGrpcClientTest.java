package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Client configuration proof; mocked channels do not establish a physical mTLS handshake. */
class AccountStartSessionAdmissionProtectionAcquisitionGrpcClientTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID HOLD_ID = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void requiresReadableFileBackedMtlsAndCanonicalNamespace(@TempDir Path directory)
      throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var tls = tls(directory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountStartSessionAdmissionProtectionAcquisitionGrpcClient(
                    new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    tls.setPlaintext(false);
    String[] invalidPaths = {
      null,
      "",
      "classpath:material.pem",
      directory.toString(),
      directory.resolve("missing.pem").toString()
    };
    String[] expectedMessages = {
      "file-backed",
      "file-backed",
      "file-backed",
      "existing readable file",
      "existing readable file"
    };
    for (int index = 0; index < invalidPaths.length; index++) {
      String path = invalidPaths[index];
      tls.setCertChain(path);
      assertThatThrownBy(
              () ->
                  new AccountStartSessionAdmissionProtectionAcquisitionGrpcClient(
                      new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(expectedMessages[index]);
    }

    assertThatThrownBy(
            () ->
                new AccountStartSessionAdmissionProtectionAcquisitionGrpcClient(
                    new ServiceEndpointsProperties(), tls(directory), channelFactory, "Test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void pinsAccountPeerAndRejectsAmbientTransactionOrEndUserBeforeTransport(@TempDir Path directory)
      throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    when(channelFactory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client =
        new AccountStartSessionAdmissionProtectionAcquisitionGrpcClient(
            endpoints, tls(directory), channelFactory, NAMESPACE);
    try {
      client.init();
      var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      var initialized = (io.grpc.stub.AbstractStub<?>) field.get(client);
      assertThat(initialized.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      var peerField =
          GrpcServerPeerIdentityCallCredentials.class.getDeclaredField("expectedPeerUri");
      peerField.setAccessible(true);
      assertThat(peerField.get(initialized.getCallOptions().getCredentials()))
          .isEqualTo("spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service");

      var stub =
          mock(
              AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc
                  .AccountStartSessionAdmissionProtectionAcquisitionServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
      when(stub.withMaxInboundMessageSize(
              AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_WIRE_BYTES))
          .thenReturn(stub);
      when(stub.withCallCredentials(any())).thenReturn(stub);
      when(stub.withInterceptors(any())).thenReturn(stub);
      when(stub.withCompression("gzip")).thenReturn(stub);
      field.set(client, stub);

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.acquire(input()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.acquire(input()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.acquire(input()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only caller context");
      verifyNoInteractions(stub);
      verify(channelFactory)
          .buildChannel(
              eq("account.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    Path cert = Files.writeString(directory.resolve("client.crt"), "synthetic certificate");
    Path key = Files.writeString(directory.resolve("client.key"), "synthetic private key");
    Path ca = Files.writeString(directory.resolve("ca.crt"), "synthetic CA");
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(cert.toString());
    tls.setPrivateKey(key.toString());
    tls.setCaCert(ca.toString());
    return tls;
  }

  private static AccountStartSessionAdmissionProtectionAcquisitionInput input() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "client-guard-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
            19L,
            authorityBundle(preTuple),
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                NAMESPACE,
                TENANT,
                "earth",
                uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
                uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
                "SHARED",
                uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
                uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
                3L,
                tuple.controlPlaneRequestId(),
                "c".repeat(64),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                12L,
                null),
            HOLD_ID,
            HOLD_FENCE);
    return new AccountStartSessionAdmissionProtectionAcquisitionInput(
        tuple.canonicalBytes(), MUTATION, ATTEMPT, 21L, PARTICIPATION, 22L, hold);
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
            "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    return canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence));
  }

  private static byte[] canonical(Object value) {
    try {
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(value));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
