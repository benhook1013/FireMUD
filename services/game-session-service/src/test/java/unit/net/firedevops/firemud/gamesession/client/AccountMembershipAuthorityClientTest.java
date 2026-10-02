package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AccountMembershipAuthorityClientTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000101";
  private static final String TENANT_ID = "00000000-0000-0000-0000-000000000005";
  private static final String REALM_ID = "00000000-0000-4000-8000-000000000201";
  private static final String PLAYABLE_NAMESPACE_ID = "00000000-0000-4000-8000-000000000301";
  private static final String GAME_INSTANCE_ID = "00000000-0000-4000-8000-000000000401";
  private static final String REQUEST_ID = "session-7:attempt α";

  @Test
  void sendsCompleteTypedContextWithBoundedDeadlineAndReturnsVerifiedResponse() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub = mockStub();
    GetTenantMembershipForRuntimeResponse response = membershipResponse();
    when(stub.getTenantMembershipForRuntime(any())).thenReturn(response);
    AccountMembershipAuthorityClient client = newClient(stub);

    GetTenantMembershipForRuntimeResponse verified =
        client.getTenantMembershipForRuntime(playerContext());

    assertThat(verified).isEqualTo(response);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    ArgumentCaptor<GetTenantMembershipForRuntimeRequest> requestCaptor =
        ArgumentCaptor.forClass(GetTenantMembershipForRuntimeRequest.class);
    verify(stub).getTenantMembershipForRuntime(requestCaptor.capture());
    assertThat(requestCaptor.getValue().hasPlayerContext()).isTrue();
    assertThat(requestCaptor.getValue().getPlayerContext()).isEqualTo(playerContext());
  }

  @Test
  void returnsValidInactiveAndMissingSnapshotsWithoutTreatingMissingAsAdmission() throws Exception {
    GetTenantMembershipForRuntimeResponse inactive =
        bindToRequest(RuntimeMembershipTestFixtures.inactive(ACCOUNT_ID, 5L, "3"));
    GetTenantMembershipForRuntimeResponse missing =
        bindToRequest(RuntimeMembershipTestFixtures.missing(ACCOUNT_ID, 5L));
    AccountServiceGrpc.AccountServiceBlockingStub stub = mockStub();
    when(stub.getTenantMembershipForRuntime(any())).thenReturn(inactive, missing);
    AccountMembershipAuthorityClient client = newClient(stub);

    GetTenantMembershipForRuntimeResponse verifiedInactive =
        client.getTenantMembershipForRuntime(playerContext());
    GetTenantMembershipForRuntimeResponse verifiedMissing =
        client.getTenantMembershipForRuntime(playerContext());

    assertThat(verifiedInactive.getMembershipLifecycleState()).isEqualTo("INACTIVE");
    assertThat(verifiedMissing.getMembershipLifecycleState()).isEqualTo("MISSING");
    assertThat(verifiedMissing.getMembershipExists()).isFalse();
    assertThat(verifiedMissing.getGameplayAdmissionAllowed()).isFalse();
  }

  @Test
  void acceptsCanonicalUuidTargetSelectorsAndOptionalCharacterButRejectsInvalidContextBeforeStub()
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountMembershipAuthorityClient client = newClientWithoutStub(channelFactory);

    assertThatThrownBy(() -> client.getTenantMembershipForRuntime(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertInvalidContext(client, playerContext().toBuilder().setGameInstanceId("7").build());
    assertInvalidContext(client, playerContext().toBuilder().setSessionId("07").build());
    assertInvalidContext(client, playerContext().toBuilder().setCharacterId("-1").build());
    assertInvalidContext(
        client, playerContext().toBuilder().setPlayableStateScope("UNSPECIFIED").build());
    assertInvalidContext(client, playerContext().toBuilder().setRealmId("5").build());
    assertInvalidContext(
        client,
        playerContext().toBuilder().setAccountId("00000000-0000-0000-0000-000000000000").build());
    assertInvalidContext(
        client,
        playerContext().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build());
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsEveryRecipientRequestAvailabilityFreshnessAndContentDeviation() throws Exception {
    GetTenantMembershipForRuntimeResponse valid = membershipResponse();
    assertResponseRejected(valid.toBuilder().setAccountId(REALM_ID).build());
    assertResponseRejected(valid.toBuilder().setTenantId(REALM_ID).build());
    assertResponseRejected(valid.toBuilder().setRequestAccountId(REALM_ID).build());
    assertResponseRejected(valid.toBuilder().setRequestTenantId(REALM_ID).build());
    assertResponseRejected(valid.toBuilder().setRequestId("another-operation").build());
    assertResponseRejected(valid.toBuilder().setAuthorityAvailability("UNAVAILABLE").build());
    assertResponseRejected(
        valid.toBuilder()
            .setError(ErrorDetail.newBuilder().setCode("AUTH_UNAVAILABLE").build())
            .build());
    assertResponseRejected(
        valid.toBuilder().setEvaluatedAt(Instant.now().minusSeconds(20).toString()).build());
    assertResponseRejected(
        valid.toBuilder().setEvaluatedAt(Instant.now().plusSeconds(2).toString()).build());
    assertResponseRejected(valid.toBuilder().clearOutboxSourceEvidence().build());
    assertResponseRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build());
    assertResponseRejected(
        valid.toBuilder()
            .setOutboxCheckpoints(
                0,
                valid.getOutboxCheckpoints(0).toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                            .build()))
            .build());
  }

  @Test
  void rejectsAccountErrorsAndUnavailableTransportWithoutReturningContent() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub errorStub = mockStub();
    when(errorStub.getTenantMembershipForRuntime(any()))
        .thenReturn(membershipResponse().toBuilder().setAuthorityAvailability("UNKNOWN").build());
    AccountMembershipAuthorityClient errorClient = newClient(errorStub);
    assertThatThrownBy(() -> errorClient.getTenantMembershipForRuntime(playerContext()))
        .isInstanceOf(IllegalStateException.class);

    AccountServiceGrpc.AccountServiceBlockingStub unavailableStub = mockStub();
    when(unavailableStub.getTenantMembershipForRuntime(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    AccountMembershipAuthorityClient unavailableClient = newClient(unavailableStub);
    assertThatThrownBy(() -> unavailableClient.getTenantMembershipForRuntime(playerContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void constructorRequiresFileBackedWorkloadMtlsAndExplicitInitialization() throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    CommonGrpcClientProperties classpath = mtlsProperties();
    classpath.setPrivateKey("classpath:client.key");
    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    CommonGrpcClientProperties incomplete = mtlsProperties();
    incomplete.setCaCert("");
    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityClient(
                    new ServiceEndpointsProperties(),
                    incomplete,
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityClient(
                    new ServiceEndpointsProperties(),
                    mtlsProperties(),
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    "bad/namespace"))
        .isInstanceOf(IllegalArgumentException.class);

    AccountMembershipAuthorityClient client = newClientWithoutStub(channelFactory);
    verifyNoInteractions(channelFactory);
    assertThatThrownBy(() -> client.getTenantMembershipForRuntime(playerContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void explicitInitUsesConfiguredAccountTargetAndFileBackedMtls(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    CommonGrpcClientProperties tls = fileBackedMtlsProperties(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    AccountMembershipAuthorityClient client =
        new AccountMembershipAuthorityClient(
            endpoints, tls, channelFactory, BlockingGrpcStubCustomizer.noop(), NAMESPACE);
    try {
      verifyNoInteractions(channelFactory);
      client.init();
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

  @Test
  void installedPeerInterceptorRejectsMissingAndWrongAccountServerIdentity() throws Exception {
    AccountMembershipAuthorityClient client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    for (SSLSession session :
        new SSLSession[] {
          null,
          peerSession("game-design-service"),
          peerSession("other-namespace", "account-service")
        }) {
      ManagedChannel channel = mock(ManagedChannel.class);
      Attributes attributes =
          session == null
              ? Attributes.EMPTY
              : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
      when(channel.newCall(any(), any())).thenAnswer(invocation -> immediateCloseCall(attributes));
      var stub = client.buildStub(channel);

      assertThatThrownBy(
              () ->
                  stub.getTenantMembershipForRuntime(
                      GetTenantMembershipForRuntimeRequest.newBuilder()
                          .setPlayerContext(playerContext())
                          .build()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(exception -> ((StatusRuntimeException) exception).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
    }
  }

  private static void assertInvalidContext(
      AccountMembershipAuthorityClient client, PlayerExecutionContext context) {
    assertThatThrownBy(() -> client.getTenantMembershipForRuntime(context))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertResponseRejected(GetTenantMembershipForRuntimeResponse response)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub = mockStub();
    when(stub.getTenantMembershipForRuntime(any())).thenReturn(response);
    AccountMembershipAuthorityClient client = newClient(stub);
    assertThatThrownBy(() -> client.getTenantMembershipForRuntime(playerContext()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GetTenantMembershipForRuntimeResponse membershipResponse() {
    return bindToRequest(RuntimeMembershipTestFixtures.active(ACCOUNT_ID, 5L, TENANT_ID, "1"));
  }

  private static GetTenantMembershipForRuntimeResponse bindToRequest(
      GetTenantMembershipForRuntimeResponse response) {
    return response.toBuilder()
        .setRequestAccountId(ACCOUNT_ID)
        .setRequestTenantId(TENANT_ID)
        .setRequestId(REQUEST_ID)
        .setAuthorityAvailability("AVAILABLE")
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  private static PlayerExecutionContext playerContext() {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(ACCOUNT_ID)
        .setTenantId(TENANT_ID)
        .setRealmId(REALM_ID)
        .setPlayableStateNamespaceId(PLAYABLE_NAMESPACE_ID)
        .setPlayableStateScope("SHARED")
        .setGameInstanceId(GAME_INSTANCE_ID)
        .setCharacterId("")
        .setSessionId("7")
        .setRequestId(REQUEST_ID)
        .build();
  }

  private static AccountMembershipAuthorityClient newClient(
      AccountServiceGrpc.AccountServiceBlockingStub stub) throws Exception {
    AccountMembershipAuthorityClient client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static AccountMembershipAuthorityClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new AccountMembershipAuthorityClient(
        new ServiceEndpointsProperties(),
        mtlsProperties(),
        channelFactory,
        BlockingGrpcStubCustomizer.noop(),
        NAMESPACE);
  }

  private static AccountServiceGrpc.AccountServiceBlockingStub mockStub() {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/account-ca.crt");
    return tls;
  }

  private static CommonGrpcClientProperties fileBackedMtlsProperties(Path directory)
      throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.createFile(directory.resolve("game-session-client.crt")).toString());
    tls.setPrivateKey(Files.createFile(directory.resolve("game-session-client.key")).toString());
    tls.setCaCert(Files.createFile(directory.resolve("account-ca.crt")).toString());
    return tls;
  }

  private static ClientCall<Object, Object> immediateCloseCall(Attributes attributes) {
    return new ClientCall<>() {
      private Listener<Object> listener;

      @Override
      public void start(Listener<Object> callListener, Metadata headers) {
        listener = callListener;
      }

      @Override
      public void request(int numMessages) {}

      @Override
      public void cancel(String message, Throwable cause) {}

      @Override
      public void halfClose() {
        listener.onClose(Status.OK, new Metadata());
      }

      @Override
      public void sendMessage(Object message) {}

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setMessageCompression(boolean enabled) {}

      @Override
      public Attributes getAttributes() {
        return attributes;
      }
    };
  }

  private static SSLSession peerSession(String service) throws Exception {
    return peerSession(NAMESPACE, service);
  }

  private static SSLSession peerSession(String namespace, String service) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/" + namespace + "/sa/" + service)));
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    return session;
  }
}
