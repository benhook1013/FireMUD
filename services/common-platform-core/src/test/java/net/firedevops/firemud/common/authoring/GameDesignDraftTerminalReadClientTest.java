package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameDesignDraftTerminalReadClientTest {
  @Test
  void rejectsPlaintextBeforeCreatingChannel(@TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties tls = tls(directory);
    tls.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GameDesignDraftTerminalReadClient(
                    new ServiceEndpointsProperties(), tls, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsWrongNamespaceAndUninitializedUseBeforeTransport(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignDraftTerminalReadClient client =
        new GameDesignDraftTerminalReadClient(
            new ServiceEndpointsProperties(), tls(directory), channelFactory, "test");
    try {
      assertThatThrownBy(() -> client.read(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(request("test")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesAnExactGameDesignPeerAuthenticatedStubForConfiguredEndpoint(
      @TempDir Path directory) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    GameDesignDraftTerminalReadClient client =
        new GameDesignDraftTerminalReadClient(endpoints, tls(directory), channelFactory, "test");
    try {
      client.init();

      verify(channelFactory)
          .buildChannel(
              eq("game-design.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
      AbstractStub<?> stub = initializedStub(client);
      assertThat(stub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
      assertThat(readField(stub.getCallOptions().getCredentials(), "expectedPeerUri"))
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
    } finally {
      client.close();
    }
  }

  private static AbstractStub<?> initializedStub(GameDesignDraftTerminalReadClient client)
      throws ReflectiveOperationException {
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (AbstractStub<?>) field.get(client);
  }

  private static Object readField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static GameDesignDraftTerminalReadEvidence.Request request(String namespace) {
    UUID tenant = uuid("11111111-1111-4111-8111-111111111111");
    UUID version = uuid("22222222-2222-4222-8222-222222222222");
    UUID request = uuid("44444444-4444-4444-8444-444444444444");
    UUID commit = uuid("55555555-5555-4555-8555-555555555555");
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            request,
            commit,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    byte[] draftBytes = draft.canonicalBytes();
    byte[] accountBinding =
        new DraftAuthorizationFenceBinding(
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                request,
                commit,
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                tenant,
                version,
                "base-1",
                "0",
                draftBytes,
                draftBytes,
                draft.digest(),
                List.of(
                    new SourceEvidence(
                        SourceKind.GLOBAL_ROLES,
                        uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                        null,
                        "1",
                        null,
                        null,
                        new byte[] {1})))
            .canonicalBytes();
    return new GameDesignDraftTerminalReadEvidence.Request(
        1, namespace, uuid("33333333-3333-4333-8333-333333333333"), accountBinding);
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
