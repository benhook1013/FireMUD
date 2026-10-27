package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/** Transport guard and closed-request checks; no SQL-positive ownership is fabricated here. */
class EntityPlayerCreatedRuntimeReferenceIngressTest {
  @TempDir Path directory;

  @Test
  void exactMethodsRequireTheirOwnSameNamespaceOwnerCertificateBeforeDecoding() throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity runtime reference proof CA",
            identity("entity", "firemud", "entity-management-service", true),
            identity("world", "firemud", "world-management-service", false),
            identity("game-session", "firemud", "game-session-service", false),
            identity("other-world", "other", "world-management-service", false),
            identity("other-service", "firemud", "account-service", false));
    var dsl = mock(DSLContext.class);
    var creation = mock(EntityCharacterCreateRepository.class);
    var owner = new EntityPlayerCreatedRuntimeReferenceService("firemud", dsl, creation);
    var entity = pki.identity("entity");
    Server server =
        NettyServerBuilder.forPort(0)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(entity.privateKey(), entity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(EntityPlayerCreatedRuntimeReferenceIngressFactory.bind(owner, "firemud"))
            .build()
            .start();
    try {
      var worldChannel = channel(server.getPort(), pki, "world");
      var gameChannel = channel(server.getPort(), pki, "game-session");
      var wrongNamespaceChannel = channel(server.getPort(), pki, "other-world");
      var wrongServiceChannel = channel(server.getPort(), pki, "other-service");
      try {
        var world =
            CanonicalPlayerCreatedRuntimeReferenceServiceGrpc.newBlockingStub(worldChannel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        var game =
            CanonicalPlayerCreatedRuntimeReferenceServiceGrpc.newBlockingStub(gameChannel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        var otherNamespace =
            CanonicalPlayerCreatedRuntimeReferenceServiceGrpc.newBlockingStub(wrongNamespaceChannel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        var otherService =
            CanonicalPlayerCreatedRuntimeReferenceServiceGrpc.newBlockingStub(wrongServiceChannel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        assertCode(
            () ->
                world.readPlayerCreatedActorPlacementReceipt(
                    PlayerCreatedActorRuntimeReferenceRequest.getDefaultInstance()),
            Status.Code.INVALID_ARGUMENT);
        assertCode(
            () -> world.readPlayerCreatedActorRuntimeReference(validRequest()),
            Status.Code.PERMISSION_DENIED);
        assertCode(
            () -> game.readPlayerCreatedActorPlacementReceipt(validRequest()),
            Status.Code.PERMISSION_DENIED);
        assertCode(
            () -> game.readPlayerCreatedActorRuntimeReference(requestWithNilCharacter()),
            Status.Code.INVALID_ARGUMENT);
        assertCode(
            () -> world.readPlayerCreatedActorPlacementReceipt(requestWithUnknownField()),
            Status.Code.INVALID_ARGUMENT);
        assertCode(
            () -> world.readPlayerCreatedActorPlacementReceipt(requestWithChangedNamespace()),
            Status.Code.PERMISSION_DENIED);
        assertCode(
            () -> otherNamespace.readPlayerCreatedActorPlacementReceipt(validRequest()),
            Status.Code.PERMISSION_DENIED);
        assertCode(
            () -> otherService.readPlayerCreatedActorRuntimeReference(validRequest()),
            Status.Code.PERMISSION_DENIED);
      } finally {
        worldChannel.shutdownNow();
        gameChannel.shutdownNow();
        wrongNamespaceChannel.shutdownNow();
        wrongServiceChannel.shutdownNow();
      }
    } finally {
      server.shutdownNow();
    }
    verifyNoInteractions(dsl, creation);
  }

  @Test
  void grpcAdapterReturnsBoundedSafeStorageFailureWithoutPartialResponse() {
    var owner = mock(EntityPlayerCreatedRuntimeReferenceService.class);
    var failure = new DataAccessResourceFailureException("private database detail");
    when(owner.readPlacementReceipt(any())).thenThrow(failure);
    @SuppressWarnings("unchecked")
    StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer =
        mock(StreamObserver.class);

    new EntityPlayerCreatedRuntimeReferenceGrpcService(owner)
        .readPlayerCreatedActorPlacementReceipt(validRequest(), observer);

    var captured = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(captured.capture());
    verify(observer, never()).onNext(any());
    assertThat(captured.getValue()).isInstanceOf(StatusRuntimeException.class);
    var status = (StatusRuntimeException) captured.getValue();
    assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status.getStatus().getDescription())
        .isEqualTo("Entity runtime reference storage unavailable; no reference inferred");
    assertThat(status.getCause()).isSameAs(failure);
  }

  @Test
  void completedCallbackFailureDoesNotSendASecondTerminalSignal() {
    var owner = mock(EntityPlayerCreatedRuntimeReferenceService.class);
    when(owner.readRuntimeReference(any()))
        .thenReturn(PlayerCreatedActorRuntimeReferenceResponse.getDefaultInstance());
    @SuppressWarnings("unchecked")
    StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer =
        mock(StreamObserver.class);
    var failure = new IllegalStateException("controlled completion callback failure");
    doThrow(failure).when(observer).onCompleted();
    assertThatThrownBy(
            () ->
                new EntityPlayerCreatedRuntimeReferenceGrpcService(owner)
                    .readPlayerCreatedActorRuntimeReference(validRequest(), observer))
        .isSameAs(failure);
    verify(observer, never()).onError(any());
  }

  private static void assertCode(Runnable call, Status.Code expected) {
    assertThatThrownBy(call::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static ManagedChannel channel(int port, LoopbackMtlsTestPki.Pki pki, String alias)
      throws Exception {
    var identity = pki.identity(alias);
    return NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate(), pki.caCertificate())
                .build())
        .build();
  }

  private static IdentitySpec identity(
      String alias, String namespace, String service, boolean server) {
    return new IdentitySpec(alias, "spiffe://firemud/ns/" + namespace + "/sa/" + service, server);
  }

  private static PlayerCreatedActorRuntimeReferenceRequest validRequest() {
    return PlayerCreatedActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setOrigin(
            PlayerCreatedActorOrigin.newBuilder()
                .setTargetNamespace("firemud")
                .setCreationOperationUuid(UUID.randomUUID().toString())
                .setMutationDigest("a".repeat(64))
                .setTerminalUuid(UUID.randomUUID().toString())
                .setTerminalSha256("b".repeat(64)))
        .setTenantUuid(UUID.randomUUID().toString())
        .setAccountUuid(UUID.randomUUID().toString())
        .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
        .setGameInstanceUuid(UUID.randomUUID().toString())
        .setCharacterUuid(UUID.randomUUID().toString())
        .setCanonicalVersionUuid(UUID.randomUUID().toString())
        .setRealmUuid(UUID.randomUUID().toString())
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .build();
  }

  private static PlayerCreatedActorRuntimeReferenceRequest requestWithNilCharacter() {
    return validRequest().toBuilder()
        .setCharacterUuid("00000000-0000-0000-0000-000000000000")
        .build();
  }

  private static PlayerCreatedActorRuntimeReferenceRequest requestWithUnknownField() {
    return validRequest().toBuilder()
        .setUnknownFields(
            UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                .build())
        .build();
  }

  private static PlayerCreatedActorRuntimeReferenceRequest requestWithChangedNamespace() {
    var request = validRequest();
    return request.toBuilder()
        .setOrigin(request.getOrigin().toBuilder().setTargetNamespace("other").build())
        .build();
  }
}
