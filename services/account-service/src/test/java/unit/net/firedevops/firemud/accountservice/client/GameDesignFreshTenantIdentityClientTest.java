package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GameDesignFreshTenantIdentityClientTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER_REQUEST_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final long SOURCE_GAME_ROW_ID = 91L;
  private static final String SOURCE_GAME_TENANT_KEY = "fresh-owner-key-91";
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void exactResponseIsValidatedAndStableOnReplayWithoutCallingRetainedRpcs() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveFreshTenantCreation(any())).thenReturn(validResponse());
    GameDesignFreshTenantIdentityClient client = newClient(stub);

    FreshTenantCreationEvidence first = client.resolveCreation(REQUEST_ID, REQUEST_DIGEST);
    FreshTenantCreationEvidence retry = client.resolveCreation(REQUEST_ID, REQUEST_DIGEST);

    assertThat(first).isEqualTo(retry);
    assertThat(first.schemaVersion()).isEqualTo(1);
    assertThat(first.targetNamespace()).isEqualTo("test");
    assertThat(first.creationRequestId()).isEqualTo(REQUEST_ID);
    assertThat(first.operationId()).isEqualTo(OPERATION_ID);
    assertThat(first.requestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(first.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(first.sourceGameRowId()).isEqualTo(SOURCE_GAME_ROW_ID);
    assertThat(first.sourceGameTenantKey()).isEqualTo(SOURCE_GAME_TENANT_KEY);
    assertThat(first.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(first.evidenceDigest()).isEqualTo(validResponse().getEvidenceDigest());

    ArgumentCaptor<ResolveFreshTenantCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(ResolveFreshTenantCreationRequest.class);
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub, times(2)).resolveFreshTenantCreation(requestCaptor.capture());
    assertThat(requestCaptor.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getCreationRequestId()).isEqualTo(REQUEST_ID.toString());
              assertThat(request.getExpectedRequestDigest()).isEqualTo(REQUEST_DIGEST);
            });
    verify(stub, never()).resolveLegacyAccountTenantAssociation(any());
    verify(stub, never()).resolveLegacyGameTenantIdentity(any());
  }

  @Test
  void rejectsResponseThatDoesNotBindTheConfiguredNamespaceRequestAndExpectedDigest()
      throws Exception {
    assertRejected(validResponse("other", REQUEST_ID, REQUEST_DIGEST, SOURCE_GAME_ROW_ID));
    assertRejected(validResponse("test", OTHER_REQUEST_ID, REQUEST_DIGEST, SOURCE_GAME_ROW_ID));
    assertRejected(
        validResponse("test", REQUEST_ID, "sha256:" + "b".repeat(64), SOURCE_GAME_ROW_ID));
  }

  @Test
  void rejectsChangedSourceRowCorruptDigestUnsupportedSchemaAndWrongProvenance() throws Exception {
    ResolveFreshTenantCreationResponse valid = validResponse();
    assertRejected(valid.toBuilder().setSourceGameRowId(SOURCE_GAME_ROW_ID + 1).build());
    assertRejected(valid.toBuilder().setEvidenceDigest("sha256:" + "d".repeat(64)).build());
    assertRejected(valid.toBuilder().setSchemaVersion(2).build());
    assertRejected(valid.toBuilder().setProvenanceKind("RETAINED_GAME_V30").build());
    assertRejected(valid.toBuilder().setOperationId("22222222-2222-4222-8222-22222222222").build());
    assertRejected(valid.toBuilder().setCanonicalTenantId(NIL_UUID.toString()).build());
  }

  @Test
  void rejectsMalformedSelectorsBeforeAnyRpc() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    GameDesignFreshTenantIdentityClient client = newClient(stub);

    assertThatThrownBy(() -> client.resolveCreation(null, REQUEST_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.resolveCreation(NIL_UUID, REQUEST_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, "SHA256:" + "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, "bad-digest"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(stub);
  }

  @Test
  void rejectsPlaintextAndIncompleteAccountMtlsConfigurationBeforeChannelCreation() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GameDesignFreshTenantIdentityClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    assertThatThrownBy(
            () ->
                new GameDesignFreshTenantIdentityClient(
                    new ServiceEndpointsProperties(), null, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    List<CommonGrpcClientProperties> incompleteConfigurations =
        List.of(
            incompleteMtlsProperties(null, "certs/account-client.key", "certs/game-design-ca.crt"),
            incompleteMtlsProperties("certs/account-client.crt", null, "certs/game-design-ca.crt"),
            incompleteMtlsProperties("certs/account-client.crt", "certs/account-client.key", null));
    for (CommonGrpcClientProperties incomplete : incompleteConfigurations) {
      assertThatThrownBy(
              () ->
                  new GameDesignFreshTenantIdentityClient(
                      new ServiceEndpointsProperties(), incomplete, channelFactory, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("certificate, key, and CA");
    }

    CommonGrpcClientProperties classpathMaterial = mtlsProperties();
    classpathMaterial.setCertChain("classpath:certs/account-client.crt");
    assertThatThrownBy(
            () ->
                new GameDesignFreshTenantIdentityClient(
                    new ServiceEndpointsProperties(), classpathMaterial, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void transportFailureIsPropagatedAndUsesTheBoundedDeadline() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Game Design unavailable"));
    when(stub.resolveFreshTenantCreation(any())).thenThrow(unavailable);
    GameDesignFreshTenantIdentityClient client = newClient(stub);

    assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
        .isSameAs(unavailable);

    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void initUsesTheSharedFileBackedTlsChannelForTheConfiguredGameDesignEndpoint(
      @TempDir Path directory) throws Exception {
    CommonGrpcClientProperties tls = mtlsProperties(directory);
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design-service.internal:6565");
    GrpcChannelFactory channelFactory = spy(new GrpcChannelFactory());
    GameDesignFreshTenantIdentityClient client =
        new GameDesignFreshTenantIdentityClient(endpoints, tls, channelFactory, "test");
    try {
      // This creates the common TLS channel but does not perform a live peer-certificate handshake.
      client.init();

      verify(channelFactory)
          .buildChannel(
              eq("game-design-service.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static void assertRejected(ResolveFreshTenantCreationResponse response) throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveFreshTenantCreation(any())).thenReturn(response);
    GameDesignFreshTenantIdentityClient client = newClient(stub);

    assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GameDesignFreshTenantIdentityClient newClient(
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub) throws Exception {
    GameDesignFreshTenantIdentityClient client =
        new GameDesignFreshTenantIdentityClient(
            new ServiceEndpointsProperties(),
            mtlsProperties(),
            mock(GrpcChannelFactory.class),
            "test");
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub mockStub() {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub =
        mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ResolveFreshTenantCreationResponse validResponse() {
    return validResponse("test", REQUEST_ID, REQUEST_DIGEST, SOURCE_GAME_ROW_ID);
  }

  private static ResolveFreshTenantCreationResponse validResponse(
      String namespace, UUID requestId, String requestDigest, long sourceGameRowId) {
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            requestId,
            OPERATION_ID,
            requestDigest,
            CANONICAL_TENANT_ID,
            sourceGameRowId,
            SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW");
    return ResolveFreshTenantCreationResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(namespace)
        .setCreationRequestId(requestId.toString())
        .setOperationId(OPERATION_ID.toString())
        .setRequestDigest(requestDigest)
        .setCanonicalTenantId(CANONICAL_TENANT_ID.toString())
        .setSourceGameRowId(sourceGameRowId)
        .setSourceGameTenantKey(SOURCE_GAME_TENANT_KEY)
        .setProvenanceKind("NEW_GAME_ROW")
        .setEvidenceDigest(evidenceDigest)
        .build();
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/account-client.crt");
    tls.setPrivateKey("certs/account-client.key");
    tls.setCaCert("certs/gamedesign-server-ca.crt");
    return tls;
  }

  private static CommonGrpcClientProperties incompleteMtlsProperties(
      String certificate, String privateKey, String caCertificate) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(copyCertificateResource("dev-cert.pem", directory).toString());
    tls.setPrivateKey(copyCertificateResource("dev-key.pem", directory).toString());
    tls.setCaCert(copyCertificateResource("dev-ca.pem", directory).toString());
    return tls;
  }

  private static Path copyCertificateResource(String fileName, Path directory) throws Exception {
    Path destination = directory.resolve(fileName);
    try (InputStream source =
        GameDesignFreshTenantIdentityClientTest.class.getResourceAsStream("/certs/" + fileName)) {
      if (source == null) {
        throw new IllegalStateException("Missing shared TLS test resource: " + fileName);
      }
      Files.copy(source, destination);
    }
    return destination;
  }
}
