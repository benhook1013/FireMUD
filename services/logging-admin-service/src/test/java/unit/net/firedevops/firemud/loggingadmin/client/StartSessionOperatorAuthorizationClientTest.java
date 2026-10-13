package net.firedevops.firemud.loggingadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class StartSessionOperatorAuthorizationClientTest {
  @TempDir private Path tlsDirectory;

  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID OWNER_ACCOUNT_ID =
      UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("a137588f-4ac6-45ae-984d-5792bdf934b4");
  private static final UUID CLAIM_OWNER = UUID.fromString("5f63c9bb-8488-428d-87fa-b7b9b79a7a8a");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final String CONTROL_UI_TOKEN = "eyJhbGciOiJub25lIn0.operator-secret";
  private static final String LOGGING_WORKLOAD_IDENTITY =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String OPAQUE_REFERENCE = "r".repeat(43);
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void issueAndRecoverUseExactTupleAndClaimIdentityAndReturnValidatedOriginalBytes()
      throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-βeta");
    StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        stub =
            mock(
                StartSessionOperatorAuthorizationServiceGrpc
                    .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    byte[] bundle = bundleBytes(tuple, NOW.plusSeconds(300));
    when(stub.issueHumanOperatorAuthorizationReference(any()))
        .thenReturn(issueResponse(tuple, NOW.plusSeconds(300), bundle));
    when(stub.recoverOperatorAuthorizationReference(any()))
        .thenReturn(recoverResponse(tuple, NOW.plusSeconds(300), bundle));

    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      StartSessionOperatorAuthorizationClient.AuthorizationReference issued =
          client.issueHuman(tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L);
      StartSessionOperatorAuthorizationClient.AuthorizationReference recovered =
          client.recover(tuple, RESERVATION_OWNER, 9L, CLAIM_OWNER, 12L);

      assertThat(issued.operatorAuthorizationReference()).isEqualTo(OPAQUE_REFERENCE);
      assertThat(issued.authorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
      assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(300));
      assertThat(issued.authorityEvidenceBundle()).containsExactly(bundle);
      assertThat(issued.bundleReference().sourceFence()).isEqualTo("24");
      assertThat(issued.authenticatedLoggingWorkloadIdentity())
          .isEqualTo(LOGGING_WORKLOAD_IDENTITY);
      assertThat(issued.decodedBundle().controlPlaneRequestId()).isEqualTo("client-βeta");
      assertThat(issued.postAuthorizationTuple().canonicalBytes())
          .containsExactly(postTupleBytes(tuple, bundle, sharedReference()));
      assertThat(issued.postAuthorizationTuple().bundleReference().sourceFence())
          .isNotEqualTo(issued.postAuthorizationTuple().issuanceFence());
      assertThat(recovered.authorityEvidenceBundle()).containsExactly(bundle);
      assertThat(recovered.operatorAuthorizationReference()).isEqualTo(OPAQUE_REFERENCE);
      assertThat(recovered.postAuthorizationTuple().canonicalBytes())
          .containsExactly(postTupleBytes(tuple, bundle, sharedReference()));
      assertThat(recovered.authenticatedLoggingWorkloadIdentity())
          .isEqualTo(LOGGING_WORKLOAD_IDENTITY);

      byte[] callerBytes = issued.authorityEvidenceBundle();
      callerBytes[0] = (byte) '!';
      assertThat(issued.authorityEvidenceBundle()).containsExactly(bundle);
      assertThat(issued.toString())
          .doesNotContain(CONTROL_UI_TOKEN)
          .doesNotContain(OPAQUE_REFERENCE)
          .doesNotContain(FINGERPRINT);

      var issueCaptor =
          org.mockito.ArgumentCaptor.forClass(
              IssueHumanOperatorAuthorizationReferenceRequest.class);
      verify(stub).issueHumanOperatorAuthorizationReference(issueCaptor.capture());
      assertThat(issueCaptor.getValue().getCanonicalPreAuthorizationTupleBytes().toByteArray())
          .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
      assertThat(issueCaptor.getValue().getControlUiJwt()).isEqualTo(CONTROL_UI_TOKEN);
      assertThat(issueCaptor.getValue().getReservationOwnerId())
          .isEqualTo(RESERVATION_OWNER.toString());
      assertThat(issueCaptor.getValue().getReservationClaimFence()).isEqualTo(9L);
      assertThat(issueCaptor.getValue().getCurrentClaimOwnerId()).isEqualTo(CLAIM_OWNER.toString());
      assertThat(issueCaptor.getValue().getCurrentClaimFence()).isEqualTo(11L);

      var recoverCaptor =
          org.mockito.ArgumentCaptor.forClass(RecoverOperatorAuthorizationReferenceRequest.class);
      verify(stub).recoverOperatorAuthorizationReference(recoverCaptor.capture());
      assertThat(recoverCaptor.getValue().getCanonicalPreAuthorizationTupleBytes().toByteArray())
          .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
      assertThat(recoverCaptor.getValue().getReservationOwnerId())
          .isEqualTo(RESERVATION_OWNER.toString());
      assertThat(recoverCaptor.getValue().getReservationClaimFence()).isEqualTo(9L);
      assertThat(recoverCaptor.getValue().getCurrentClaimOwnerId())
          .isEqualTo(CLAIM_OWNER.toString());
      assertThat(recoverCaptor.getValue().getCurrentClaimFence()).isEqualTo(12L);

      var credentialsCaptor = ArgumentCaptor.forClass(CallCredentials.class);
      verify(stub, times(2)).withCallCredentials(credentialsCaptor.capture());
      assertThat(credentialsCaptor.getAllValues())
          .allSatisfy(
              credentials ->
                  assertThat(credentials)
                      .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class));
      var interceptorCaptor = ArgumentCaptor.forClass(ClientInterceptor[].class);
      verify(stub, times(2)).withInterceptors(interceptorCaptor.capture());
      assertThat(interceptorCaptor.getAllValues())
          .allSatisfy(
              interceptors -> {
                assertThat(interceptors).hasSize(1);
                assertThat(interceptors[0])
                    .isInstanceOf(GrpcServerPeerIdentityClientInterceptor.class);
              });
      assertThat(StartSessionOperatorAuthorizationClient.accountServerUri("world-runtime"))
          .isEqualTo("spiffe://firemud/ns/world-runtime/sa/account-service");
    }
  }

  @Test
  void accountEndpointIsTakenFromServiceEndpointsAndOnlyTheAccountStubIsCalled() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-endpoint");
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    byte[] bundle = bundleBytes(tuple, NOW.plusSeconds(300));
    when(stub.issueHumanOperatorAuthorizationReference(any()))
        .thenReturn(issueResponse(tuple, NOW.plusSeconds(300), bundle));

    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      assertThat(client.accountTarget()).isEqualTo("account-service.operator.svc:6565");
      client.issueHuman(tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L);
      verify(stub).issueHumanOperatorAuthorizationReference(any());
      verify(stub, never()).recoverOperatorAuthorizationReference(any());
    }
  }

  @Test
  void rejectsMalformedUnknownChangedOrExpiredAccountResponses() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-invalid-response");
    byte[] validBundle = bundleBytes(tuple, NOW.plusSeconds(300));
    IssueHumanOperatorAuthorizationReferenceResponse valid =
        issueResponse(tuple, NOW.plusSeconds(300), validBundle);

    assertRejected(tuple, valid.toBuilder().setOperatorAuthorizationReference("bad").build());
    assertRejected(tuple, valid.toBuilder().setAuthorizationReferenceFingerprint("bad").build());
    assertRejected(tuple, valid.toBuilder().clearAuthenticatedLoggingWorkloadIdentity().build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setAuthenticatedLoggingWorkloadIdentity(
                "spiffe://firemud/ns/other/sa/logging-admin-service")
            .build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setAuthenticatedLoggingWorkloadIdentity(
                "spiffe://firemud/ns/world-runtime/sa/game-session-service")
            .build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setAuthenticatedLoggingWorkloadIdentity(
                "SPIFFE://firemud/ns/world-runtime/sa/logging-admin-service")
            .build());
    assertRejected(tuple, valid.toBuilder().setUnknownFields(unknownFields()).build());
    assertRejected(tuple, valid.toBuilder().clearExpiresAt().build());
    assertRejected(tuple, valid.toBuilder().clearBundleReference().build());
    assertRejected(tuple, valid.toBuilder().clearOriginalAccountPostAuthorizationTuple().build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setExpiresAt(
                timestamp(NOW.plusSeconds(300)).toBuilder()
                    .setUnknownFields(unknownFields())
                    .build())
            .build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setBundleReference(
                valid.getBundleReference().toBuilder().setSourceVersion("18").build())
            .build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setOriginalAccountPostAuthorizationTuple(
                ByteString.copyFrom(
                    postTupleBytes(
                        tuple,
                        validBundle,
                        new StartSessionAuthorityEvidenceBundle.BundleReference(
                            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                            "17",
                            "25",
                            "18446744073709551615"))))
            .build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setOriginalAccountPostAuthorizationTuple(
                ByteString.copyFrom(
                    postTupleBytes(
                        tuple,
                        validBundle,
                        new StartSessionAuthorityEvidenceBundle.BundleReference(
                            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                            "17",
                            "24",
                            "18446744073709551614"))))
            .build());
    assertRejected(
        tuple,
        valid.toBuilder()
            .setBundleReference(
                valid.getBundleReference().toBuilder().setUnknownFields(unknownFields()).build())
            .build());
    assertRejected(tuple, valid.toBuilder().setExpiresAt(timestamp(NOW.plusSeconds(301))).build());
    assertRejected(tuple, valid.toBuilder().setExpiresAt(timestamp(NOW.minusSeconds(1))).build());

    RecoverOperatorAuthorizationReferenceResponse validRecovery =
        recoverResponse(tuple, NOW.plusSeconds(300), validBundle);
    assertRecoverRejected(
        tuple, validRecovery.toBuilder().clearAuthenticatedLoggingWorkloadIdentity().build());

    Map<String, Object> changedTupleBundle = read(validBundle);
    object(object(changedTupleBundle.get("issuanceOperationIdentity")))
        .put("mutationDigest", "c".repeat(64));
    assertRejected(
        tuple,
        valid.toBuilder()
            .setAuthorityEvidenceBundle(ByteString.copyFrom(canonical(changedTupleBundle)))
            .build());

    Map<String, Object> extraAssurance = read(validBundle);
    object(extraAssurance.get("issuanceEvidence")).put("assurance", "forbidden");
    assertRejected(
        tuple,
        valid.toBuilder()
            .setAuthorityEvidenceBundle(ByteString.copyFrom(canonical(extraAssurance)))
            .build());
  }

  @Test
  void rejectsInvalidCallerTupleFenceAndTokenBeforeCallingAccount() throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-invalid-request");

    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      assertThatThrownBy(
              () ->
                  client.issueHuman(
                      tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 0L, CLAIM_OWNER, 1L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> client.issueHuman(tuple, "bad token\n", RESERVATION_OWNER, 1L, CLAIM_OWNER, 1L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.recover(tuple, new UUID(0L, 0L), 1L, CLAIM_OWNER, 1L))
          .isInstanceOf(IllegalArgumentException.class);
      verify(stub, never()).issueHumanOperatorAuthorizationReference(any());
      verify(stub, never()).recoverOperatorAuthorizationReference(any());
    }
  }

  @Test
  void refusesIssueAndRecoverWithoutReadableFileBackedMtlsBeforeCallingAccount() throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-missing-mtls");

    CommonGrpcClientProperties plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    CommonGrpcClientProperties missing = new CommonGrpcClientProperties();
    CommonGrpcClientProperties classpath = new CommonGrpcClientProperties();
    classpath.setCertChain("classpath:application.yml");
    classpath.setPrivateKey("classpath:application.yml");
    classpath.setCaCert("classpath:application.yml");
    CommonGrpcClientProperties directoryInsteadOfCertificate = fileBackedTlsProperties();
    directoryInsteadOfCertificate.setCertChain(tlsDirectory.toString());
    CommonGrpcClientProperties incomplete = fileBackedTlsProperties();
    incomplete.setCaCert(null);

    for (CommonGrpcClientProperties invalidTls :
        List.of(plaintext, missing, classpath, directoryInsteadOfCertificate, incomplete)) {
      try (TestClient client = new TestClient(stub, invalidTls)) {
        assertThatThrownBy(
                () ->
                    client.issueHuman(
                        tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.recover(tuple, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L))
            .isInstanceOf(IllegalStateException.class);
      }
    }
    verify(stub, never()).issueHumanOperatorAuthorizationReference(any());
    verify(stub, never()).recoverOperatorAuthorizationReference(any());
  }

  @Test
  void unavailableAccountResponseRemainsUnavailableWithoutCreatingFallback() throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.issueHumanOperatorAuthorizationReference(any()))
        .thenThrow(Status.UNAVAILABLE.withDescription("account unavailable").asRuntimeException());
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-unavailable");

    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      Throwable failure =
          catchThrowable(
              () ->
                  client.issueHuman(
                      tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L));
      assertThat(failure).isInstanceOf(StatusRuntimeException.class);
      assertThat(((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.UNAVAILABLE);
    }
  }

  @Test
  void deniesIssueAndRecoverBeforeAccountUnderSqlOrSynchronization() throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-no-ambient-transaction");

    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(
                () ->
                    client.issueHuman(
                        tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ambient SQL or synchronization");
      } finally {
        TransactionSynchronizationManager.clear();
      }

      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.recover(tuple, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ambient SQL or synchronization");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.clear();
      }

      verify(stub, never()).issueHumanOperatorAuthorizationReference(any());
      verify(stub, never()).recoverOperatorAuthorizationReference(any());
    }
  }

  @Test
  void rejectsAccountReferenceLifetimeAboveItsConfiguredMaximum() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("client-overlong-reference");
    assertRejected(
        tuple,
        issueResponse(tuple, NOW.plusSeconds(301), bundleBytes(tuple, NOW.plusSeconds(301))));
  }

  private void assertRejected(
      StartSessionPreAuthorizationReservationTuple tuple,
      IssueHumanOperatorAuthorizationReferenceResponse response)
      throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.issueHumanOperatorAuthorizationReference(any())).thenReturn(response);
    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      assertThatThrownBy(
              () ->
                  client.issueHuman(
                      tuple, CONTROL_UI_TOKEN, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private void assertRecoverRejected(
      StartSessionPreAuthorizationReservationTuple tuple,
      RecoverOperatorAuthorizationReferenceResponse response)
      throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.recoverOperatorAuthorizationReference(any())).thenReturn(response);
    try (TestClient client = new TestClient(stub, fileBackedTlsProperties())) {
      assertThatThrownBy(() -> client.recover(tuple, RESERVATION_OWNER, 9L, CLAIM_OWNER, 11L))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static IssueHumanOperatorAuthorizationReferenceResponse issueResponse(
      StartSessionPreAuthorizationReservationTuple tuple, Instant expiry, byte[] bundle) {
    return IssueHumanOperatorAuthorizationReferenceResponse.newBuilder()
        .setOperatorAuthorizationReference(OPAQUE_REFERENCE)
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setExpiresAt(timestamp(expiry))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(bundle))
        .setBundleReference(reference())
        .setAuthenticatedLoggingWorkloadIdentity(LOGGING_WORKLOAD_IDENTITY)
        .setOriginalAccountPostAuthorizationTuple(
            ByteString.copyFrom(postTupleBytes(tuple, bundle, sharedReference())))
        .build();
  }

  private static RecoverOperatorAuthorizationReferenceResponse recoverResponse(
      StartSessionPreAuthorizationReservationTuple tuple, Instant expiry, byte[] bundle) {
    return RecoverOperatorAuthorizationReferenceResponse.newBuilder()
        .setOperatorAuthorizationReference(OPAQUE_REFERENCE)
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setExpiresAt(timestamp(expiry))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(bundle))
        .setBundleReference(reference())
        .setAuthenticatedLoggingWorkloadIdentity(LOGGING_WORKLOAD_IDENTITY)
        .setOriginalAccountPostAuthorizationTuple(
            ByteString.copyFrom(postTupleBytes(tuple, bundle, sharedReference())))
        .build();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static AuthorityEvidenceBundleReference reference() {
    return AuthorityEvidenceBundleReference.newBuilder()
        .setBundleVersion(sharedReference().bundleVersion())
        .setSourceVersion(sharedReference().sourceVersion())
        .setSourceFence(sharedReference().sourceFence())
        .setLinearization(sharedReference().linearization())
        .build();
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference sharedReference() {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "24", "18446744073709551615");
  }

  private static byte[] postTupleBytes(
      StartSessionPreAuthorizationReservationTuple tuple,
      byte[] bundle,
      StartSessionAuthorityEvidenceBundle.BundleReference reference) {
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
            tuple, LOGGING_WORKLOAD_IDENTITY, FINGERPRINT, RESERVATION_OWNER, 9L, bundle, reference)
        .canonicalBytes();
  }

  private static byte[] bundleBytes(
      StartSessionPreAuthorizationReservationTuple tuple, Instant expiry) throws Exception {
    return StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
        tuple,
        new Values(
            ISSUANCE_ID,
            "sha256:" + "a".repeat(64),
            "17",
            "CURRENT",
            NOW,
            expiry,
            1L,
            2L,
            3L,
            4L,
            5L,
            23L,
            TOKEN_JTI,
            "2",
            "3"));
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Logging Account client contract");
    return StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR_ID, action);
  }

  private static byte[] canonical(Object value) throws IOException {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static Map<String, Object> read(byte[] bytes) throws IOException {
    return JSON.readValue(bytes, new tools.jackson.core.type.TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static final class TestClient extends StartSessionOperatorAuthorizationClient {
    private final StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        stub;

    private TestClient(
        StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub
            stub,
        CommonGrpcClientProperties tlsProperties)
        throws Exception {
      super(
          endpoints(),
          tlsProperties,
          stubChannelFactory(),
          BlockingGrpcStubCustomizer.noop(),
          Clock.fixed(NOW, ZoneOffset.UTC));
      this.stub = stub;
      when(stub.withCallCredentials(any(CallCredentials.class))).thenReturn(stub);
      when(stub.withInterceptors(any(ClientInterceptor[].class))).thenReturn(stub);
      String reloadSetting = System.getProperty("firemud.grpc.tls-reload.enabled");
      System.setProperty("firemud.grpc.tls-reload.enabled", "false");
      try {
        initialize();
      } finally {
        if (reloadSetting == null) {
          System.clearProperty("firemud.grpc.tls-reload.enabled");
        } else {
          System.setProperty("firemud.grpc.tls-reload.enabled", reloadSetting);
        }
      }
    }

    @Override
    protected StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        buildStub(ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }

    private String accountTarget() {
      return configuredTarget(endpoints());
    }
  }

  private static ServiceEndpointsProperties endpoints() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account-service.operator.svc:6565");
    return endpoints;
  }

  private CommonGrpcClientProperties fileBackedTlsProperties() throws IOException {
    Path certChain = tlsDirectory.resolve("client.crt");
    Path privateKey = tlsDirectory.resolve("client.key");
    Path caCert = tlsDirectory.resolve("ca.crt");
    Files.writeString(certChain, "test-only certificate placeholder");
    Files.writeString(privateKey, "test-only private-key placeholder");
    Files.writeString(caCert, "test-only CA placeholder");
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(certChain.toString());
    properties.setPrivateKey(privateKey.toString());
    properties.setCaCert(caCert.toString());
    return properties;
  }

  private static GrpcChannelFactory stubChannelFactory() {
    ManagedChannel channel = mock(ManagedChannel.class);
    return new GrpcChannelFactory() {
      @Override
      public ManagedChannel buildChannel(
          String target,
          int defaultPort,
          CommonGrpcClientProperties properties,
          boolean keepAlive) {
        return channel;
      }
    };
  }
}
