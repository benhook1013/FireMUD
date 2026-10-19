package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameSessionPreseededActorAssignmentOwnerReadClientTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID ASSIGNMENT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ACCOUNT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REALM = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_NAMESPACE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID VERSION = uuid("66666666-6666-4666-8666-666666666666");

  @Test
  void requiresStrictFileBackedMtlsAndValidWorkloadNamespace() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GameSessionPreseededActorAssignmentOwnerReadClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new GameSessionPreseededActorAssignmentOwnerReadClient(
                    new ServiceEndpointsProperties(), null, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    for (CommonGrpcClientProperties incomplete :
        List.of(
            incompleteMtlsProperties(null, "entity.key", "gs-ca.crt"),
            incompleteMtlsProperties("entity.crt", null, "gs-ca.crt"),
            incompleteMtlsProperties("entity.crt", "entity.key", null))) {
      assertThatThrownBy(
              () ->
                  new GameSessionPreseededActorAssignmentOwnerReadClient(
                      new ServiceEndpointsProperties(), incomplete, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("certificate, key, and CA");
    }

    for (CommonGrpcClientProperties classpath :
        List.of(
            incompleteMtlsProperties("classpath:entity.crt", "entity.key", "gs-ca.crt"),
            incompleteMtlsProperties("entity.crt", "classpath:entity.key", "gs-ca.crt"),
            incompleteMtlsProperties("entity.crt", "entity.key", "classpath:gs-ca.crt"))) {
      assertThatThrownBy(
              () ->
                  new GameSessionPreseededActorAssignmentOwnerReadClient(
                      new ServiceEndpointsProperties(), classpath, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("file-backed");
    }

    assertThatThrownBy(
            () ->
                new GameSessionPreseededActorAssignmentOwnerReadClient(
                    new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, "bad.ns"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsCrossNamespaceSelectorsBeforeStubInitialization() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    var client =
        new GameSessionPreseededActorAssignmentOwnerReadClient(
            new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);

    assertThatThrownBy(() -> client.getPreseededActorAssignmentOwnerRead(request("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match Entity Management");
    assertThatThrownBy(() -> client.getPreseededActorAssignmentOwnerRead(request(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initIsIdempotentAndClosedClientRefusesCallsWithoutUsingTheStaleStub(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-session-service:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    var client =
        new GameSessionPreseededActorAssignmentOwnerReadClient(
            new ServiceEndpointsProperties(), mtlsProperties(directory), channelFactory, NAMESPACE);

    client.init();
    client.init();
    var staleStub =
        mock(
            CanonicalGameplayRosterOwnerReadServiceGrpc
                .CanonicalGameplayRosterOwnerReadServiceBlockingStub.class);
    setStub(client, staleStub);
    client.close();

    assertThatThrownBy(() -> client.getPreseededActorAssignmentOwnerRead(request(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(staleStub);
    verify(channelFactory, times(1))
        .buildChannel(
            eq("game-session-service:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true));
  }

  private static PreseededActorAssignmentOwnerReadEvidence.Request request(String namespace) {
    return new PreseededActorAssignmentOwnerReadEvidence.Request(
        ASSIGNMENT,
        ACCOUNT,
        namespace,
        TENANT,
        "earth",
        REALM,
        "main",
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        71L,
        "a".repeat(64),
        "release:exact");
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    return incompleteMtlsProperties("entity-client.crt", "entity-client.key", "gs-ca.crt");
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    return incompleteMtlsProperties(
        Files.createFile(directory.resolve("entity-client.crt")).toString(),
        Files.createFile(directory.resolve("entity-client.key")).toString(),
        Files.createFile(directory.resolve("gs-ca.crt")).toString());
  }

  private static CommonGrpcClientProperties incompleteMtlsProperties(
      String certificate, String privateKey, String caCertificate) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return tls;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void setStub(
      GameSessionPreseededActorAssignmentOwnerReadClient client,
      CanonicalGameplayRosterOwnerReadServiceGrpc
              .CanonicalGameplayRosterOwnerReadServiceBlockingStub
          stub)
      throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient.class
            .getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
