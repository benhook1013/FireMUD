package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.client.AccountCharacterCreateSourceClient;
import net.firedevops.firemud.entitymanagement.client.WorldCharacterCreateLifecycleClient;
import net.firedevops.firemud.entitymanagement.v1.AbortProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityCharacterCreateTerminalRequest;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Native incoming identity proof only. Owner storage and creation dependencies deliberately deny.
 */
class EntityCharacterCreateMtlsTest {
  @TempDir Path directory;

  @Test
  void ownerCertificatesHaveDistinctReadAndMutationPermissions() throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity creation proof CA",
            identity("entity", "firemud", "entity-management-service", true),
            identity("account", "firemud", "account-service", false),
            identity("game-session", "firemud", "game-session-service", false),
            identity("world", "firemud", "world-management-service", false),
            identity("other-namespace", "other", "account-service", false),
            identity("other-service", "firemud", "game-design-service", false));
    var repository = mock(EntityCharacterCreateRepository.class);
    when(repository.read(any())).thenReturn(Optional.empty());
    when(repository.replay(any())).thenReturn(Optional.empty());
    var account = mock(AccountCharacterCreateSourceClient.class);
    when(account.readOriginal(any()))
        .thenThrow(
            Status.UNAVAILABLE
                .withDescription("Controlled missing Account authority")
                .asRuntimeException());
    var service =
        new EntityCharacterCreateService(
            "firemud",
            account,
            GameSessionCharacterCreateProtectionReader.unavailable(),
            mock(WorldCharacterCreateLifecycleClient.class),
            mock(EntityCharacterCreateSource.class),
            repository);
    var entity = pki.identity("entity");
    Server server =
        NettyServerBuilder.forPort(0)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(entity.privateKey(), entity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new EntityCharacterCreateGrpcService(service, new SimpleMeterRegistry()),
                    new EntityCharacterCreatePeerInterceptor("firemud")))
            .build()
            .start();
    try {
      var original =
          ReadOriginalCharacterCreateRequest.newBuilder()
              .setTargetNamespace("firemud")
              .setOperationId(UUID.randomUUID().toString())
              .setMutationDigest("a".repeat(64))
              .build();
      var read =
          ReadEntityCharacterCreateTerminalRequest.newBuilder().setOriginal(original).build();
      var commit =
          CommitProtectedCharacterCreateRequest.newBuilder()
              .setOriginal(original)
              .setAccountProtection(reference())
              .setGameSessionProtection(reference())
              .setWorldProtection(reference())
              .build();
      var abort =
          AbortProtectedCharacterCreateRequest.newBuilder()
              .setCreation(commit)
              .setAbortRequestId(UUID.randomUUID().toString())
              .build();
      for (String alias :
          new String[] {"account", "game-session", "world", "other-namespace", "other-service"}) {
        ManagedChannel channel = channel(server.getPort(), pki, alias);
        try {
          var stub =
              EntityCharacterCreateServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(5, TimeUnit.SECONDS);
          boolean owner =
              alias.equals("account") || alias.equals("game-session") || alias.equals("world");
          assertThatThrownBy(() -> stub.readEntityCharacterCreateTerminal(read))
              .isInstanceOf(io.grpc.StatusRuntimeException.class)
              .hasMessageContaining(owner ? "NOT_FOUND" : "PERMISSION_DENIED");
          assertThatThrownBy(() -> stub.commitProtectedCharacterCreate(commit))
              .isInstanceOf(io.grpc.StatusRuntimeException.class)
              .hasMessageContaining(alias.equals("account") ? "UNAVAILABLE" : "PERMISSION_DENIED");
          assertThatThrownBy(() -> stub.abortProtectedCharacterCreate(abort))
              .isInstanceOf(io.grpc.StatusRuntimeException.class)
              .hasMessageContaining(alias.equals("account") ? "UNAVAILABLE" : "PERMISSION_DENIED");
        } finally {
          channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
      }
      ManagedChannel noCertificate =
          NettyChannelBuilder.forAddress("localhost", server.getPort())
              .sslContext(GrpcSslContexts.forClient().trustManager(pki.caCertificate()).build())
              .build();
      try {
        assertThatThrownBy(
                () ->
                    EntityCharacterCreateServiceGrpc.newBlockingStub(noCertificate)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .readEntityCharacterCreateTerminal(read))
            .isInstanceOf(io.grpc.StatusRuntimeException.class);
      } finally {
        noCertificate.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void unexpectedOwnerFailuresAreInternalAndCounted() {
    var meterRegistry = new SimpleMeterRegistry();
    var nullFailure = new NullPointerException("private failure detail");
    var stateFailure = new IllegalStateException("private state detail");

    var nullStatus = invokeReadFailure(nullFailure, meterRegistry);
    var stateStatus = invokeReadFailure(stateFailure, meterRegistry);

    assertThat(nullStatus.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(nullStatus.getStatus().getDescription()).isEqualTo("Internal error");
    assertThat(nullStatus).hasCause(nullFailure);
    assertThat(stateStatus.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(stateStatus.getStatus().getDescription()).isEqualTo("Internal error");
    assertThat(stateStatus).hasCause(stateFailure);
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isEqualTo(2.0);
  }

  @Test
  void invalidOwnerEvidenceIsDataLossWithCauseAndNoInternalMetric() {
    var meterRegistry = new SimpleMeterRegistry();
    var invalidEvidence = new IllegalArgumentException("private invalid evidence detail");

    var failure = invokeReadFailure(invalidEvidence, meterRegistry);

    assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.DATA_LOSS);
    assertThat(failure.getStatus().getDescription())
        .isEqualTo("Entity creation owner evidence failed validation");
    assertThat(failure).hasCause(invalidEvidence);
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isZero();
  }

  @Test
  void explicitGrpcStatusAndStorageFailuresKeepTheirClassification() {
    var meterRegistry = new SimpleMeterRegistry();
    var explicit =
        Status.PERMISSION_DENIED.withDescription("controlled denial").asRuntimeException();
    var propagated = invokeReadFailure(explicit, meterRegistry);
    var storage = new DataAccessResourceFailureException("controlled storage outage");
    var unavailable = invokeReadFailure(storage, meterRegistry);

    assertThat(propagated).isSameAs(explicit);
    assertThat(unavailable.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.getStatus().getDescription())
        .isEqualTo("Entity creation storage unavailable; no terminal inferred");
    assertThat(meterRegistry.counter("grpc.app_error", "code", "INTERNAL").count()).isZero();
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

  private static CharacterCreateProtectionReference reference() {
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(UUID.randomUUID().toString())
        .setProtectionFence(1L)
        .build();
  }

  @SuppressWarnings("unchecked")
  private static StatusRuntimeException invokeReadFailure(
      RuntimeException failure, SimpleMeterRegistry meterRegistry) {
    var owner = mock(EntityCharacterCreateService.class);
    doThrow(failure).when(owner).read(any());
    StreamObserver<EntityCharacterCreateTerminal> observer = mock(StreamObserver.class);

    new EntityCharacterCreateGrpcService(owner, meterRegistry)
        .readEntityCharacterCreateTerminal(
            ReadEntityCharacterCreateTerminalRequest.getDefaultInstance(), observer);

    var captured = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(captured.capture());
    verify(observer, never()).onNext(any());
    assertThat(captured.getValue()).isInstanceOf(StatusRuntimeException.class);
    return (StatusRuntimeException) captured.getValue();
  }
}
