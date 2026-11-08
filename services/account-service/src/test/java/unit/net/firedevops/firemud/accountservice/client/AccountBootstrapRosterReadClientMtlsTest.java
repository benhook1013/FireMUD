package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterActor;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadResponse;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadServiceGrpc;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Physical transport/projection proof only; published GS/World source facts are controlled inputs.
 */
class AccountBootstrapRosterReadClientMtlsTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REALM = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID NAMESPACE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID VERSION = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID ACTOR = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID SECOND_ACTOR = UUID.fromString("88888888-8888-4888-8888-888888888888");
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createCertificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Account bootstrap Entity read CA",
            new IdentitySpec("account", "spiffe://firemud/ns/test/sa/account-service", false),
            new IdentitySpec(
                "entity", "spiffe://firemud/ns/test/sa/entity-management-service", true),
            new IdentitySpec(
                "wrong-service", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/other/sa/entity-management-service", true));
  }

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactEntityPeerPreservesRequestNamespaceAndActualLevelIncludingZero() throws Exception {
    Probe service = new Probe();
    service.respond =
        request ->
            AccountBootstrapRosterReadResponse.newBuilder()
                .setRequest(request)
                .addActors(actor().setLevel(0))
                .addActors(actor().setCharacterUuid(SECOND_ACTOR.toString()).setLevel(4))
                .build();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      UUID requestId = UUID.randomUUID();
      var actors = client.read(requestId, ACCOUNT, published());
      assertThat(actors)
          .containsExactly(
              new AccountBootstrapRosterReadClient.Actor(ACTOR, "Mara", 0),
              new AccountBootstrapRosterReadClient.Actor(SECOND_ACTOR, "Mara", 4));
      assertThat(service.request.get().getRequestUuid()).isEqualTo(requestId.toString());
      assertThat(service.request.get().getCanonicalAccountUuid()).isEqualTo(ACCOUNT.toString());
      assertThat(service.request.get().getExpectedTarget().getPlayableStateNamespaceUuid())
          .isEqualTo(NAMESPACE_ID.toString());
      assertThat(service.peer.get().isService("account-service")).isTrue();
      assertThat(service.peer.get().isInNamespace("test")).isTrue();
      service.respond =
          request -> AccountBootstrapRosterReadResponse.newBuilder().setRequest(request).build();
      assertThat(client.read(UUID.randomUUID(), ACCOUNT, published())).isEmpty();
    }
  }

  @Test
  void localBrowserContextIsPreservedWithoutAnOutboundBearerCredential() throws Exception {
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      SessionContext.setContext(
          ACCOUNT.toString(), List.of("player"), Map.of(TENANT.toString(), List.of("tenantAdmin")));
      assertThat(client.read(UUID.randomUUID(), ACCOUNT, published())).isEmpty();
      assertThat(SessionContext.getAccountId()).isEqualTo(ACCOUNT.toString());
      assertThat(SessionContext.getScopedRoles(TENANT.toString())).containsExactly("tenantAdmin");
      assertThat(service.authorization.get()).isNull();
      assertThat(service.peer.get().isService("account-service")).isTrue();
    }
  }

  @Test
  void foreignEntityIdentityIsRefusedBeforeApplicationDispatch() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      Probe service = new Probe();
      try (var server = server(identity, service);
          var client = client(server.port())) {
        client.init();
        assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
            .isInstanceOf(StatusRuntimeException.class);
        assertThat(service.calls.get()).isZero();
      }
    }
  }

  @Test
  void mismatchedDuplicateAndMissingLevelResponsesNeverBecomeAnEmptySuccess() throws Exception {
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      service.respond =
          request ->
              AccountBootstrapRosterReadResponse.newBuilder()
                  .setRequest(request.toBuilder().setCanonicalAccountUuid(TENANT.toString()))
                  .build();
      assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
          .isInstanceOf(IllegalStateException.class);
      service.respond =
          request ->
              AccountBootstrapRosterReadResponse.newBuilder()
                  .setRequest(request)
                  .addActors(actor())
                  .build();
      assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
          .isInstanceOf(IllegalStateException.class);
      service.respond =
          request ->
              AccountBootstrapRosterReadResponse.newBuilder()
                  .setRequest(request)
                  .addActors(actor().setLevel(2))
                  .addActors(actor().setLevel(2))
                  .build();
      assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void malformedActorFieldsRemainBoundedSourceFailures() throws Exception {
    var invalidActors =
        List.of(
            actor().setLevel(1).setCharacterUuid("source-payload-not-a-uuid").build(),
            actor().setLevel(1).clearCharacterUuid().build(),
            actor().setLevel(1).setCharacterUuid(new UUID(0L, 0L).toString()).build(),
            actor().setLevel(1).clearDisplayName().build(),
            actor().setLevel(1).setDisplayName(" ").build(),
            actor().setLevel(1).setDisplayName(" Mara").build(),
            actor().setLevel(1).setDisplayName("Mara ").build(),
            actor().setLevel(1).setDisplayName("M".repeat(101)).build(),
            actor().setLevel(1).setDisplayName("Mara\u0007").build(),
            actor().setLevel(-1).build());
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      for (var invalid : invalidActors) {
        service.respond =
            request ->
                AccountBootstrapRosterReadResponse.newBuilder()
                    .setRequest(request)
                    .addActors(invalid)
                    .build();
        assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Entity bootstrap discovery actor is malformed")
            .hasNoCause();
      }
      assertThat(service.calls.get()).isEqualTo(invalidActors.size());
    }
  }

  @Test
  void actorShapeAndOrderingAreCheckedBeforeSourceUuidParsing() throws Exception {
    var unknownFields =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var invalidActorLists =
        List.of(
            List.of(actor().setCharacterUuid("source-payload-not-a-uuid").build()),
            List.of(actor().setLevel(1).setUnknownFields(unknownFields).build()),
            List.of(
                actor()
                    .setLevel(1)
                    .setCharacterUuid("source-payload-not-a-uuid")
                    .setUnknownFields(unknownFields)
                    .build()),
            List.of(actor().setLevel(1).build(), actor().setLevel(1).build()),
            List.of(
                actor().setLevel(1).setCharacterUuid(SECOND_ACTOR.toString()).build(),
                actor().setLevel(1).build()),
            List.of(
                actor().setLevel(1).build(),
                actor().setLevel(1).setCharacterUuid("0-source-payload-not-a-uuid").build()));
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      for (var invalid : invalidActorLists) {
        service.respond =
            request ->
                AccountBootstrapRosterReadResponse.newBuilder()
                    .setRequest(request)
                    .addAllActors(invalid)
                    .build();
        assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Entity bootstrap discovery actors are duplicated or unordered")
            .hasNoCause();
      }
      assertThat(service.calls.get()).isEqualTo(invalidActorLists.size());
    }
  }

  @Test
  void noncanonicalActorUuidRemainsASourceFailure() throws Exception {
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      for (String noncanonical : List.of("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "7-7-4-8-7")) {
        service.respond =
            request ->
                AccountBootstrapRosterReadResponse.newBuilder()
                    .setRequest(request)
                    .addActors(actor().setCharacterUuid(noncanonical).setLevel(1))
                    .build();
        assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Canonical actor UUID required")
            .hasNoCause();
      }
      assertThat(service.calls.get()).isEqualTo(2);
    }
  }

  @Test
  void callerInputFailuresRemainArgumentErrorsBeforeTheEntityRead() throws Exception {
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      for (UUID invalid : new UUID[] {null, new UUID(0L, 0L)}) {
        assertThatThrownBy(() -> client.read(invalid, ACCOUNT, published()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read(UUID.randomUUID(), invalid, published()))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThatThrownBy(() -> client.read(UUID.randomUUID(), TENANT, published()))
          .isInstanceOf(IllegalArgumentException.class);
      var unsupported = published();
      when(unsupported.currentTarget().playableStateScope()).thenReturn("ISOLATED");
      assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, unsupported))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new AccountBootstrapRosterReadClient.Actor(ACTOR, " ", 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(service.calls.get()).isZero();
    }
  }

  @Test
  void ambientSqlRefusesBeforeTheEntityRead() throws Exception {
    Probe service = new Probe();
    try (var server = server("entity", service);
        var client = client(server.port())) {
      client.init();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(UUID.randomUUID(), ACCOUNT, published()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(service.calls.get()).isZero();
    }
  }

  private static AccountBootstrapRosterActor.Builder actor() {
    return AccountBootstrapRosterActor.newBuilder()
        .setCharacterUuid(ACTOR.toString())
        .setDisplayName("Mara");
  }

  private static CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence
      published() {
    var target = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class);
    when(target.targetNamespace()).thenReturn("test");
    when(target.canonicalTenantId()).thenReturn(TENANT);
    when(target.realmId()).thenReturn(REALM);
    when(target.worldSlug()).thenReturn("world");
    when(target.realmSlug()).thenReturn("production");
    when(target.canonicalGameInstanceId()).thenReturn(INSTANCE);
    when(target.canonicalVersionId()).thenReturn(VERSION);
    when(target.playableStateNamespaceId()).thenReturn(NAMESPACE_ID);
    when(target.playableStateScope()).thenReturn("SHARED");
    when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
    when(target.catalogRevision()).thenReturn(1L);
    when(target.pointerVersion()).thenReturn(1L);
    when(target.activeWorldEpoch()).thenReturn(1L);
    when(target.admissionPointerSnapshotDigest()).thenReturn("a".repeat(64));
    when(target.ownerProofDigest()).thenReturn("sha256:" + "b".repeat(64));
    var policy = mock(PublishedRealmEntryPolicyEvidence.class);
    when(policy.policyDigest()).thenReturn("sha256:" + "c".repeat(64));
    var policySet = mock(PublishedRealmEntryPolicySetEvidence.class);
    when(policySet.publishedReleaseBundleRef()).thenReturn("controlled-release");
    var source = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
    when(source.selectedPolicyEvidence()).thenReturn(policy);
    when(source.publishedPolicySetEvidence()).thenReturn(policySet);
    var result = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
    when(result.currentTarget()).thenReturn(target);
    when(result.sourceEvidence()).thenReturn(source);
    when(result.selector())
        .thenReturn(
            new CanonicalGameplayRosterOwnerReadEvidence.Request(
                UUID.randomUUID(),
                ACCOUNT,
                "test",
                TENANT,
                "world",
                REALM,
                "production",
                NAMESPACE_ID,
                "SHARED",
                INSTANCE,
                VERSION,
                1L,
                1L,
                1L));
    return result;
  }

  private static AccountBootstrapRosterReadClient client(int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("127.0.0.1:" + port);
    return new AccountBootstrapRosterReadClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("account")),
        new GrpcChannelFactory(),
        "test");
  }

  private static Loopback server(String alias, Probe service) throws Exception {
    var identity = pki.identity(alias);
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service,
                    new GrpcPeerIdentityInterceptor(),
                    new io.grpc.ServerInterceptor() {
                      @Override
                      public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                          io.grpc.ServerCall<ReqT, RespT> call,
                          io.grpc.Metadata headers,
                          io.grpc.ServerCallHandler<ReqT, RespT> next) {
                        service.authorization.set(
                            headers.get(
                                io.grpc.Metadata.Key.of(
                                    "authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER)));
                        return next.startCall(call, headers);
                      }
                    }))
            .build()
            .start();
    return new Loopback(server);
  }

  private record Loopback(Server server) implements AutoCloseable {
    int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2L, TimeUnit.SECONDS))
        throw new IllegalStateException("Entity discovery fixture did not terminate");
    }
  }

  private static final class Probe
      extends AccountBootstrapRosterReadServiceGrpc.AccountBootstrapRosterReadServiceImplBase {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<AccountBootstrapRosterReadRequest> request = new AtomicReference<>();
    final AtomicReference<GrpcPeerIdentity> peer = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    Function<AccountBootstrapRosterReadRequest, AccountBootstrapRosterReadResponse> respond =
        value -> AccountBootstrapRosterReadResponse.newBuilder().setRequest(value).build();

    @Override
    public void readAccountBootstrapRoster(
        AccountBootstrapRosterReadRequest value,
        StreamObserver<AccountBootstrapRosterReadResponse> observer) {
      calls.incrementAndGet();
      request.set(value);
      peer.set(GrpcPeerIdentity.current());
      assertThat(Context.current().getDeadline()).isNotNull();
      observer.onNext(respond.apply(value));
      observer.onCompleted();
    }
  }
}
