package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityRequest;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityResponse;
import net.firedevops.firemud.account.v1.RuntimeAccountIdentityServiceGrpc;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.AccountRuntimeIdentityClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AccountRuntimeIdentityClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final long SOURCE_ROW_ID = 73L;

  @Test
  void forwardsExactCanonicalRequestAndReturnsTypedOwnerEvidence() throws Exception {
    RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveRuntimeAccountIdentity(any()))
        .thenReturn(validResponse("ACCOUNT_REPOSITORY_INSERT"));
    AccountRuntimeIdentityClient client = newClient(stub);

    RuntimeAccountIdentityEvidence evidence =
        client.resolveRuntimeAccountIdentity(ACCOUNT_ID.toString(), REQUEST_ID.toString());

    assertThat(evidence.schemaVersion()).isEqualTo(1);
    assertThat(evidence.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(evidence.requestId()).isEqualTo(REQUEST_ID);
    assertThat(evidence.canonicalAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(evidence.sourceAccountRowId()).isEqualTo(SOURCE_ROW_ID);
    assertThat(evidence.accountUuidProvenance()).isEqualTo("ACCOUNT_REPOSITORY_INSERT");

    ArgumentCaptor<ResolveRuntimeAccountIdentityRequest> requestCaptor =
        ArgumentCaptor.forClass(ResolveRuntimeAccountIdentityRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).resolveRuntimeAccountIdentity(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getCanonicalAccountId()).isEqualTo(ACCOUNT_ID.toString());
    assertThat(requestCaptor.getValue().getRequestId()).isEqualTo(REQUEST_ID.toString());
  }

  @Test
  void rejectsMalformedInputsBeforeStubOrChannelUse() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountRuntimeIdentityClient client = newClientWithoutStub(channelFactory);
    List<String[]> invalidRequests =
        List.of(
            new String[] {null, REQUEST_ID.toString()},
            new String[] {"", REQUEST_ID.toString()},
            new String[] {"AB426BB3-A733-43F0-9C8E-2E379CBDF7EC", REQUEST_ID.toString()},
            new String[] {"11111111-1111-4111-8111-11111111111", REQUEST_ID.toString()},
            new String[] {"00000000-0000-0000-0000-000000000000", REQUEST_ID.toString()},
            new String[] {ACCOUNT_ID.toString(), null},
            new String[] {ACCOUNT_ID.toString(), ""},
            new String[] {ACCOUNT_ID.toString(), "22222222-2222-4222-8222-22222222222"},
            new String[] {ACCOUNT_ID.toString(), "00000000-0000-0000-0000-000000000000"});

    for (String[] invalid : invalidRequests) {
      assertThatThrownBy(() -> client.resolveRuntimeAccountIdentity(invalid[0], invalid[1]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsUninitializedClientAndAbsentOwnerResponseWithoutActivation() throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountRuntimeIdentityClient uninitialized = newClientWithoutStub(channelFactory);
    assertThatThrownBy(
            () ->
                uninitialized.resolveRuntimeAccountIdentity(
                    ACCOUNT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);

    RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveRuntimeAccountIdentity(any())).thenReturn(null);
    AccountRuntimeIdentityClient client = newClient(stub);
    assertThatThrownBy(
            () ->
                client.resolveRuntimeAccountIdentity(ACCOUNT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("absent");
  }

  @Test
  void rejectsSchemaMismatchAndEveryResponseFieldOutsideTheExactRequest() throws Exception {
    ResolveRuntimeAccountIdentityResponse valid = validResponse("ACCOUNT_V29_MIGRATION");
    assertRejected(valid.toBuilder().setSchemaVersion(2).build());
    assertRejected(valid.toBuilder().setTargetNamespace("other").build());
    assertRejected(valid.toBuilder().setTargetNamespace("").build());
    assertRejected(
        valid.toBuilder().setCanonicalAccountId("AB426BB3-A733-43F0-9C8E-2E379CBDF7EC").build());
    assertRejected(
        valid.toBuilder().setCanonicalAccountId("33333333-3333-4333-8333-333333333333").build());
    assertRejected(
        valid.toBuilder().setCanonicalAccountId("00000000-0000-0000-0000-000000000000").build());
    assertRejected(valid.toBuilder().setRequestId("33333333-3333-4333-8333-333333333333").build());
    assertRejected(valid.toBuilder().setRequestId("22222222-2222-4222-8222-22222222222").build());
    assertRejected(valid.toBuilder().setRequestId("00000000-0000-0000-0000-000000000000").build());
    assertRejected(valid.toBuilder().setSourceAccountRowId(0L).build());
    assertRejected(valid.toBuilder().setAccountUuidProvenance("").build());
    assertRejected(valid.toBuilder().setAccountUuidProvenance("UNKNOWN").build());
    assertRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build());
  }

  @Test
  void propagatesTimeoutAndProviderFailuresWithFiniteDeadline() throws Exception {
    RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub stub = mockStub();
    StatusRuntimeException timeout =
        new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Account timed out"));
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Account unavailable"));
    when(stub.resolveRuntimeAccountIdentity(any())).thenThrow(timeout).thenThrow(unavailable);
    AccountRuntimeIdentityClient client = newClient(stub);

    assertThatThrownBy(
            () ->
                client.resolveRuntimeAccountIdentity(ACCOUNT_ID.toString(), REQUEST_ID.toString()))
        .isSameAs(timeout);
    assertThatThrownBy(
            () ->
                client.resolveRuntimeAccountIdentity(ACCOUNT_ID.toString(), REQUEST_ID.toString()))
        .isSameAs(unavailable);

    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsPlaintextMissingClasspathTlsAndInvalidNamespaceBeforeChannelCreation() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new AccountRuntimeIdentityClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new AccountRuntimeIdentityClient(
                    new ServiceEndpointsProperties(), null, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    for (CommonGrpcClientProperties incomplete :
        List.of(
            incompleteMtlsProperties(null, "entity.key", "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", null, "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", "entity.key", null))) {
      assertThatThrownBy(
              () ->
                  new AccountRuntimeIdentityClient(
                      new ServiceEndpointsProperties(), incomplete, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("certificate, key, and CA");
    }

    for (CommonGrpcClientProperties classpath :
        List.of(
            incompleteMtlsProperties("classpath:entity.crt", "entity.key", "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", "classpath:entity.key", "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", "entity.key", "classpath:account-ca.crt"))) {
      assertThatThrownBy(
              () ->
                  new AccountRuntimeIdentityClient(
                      new ServiceEndpointsProperties(), classpath, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("file-backed");
    }

    assertThatThrownBy(
            () ->
                new AccountRuntimeIdentityClient(
                    new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, "bad.ns"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initUsesConfiguredAccountTargetAndFileBackedTls(@TempDir Path directory) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    CommonGrpcClientProperties tls = mtlsProperties(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    AccountRuntimeIdentityClient client =
        new AccountRuntimeIdentityClient(endpoints, tls, channelFactory, NAMESPACE);

    verifyNoInteractions(channelFactory);
    try {
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

  private static void assertRejected(ResolveRuntimeAccountIdentityResponse response)
      throws Exception {
    RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveRuntimeAccountIdentity(any())).thenReturn(response);
    AccountRuntimeIdentityClient client = newClient(stub);

    assertThatThrownBy(
            () ->
                client.resolveRuntimeAccountIdentity(ACCOUNT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AccountRuntimeIdentityClient newClient(
      RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub stub)
      throws Exception {
    AccountRuntimeIdentityClient client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static AccountRuntimeIdentityClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new AccountRuntimeIdentityClient(
        new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);
  }

  private static RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub
      mockStub() {
    RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub stub =
        mock(RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ResolveRuntimeAccountIdentityResponse validResponse(String provenance) {
    return ResolveRuntimeAccountIdentityResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setRequestId(REQUEST_ID.toString())
        .setCanonicalAccountId(ACCOUNT_ID.toString())
        .setSourceAccountRowId(SOURCE_ROW_ID)
        .setAccountUuidProvenance(provenance)
        .build();
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    return incompleteMtlsProperties(
        "entity-management-client.crt", "entity-management-client.key", "account-ca.crt");
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    return incompleteMtlsProperties(
        Files.createFile(directory.resolve("entity-management-client.crt")).toString(),
        Files.createFile(directory.resolve("entity-management-client.key")).toString(),
        Files.createFile(directory.resolve("account-ca.crt")).toString());
  }

  private static CommonGrpcClientProperties incompleteMtlsProperties(
      String certificate, String privateKey, String caCertificate) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return tls;
  }
}
