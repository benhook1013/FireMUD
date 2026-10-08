package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyServiceGrpc;
import org.junit.jupiter.api.Test;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

class GameDesignPublishedRealmPolicyClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void returnsCompleteTypedEvidenceAndSendsExactRequestWithDeadline() throws Exception {
    var evidence = evidence();
    var stub = mockStub();
    when(stub.listPublishedRealmEntryPolicies(any()))
        .thenAnswer(
            invocation -> {
              var request =
                  PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                      invocation.getArgument(0, ListPublishedRealmEntryPoliciesRequest.class));
              return PublishedRealmEntryPolicyReadGrpcCodec.toResponse(request, evidence);
            });
    var client = newClient(stub);

    PublishedRealmEntryPolicySetEvidence result =
        client.listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID);

    assertThat(result).isEqualTo(evidence);
    assertThat(result.hasValidDigest()).isTrue();
    assertThat(result.policies()).hasSize(2);
    var requestCaptor =
        org.mockito.ArgumentCaptor.forClass(ListPublishedRealmEntryPoliciesRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).listPublishedRealmEntryPolicies(requestCaptor.capture());
    var exactRequest = requestCaptor.getValue();
    assertThat(exactRequest.getSchemaVersion()).isEqualTo(1);
    assertThat(exactRequest.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(UUID.fromString(exactRequest.getReadRequestId()).toString())
        .isEqualTo(exactRequest.getReadRequestId());
    assertThat(exactRequest.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(exactRequest.getCanonicalVersionId()).isEqualTo(VERSION_ID.toString());
  }

  @Test
  void rejectsMalformedCanonicalTenantOrVersionBeforeStubUse() {
    var channelFactory = mock(GrpcChannelFactory.class);
    var client = newClientWithoutStub(channelFactory);

    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies(null, VERSION_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies(TENANT_ID, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies(new UUID(0L, 0L), VERSION_ID))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsUninitializedClientAndPropagatesUnavailableOwner() throws Exception {
    var channelFactory = mock(GrpcChannelFactory.class);
    var uninitialized = newClientWithoutStub(channelFactory);
    assertThatThrownBy(() -> uninitialized.listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);

    var stub = mockStub();
    StatusRuntimeException unavailable =
        Status.UNAVAILABLE.withDescription("Game Design unavailable").asRuntimeException();
    when(stub.listPublishedRealmEntryPolicies(any())).thenThrow(unavailable);
    var client = newClient(stub);
    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID))
        .isSameAs(unavailable);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsChangedRequestUnknownFieldsAndMalformedClosedEvidence() throws Exception {
    var expectedEvidence = evidence();
    var stub = mockStub();
    when(stub.listPublishedRealmEntryPolicies(any()))
        .thenAnswer(
            invocation -> {
              ListPublishedRealmEntryPoliciesRequest request = invocation.getArgument(0);
              return ListPublishedRealmEntryPoliciesResponse.newBuilder()
                  .setRequest(
                      request.toBuilder().setCanonicalVersionId(TENANT_ID.toString()).build())
                  .setPolicySetEvidence(ByteString.copyFrom(expectedEvidence.canonicalBytes()))
                  .build();
            });
    assertThatThrownBy(() -> newClient(stub).listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("response is invalid");

    doAnswer(
            invocation -> {
              var request = (ListPublishedRealmEntryPoliciesRequest) invocation.getArgument(0);
              return ListPublishedRealmEntryPoliciesResponse.newBuilder()
                  .setRequest(request)
                  .setPolicySetEvidence(ByteString.copyFrom(new byte[] {1, 2, 3}))
                  .build();
            })
        .when(stub)
        .listPublishedRealmEntryPolicies(any());
    assertThatThrownBy(() -> newClient(stub).listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID))
        .isInstanceOf(IllegalStateException.class);

    doAnswer(
            invocation -> {
              var request = (ListPublishedRealmEntryPoliciesRequest) invocation.getArgument(0);
              return ListPublishedRealmEntryPoliciesResponse.newBuilder()
                  .setRequest(request)
                  .setPolicySetEvidence(ByteString.copyFrom(expectedEvidence.canonicalBytes()))
                  .setUnknownFields(
                      UnknownFieldSet.newBuilder()
                          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                          .build())
                  .build();
            })
        .when(stub)
        .listPublishedRealmEntryPolicies(any());
    assertThatThrownBy(() -> newClient(stub).listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsResponseFromDifferentMtlsWorkloadNamespace() throws Exception {
    ManagedChannel channel = mock(ManagedChannel.class);
    SSLSession sslSession = mock(SSLSession.class);
    X509Certificate peerCertificate = mock(X509Certificate.class);
    when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {peerCertificate});
    when(peerCertificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/other/sa/game-design-service")));
    Attributes attributes =
        Attributes.newBuilder().set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    @SuppressWarnings("unchecked")
    ClientCall<ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
        call = mock(ClientCall.class);
    when(call.getAttributes()).thenReturn(attributes);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ClientCall.Listener<ListPublishedRealmEntryPoliciesResponse> listener =
                  invocation.getArgument(0);
              var request =
                  PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                      ListPublishedRealmEntryPoliciesRequest.newBuilder()
                          .setSchemaVersion(1)
                          .setTargetNamespace(NAMESPACE)
                          .setReadRequestId(UUID.randomUUID().toString())
                          .setCanonicalTenantId(TENANT_ID.toString())
                          .setCanonicalVersionId(VERSION_ID.toString())
                          .build());
              listener.onMessage(
                  PublishedRealmEntryPolicyReadGrpcCodec.toResponse(request, evidence()));
              listener.onClose(Status.OK, new Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel
            .<ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
                newCall(any(), any()))
        .thenReturn(call);

    var client = newClientWithChannel(channel);
    assertThatThrownBy(() -> client.listPublishedRealmEntryPolicies(TENANT_ID, VERSION_ID))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
  }

  @Test
  void rejectsPlaintextIncompleteClasspathTlsAndInvalidNamespaceBeforeChannelCreation() {
    var channelFactory = mock(GrpcChannelFactory.class);
    var plaintext = mtlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), null, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    var classpath = mtlsProperties();
    classpath.setPrivateKey("classpath:certs/client.key");
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), classpath, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
    assertThatThrownBy(
            () ->
                new GameDesignPublishedRealmPolicyClient(
                    new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, "bad.ns"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");
    verifyNoInteractions(channelFactory);
  }

  private static PublishedRealmEntryPolicySetEvidence evidence() {
    return PublishedRealmPolicyEvidenceFixture.fixture(
            PublishedRealmPolicyEvidenceFixture.policy("main", true, true, "SHARED"),
            PublishedRealmPolicyEvidenceFixture.policy("playtest", true, false, "ISOLATED"))
        .set();
  }

  private static GameDesignPublishedRealmPolicyClient newClient(
      PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceBlockingStub stub)
      throws Exception {
    var client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static GameDesignPublishedRealmPolicyClient newClientWithChannel(ManagedChannel channel)
      throws Exception {
    var client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    var buildStub =
        GameDesignPublishedRealmPolicyClient.class.getDeclaredMethod(
            "buildStub", ManagedChannel.class);
    buildStub.setAccessible(true);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, buildStub.invoke(client, channel));
    return client;
  }

  private static GameDesignPublishedRealmPolicyClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new GameDesignPublishedRealmPolicyClient(
        new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);
  }

  private static PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceBlockingStub
      mockStub() {
    var stub =
        mock(
            PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceBlockingStub
                .class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/game-design-ca.crt");
    return tls;
  }
}
