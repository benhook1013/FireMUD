package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthoredWorldSourceClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID OPERATION_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID TENANT_ID = UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final AuthoredWorldSourceGrpcCodec.ReadRequest READ_REQUEST =
      new AuthoredWorldSourceGrpcCodec.ReadRequest(
          NAMESPACE, READ_REQUEST_ID, OPERATION_ID, TENANT_ID, "silver-march");

  @Test
  void requiresFileBackedMtlsBeforeCreatingAChannel(@TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);

    CommonGrpcClientProperties plaintext = fileBackedMtls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    CommonGrpcClientProperties classpath = fileBackedMtls(directory);
    classpath.setPrivateKey("classpath:client.key");
    assertThatThrownBy(() -> newClient(classpath, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties missing = new CommonGrpcClientProperties();
    missing.setCertChain(directory.resolve("missing.crt").toString());
    missing.setPrivateKey(directory.resolve("missing.key").toString());
    missing.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(() -> newClient(missing, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void requiresExplicitInitializationAndConfiguredNamespace(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AuthoredWorldSourceClient client = newClient(fileBackedMtls(directory), channelFactory);
    try {
      assertThatThrownBy(() -> client.read(READ_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");

      AuthoredWorldSourceGrpcCodec.ReadRequest otherNamespace =
          new AuthoredWorldSourceGrpcCodec.ReadRequest(
              "other", READ_REQUEST_ID, OPERATION_ID, TENANT_ID, "silver-march");
      assertThatThrownBy(() -> client.read(otherNamespace))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesTheConfiguredGameDesignTargetOnceAndCannotRestartAfterClose(
      @TempDir Path directory) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("design.internal:7676");
    CommonGrpcClientProperties tls = fileBackedMtls(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("design.internal:7676"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    AuthoredWorldSourceClient client =
        new AuthoredWorldSourceClient(endpoints, tls, channelFactory, NAMESPACE);

    client.init();
    client.init();
    client.close();

    verify(channelFactory)
        .buildChannel(
            eq("design.internal:7676"), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is closed");
  }

  @Test
  void readsAndReturnsOnlyTheExactValidatedSourceReceipt(@TempDir Path directory) throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub =
        mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    AuthoredWorldSourceClient client = clientWithStub(directory, stub);
    ResolveAuthoredWorldSourceResponse response =
        AuthoredWorldSourceGrpcCodec.toReadResponse(READ_REQUEST, source());
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);
    when(stub.resolveAuthoredWorldSource(any())).thenReturn(response);

    AuthoredWorldSourceEvidence result = client.read(READ_REQUEST);

    assertThat(result).isEqualTo(source());
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub)
        .resolveAuthoredWorldSource(AuthoredWorldSourceGrpcCodec.toReadRequest(READ_REQUEST));
  }

  @Test
  void rejectsSubstitutedCorruptOrOpenResponsesAndPropagatesTransportFailure(
      @TempDir Path directory) throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub =
        mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    AuthoredWorldSourceClient client = clientWithStub(directory, stub);
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);

    when(stub.resolveAuthoredWorldSource(any()))
        .thenReturn(
            AuthoredWorldSourceGrpcCodec.toReadResponse(READ_REQUEST, source()).toBuilder()
                .setWorldSlug("other-world")
                .build());
    assertThatThrownBy(() -> client.read(READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-world source evidence");

    when(stub.resolveAuthoredWorldSource(any()))
        .thenReturn(
            AuthoredWorldSourceGrpcCodec.toReadResponse(READ_REQUEST, source()).toBuilder()
                .setUnknownFields(unknownField())
                .build());
    assertThatThrownBy(() -> client.read(READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-world source evidence");

    when(stub.resolveAuthoredWorldSource(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    StatusRuntimeException exception =
        Assertions.assertThrows(StatusRuntimeException.class, () -> client.read(READ_REQUEST));
    assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  private static AuthoredWorldSourceClient clientWithStub(
      Path directory, TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub)
      throws Exception {
    AuthoredWorldSourceClient client =
        newClient(fileBackedMtls(directory), mock(GrpcChannelFactory.class));
    setField(AbstractReloadingBlockingGrpcClient.class, client, "stub", stub);
    setField(AuthoredWorldSourceClient.class, client, "initialized", true);
    return client;
  }

  private static AuthoredWorldSourceClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory) {
    return new AuthoredWorldSourceClient(
        new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("client.crt"), "certificate");
    Path key = Files.writeString(directory.resolve("client.key"), "private-key");
    Path ca = Files.writeString(directory.resolve("ca.crt"), "authority");
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(certificate.toString());
    properties.setPrivateKey(key.toString());
    properties.setCaCert(ca.toString());
    return properties;
  }

  private static AuthoredWorldSourceEvidence source() {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            TENANT_ID,
            "new-kingdom",
            "silver-march",
            "Silver March");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            OPERATION_ID,
            requestDigest,
            TENANT_ID,
            "new-kingdom",
            "silver-march",
            "Silver March",
            17L,
            "legacy-tenant-7",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST_ID,
        OPERATION_ID,
        requestDigest,
        TENANT_ID,
        "new-kingdom",
        "silver-march",
        "Silver March",
        17L,
        "legacy-tenant-7",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static void setField(Class<?> declaringClass, Object target, String name, Object value)
      throws Exception {
    Field field = declaringClass.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
