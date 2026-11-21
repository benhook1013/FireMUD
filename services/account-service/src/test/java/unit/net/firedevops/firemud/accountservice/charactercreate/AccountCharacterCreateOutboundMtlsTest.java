package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki.Identity;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki.TestCertificateAuthority;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityCharacterCreateTerminalRequest;
import net.firedevops.firemud.gamesession.v1.AcquireCharacterCreateTargetProtectionRequest;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetSnapshot;
import net.firedevops.firemud.gamesession.v1.ReadOriginalCharacterCreateTargetProtectionRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCharacterCreateLifecycleProtectionRequest;
import net.firedevops.firemud.worldmanagement.v1.CharacterCreateLifecycleServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalCharacterCreateLifecycleProtectionRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Native production-client transport proof over controlled loopback PKI and stipulated payloads.
 * ACTIVE, Account snapshots and Entity COMMITTED here are fixture content, not genuine source,
 * lifecycle, player-bootstrap, database ownership, route activation or production-PKI proof.
 */
@Timeout(120)
class AccountCharacterCreateOutboundMtlsTest {
  private static final String NAMESPACE = "test";
  private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  @TempDir static Path directory;
  private static TestCertificateAuthority authority;
  private static Identity accountIdentity;
  private static Identity wrongClientService;
  private static Identity wrongClientNamespace;
  private static Identity gameSessionIdentity;
  private static Identity worldIdentity;
  private static Identity entityIdentity;
  private static Identity wrongServerService;
  private static Identity wrongServerNamespace;
  private static Payloads payloads;

  @BeforeAll
  static void createControlledPkiAndPayloads() throws Exception {
    authority =
        AccountStartSessionReservationProofPki.createCertificateAuthority(
            directory,
            "creation-ca.p12",
            "creation-ca.crt",
            "creation-ca",
            "CN=Controlled creation transport CA",
            "controlled-test-password");
    accountIdentity = issue("account", NAMESPACE, "account-service", false);
    wrongClientService = issue("social-client", NAMESPACE, "social-groups-service", false);
    wrongClientNamespace = issue("other-account", "other-test", "account-service", false);
    gameSessionIdentity = issue("game-session", NAMESPACE, "game-session-service", true);
    worldIdentity = issue("world", NAMESPACE, "world-management-service", true);
    entityIdentity = issue("entity", NAMESPACE, "entity-management-service", true);
    wrongServerService = issue("social-server", NAMESPACE, "social-groups-service", true);
    wrongServerNamespace = issue("other-server", "other-test", "game-session-service", true);
    payloads = Payloads.create();
  }

  @Test
  void actualAccountChannelsPreserveAcquisitionOriginalReadsAndEntityTerminalOnRetry()
      throws Exception {
    try (var owners = owners();
        var clients = clients(owners, accountIdentity, new GrpcChannelFactory())) {
      var composition =
          new AccountCharacterCreateDelegationClients(
              NAMESPACE,
              AccountCharacterCreateOwnerClients.unavailable(),
              clients.gameSession,
              clients.world,
              clients.entity);
      var first = composition.submitProtected(payloads.intent, payloads.protection);
      var retry = composition.submitProtected(payloads.intent, payloads.protection);
      assertThat(first.toByteArray()).containsExactly(payloads.terminal.toByteArray());
      assertThat(retry.toByteArray()).containsExactly(first.toByteArray());
      assertThat(
              clients.entity.readOriginalTerminal(
                  payloads.account, payloads.target, payloads.world))
          .isEqualTo(first);
      var verified = clients.entity.readVerifiedOriginalTerminal(payloads.account);
      assertThat(verified.account()).isEqualTo(payloads.account);
      assertThat(verified.terminal()).isEqualTo(first);
      assertThat(owners.calls)
          .containsExactly(
              "gs.acquire",
              "gs.read",
              "world.acquire",
              "world.read",
              "entity.commit",
              "entity.read",
              "gs.acquire",
              "gs.read",
              "world.acquire",
              "world.read",
              "entity.commit",
              "entity.read",
              "entity.read",
              "entity.read");
      assertThat(first.getOriginalAccountProtection()).isEqualTo(payloads.account.toByteString());
      assertThat(first.getOriginalGameSessionProtection())
          .isEqualTo(payloads.target.toByteString());
      assertThat(first.getOriginalWorldProtection()).isEqualTo(payloads.world.toByteString());
    }
  }

  @Test
  void certificateServiceNamespaceAndBearerCannotSubstituteForExactAccountPeer() throws Exception {
    try (var owners = owners()) {
      for (Identity identity : List.of(wrongClientService, wrongClientNamespace)) {
        try (var clients = clients(owners, identity, new GrpcChannelFactory())) {
          denied(
              Status.Code.PERMISSION_DENIED,
              () -> clients.gameSession.acquireAndReadOriginal(payloads.account));
          denied(
              Status.Code.PERMISSION_DENIED,
              () -> clients.world.acquireAndReadOriginal(payloads.account));
          denied(
              Status.Code.PERMISSION_DENIED,
              () ->
                  clients.entity.commitAndReadOriginal(
                      payloads.account, payloads.target, payloads.world));
        }
      }
      // Even a real Account certificate cannot make bearer metadata part of this certificate-only
      // purpose boundary; a wrong certificate plus an asserted Account bearer is denied too.
      for (Identity identity : List.of(accountIdentity, wrongClientService)) {
        try (var clients = clients(owners, identity, bearerFactory())) {
          denied(
              Status.Code.PERMISSION_DENIED,
              () -> clients.gameSession.acquireAndReadOriginal(payloads.account));
          denied(
              Status.Code.PERMISSION_DENIED,
              () -> clients.world.acquireAndReadOriginal(payloads.account));
          denied(
              Status.Code.PERMISSION_DENIED,
              () ->
                  clients.entity.commitAndReadOriginal(
                      payloads.account, payloads.target, payloads.world));
        }
      }
      assertThat(owners.calls).isEmpty();
    }
  }

  @Test
  void everyClientRejectsWrongServiceAndNamespaceServerCertificates() throws Exception {
    for (Identity identity : List.of(wrongServerService, wrongServerNamespace)) {
      try (var owners = new NativeOwners(identity, identity, identity);
          var clients = clients(owners, accountIdentity, new GrpcChannelFactory())) {
        denied(
            Status.Code.UNAUTHENTICATED,
            () -> clients.gameSession.acquireAndReadOriginal(payloads.account));
        assertThat(owners.calls).isEmpty();
        denied(
            Status.Code.UNAUTHENTICATED,
            () -> clients.world.acquireAndReadOriginal(payloads.account));
        assertThat(owners.calls).isEmpty();
        denied(
            Status.Code.UNAUTHENTICATED,
            () ->
                clients.entity.commitAndReadOriginal(
                    payloads.account, payloads.target, payloads.world));
        assertThat(owners.calls).isEmpty();
      }
    }
  }

  @Test
  void changedIndependentOriginalProtectionAndTerminalReadbacksAreRejectedOverTls()
      throws Exception {
    try (var owners = owners();
        var clients = clients(owners, accountIdentity, new GrpcChannelFactory())) {
      owners.targetRead.set(
          payloads.target.toBuilder().setAcquiredAt(NOW.plusNanos(1).toString()).build());
      assertThatThrownBy(() -> clients.gameSession.acquireAndReadOriginal(payloads.account))
          .hasMessageContaining("Exact original CHARACTER_CREATE");
      owners.worldRead.set(
          payloads.world.toBuilder().setAcquiredAt(NOW.plusNanos(1).toString()).build());
      assertThatThrownBy(() -> clients.world.acquireAndReadOriginal(payloads.account))
          .hasMessageContaining("Exact original CHARACTER_CREATE");
      owners.terminalRead.set(
          payloads.terminal.toBuilder().setCompletedAt(NOW.plusNanos(1).toString()).build());
      assertThatThrownBy(
              () ->
                  clients.entity.commitAndReadOriginal(
                      payloads.account, payloads.target, payloads.world))
          .hasMessageContaining("Exact original CHARACTER_CREATE");
      owners.terminalRead.set(
          payloads.terminal.toBuilder()
              .setOriginalAccountProtection(
                  payloads.account.toBuilder()
                      .setOriginalMembershipSnapshot(
                          ByteString.copyFromUtf8("different-owner-source"))
                      .build()
                      .toByteString())
              .build());
      assertThatThrownBy(
              () ->
                  clients.entity.readOriginalTerminal(
                      payloads.account, payloads.target, payloads.world))
          .hasMessageContaining("Exact original CHARACTER_CREATE");
    }
  }

  @Test
  void nativeTargetReplyEnforcesThePurposeSpecificSixteenMiBReceiveBound() throws Exception {
    try (var owners = owners();
        var clients = clients(owners, accountIdentity, new GrpcChannelFactory())) {
      owners.targetAcquire.set(
          payloads.target.toBuilder()
              .setOriginalTargetEvidence(
                  ByteString.copyFrom(
                      new byte[AccountCharacterCreateDelegationBindings.MAX_PROTECTION_BYTES]))
              .build());
      denied(
          Status.Code.RESOURCE_EXHAUSTED,
          () -> clients.gameSession.acquireAndReadOriginal(payloads.account));
      assertThat(owners.calls).containsExactly("gs.acquire");
    }
  }

  private static NativeOwners owners() throws Exception {
    return new NativeOwners(gameSessionIdentity, worldIdentity, entityIdentity);
  }

  private static Identity issue(String alias, String namespace, String service, boolean server)
      throws Exception {
    return AccountStartSessionReservationProofPki.issueIdentity(
            directory,
            authority,
            alias,
            alias + ".p12",
            alias + ".csr",
            alias + ".crt",
            "spiffe://firemud/ns/" + namespace + "/sa/" + service,
            server,
            false,
            true)
        .identity();
  }

  private static Clients clients(
      NativeOwners owners, Identity identity, GrpcChannelFactory channels) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + owners.servers.get(0).getPort());
    endpoints.setWorldManagementService("localhost:" + owners.servers.get(1).getPort());
    endpoints.setEntityManagementService("localhost:" + owners.servers.get(2).getPort());
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(
        pem(identity.alias() + "-client.pem", "CERTIFICATE", identity.certificate().getEncoded())
            .toString());
    tls.setPrivateKey(
        pem(identity.alias() + "-key.pem", "PRIVATE KEY", identity.privateKey().getEncoded())
            .toString());
    tls.setCaCert(authority.certificateFile().toString());
    var clients =
        new Clients(
            new AccountCharacterCreateGameSessionClient(endpoints, tls, channels, NAMESPACE),
            new AccountCharacterCreateWorldClient(endpoints, tls, channels, NAMESPACE),
            new AccountCharacterCreateEntityClient(endpoints, tls, channels, NAMESPACE));
    try {
      clients.gameSession.init();
      clients.world.init();
      clients.entity.init();
      return clients;
    } catch (Exception failure) {
      clients.close();
      throw failure;
    }
  }

  private static Path pem(String name, String label, byte[] bytes) throws Exception {
    return Files.writeString(
        directory.resolve(name),
        "-----BEGIN "
            + label
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
            + "\n-----END "
            + label
            + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private static GrpcChannelFactory bearerFactory() {
    return new GrpcChannelFactory() {
      @Override
      public io.grpc.ManagedChannel buildChannel(
          String target, int port, CommonGrpcClientProperties tls, boolean keepAlive)
          throws javax.net.ssl.SSLException {
        var headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer test-fixture-token");
        return NettyChannelBuilder.forTarget(target)
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(Path.of(tls.getCaCert()).toFile())
                    .keyManager(
                        Path.of(tls.getCertChain()).toFile(), Path.of(tls.getPrivateKey()).toFile())
                    .build())
            .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
            .build();
      }
    };
  }

  private static void denied(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private record Clients(
      AccountCharacterCreateGameSessionClient gameSession,
      AccountCharacterCreateWorldClient world,
      AccountCharacterCreateEntityClient entity)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      try {
        gameSession.close();
      } finally {
        try {
          world.close();
        } finally {
          entity.close();
        }
      }
    }
  }

  private static final class AccountCertificateOnly implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      var peer =
          GrpcPeerIdentity.fromSslSession(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
              .orElse(null);
      if (peer == null
          || !NAMESPACE.equals(peer.namespace())
          || !"account-service".equals(peer.service())
          || headers.get(AUTHORIZATION) != null) {
        call.close(
            Status.PERMISSION_DENIED.withDescription(
                "Exact certificate-only Account peer required"),
            new Metadata());
        return new ServerCall.Listener<>() {};
      }
      return next.startCall(call, headers);
    }
  }

  private static final class NativeOwners implements AutoCloseable {
    private final List<Server> servers = new ArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<CharacterCreateTargetProtection> targetAcquire =
        new AtomicReference<>(payloads.target);
    private final AtomicReference<CharacterCreateTargetProtection> targetRead =
        new AtomicReference<>(payloads.target);
    private final AtomicReference<WorldCharacterCreateLifecycleProtection> worldRead =
        new AtomicReference<>(payloads.world);
    private final AtomicReference<EntityCharacterCreateTerminal> terminalRead =
        new AtomicReference<>(payloads.terminal);

    private NativeOwners(Identity gs, Identity world, Identity entity) throws Exception {
      try {
        start(
            gs,
            new CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceImplBase() {
              @Override
              public void acquireCharacterCreateTargetProtection(
                  AcquireCharacterCreateTargetProtectionRequest request,
                  StreamObserver<CharacterCreateTargetProtection> observer) {
                respond(
                    "gs.acquire",
                    observer,
                    () -> {
                      assertThat(request.getAccountProtection())
                          .isEqualTo(
                              AccountCharacterCreateDelegationBindings.selector(payloads.account));
                      return targetAcquire.get();
                    });
              }

              @Override
              public void readOriginalCharacterCreateTargetProtection(
                  ReadOriginalCharacterCreateTargetProtectionRequest request,
                  StreamObserver<CharacterCreateTargetProtection> observer) {
                respond(
                    "gs.read",
                    observer,
                    () -> {
                      assertThat(request.getAccountProtection())
                          .isEqualTo(
                              AccountCharacterCreateDelegationBindings.selector(payloads.account));
                      assertThat(request.getGameSessionProtectionId())
                          .isEqualTo(payloads.target.getGameSessionProtectionId());
                      assertThat(request.getGameSessionProtectionFence())
                          .isEqualTo(payloads.target.getGameSessionProtectionFence());
                      return targetRead.get();
                    });
              }
            });
        start(
            world,
            new CharacterCreateLifecycleServiceGrpc.CharacterCreateLifecycleServiceImplBase() {
              @Override
              public void acquireCharacterCreateLifecycleProtection(
                  AcquireCharacterCreateLifecycleProtectionRequest request,
                  StreamObserver<WorldCharacterCreateLifecycleProtection> observer) {
                respond(
                    "world.acquire",
                    observer,
                    () -> {
                      assertThat(request.getAccountProtection())
                          .isEqualTo(
                              AccountCharacterCreateDelegationBindings.selector(payloads.account));
                      return payloads.world;
                    });
              }

              @Override
              public void readOriginalCharacterCreateLifecycleProtection(
                  ReadOriginalCharacterCreateLifecycleProtectionRequest request,
                  StreamObserver<WorldCharacterCreateLifecycleProtection> observer) {
                respond(
                    "world.read",
                    observer,
                    () -> {
                      assertThat(request.getAccountProtection())
                          .isEqualTo(
                              AccountCharacterCreateDelegationBindings.selector(payloads.account));
                      assertThat(request.getWorldProtectionId())
                          .isEqualTo(payloads.world.getWorldProtectionId());
                      assertThat(request.getWorldProtectionFence())
                          .isEqualTo(payloads.world.getWorldProtectionFence());
                      return worldRead.get();
                    });
              }
            });
        start(
            entity,
            new EntityCharacterCreateServiceGrpc.EntityCharacterCreateServiceImplBase() {
              @Override
              public void commitProtectedCharacterCreate(
                  CommitProtectedCharacterCreateRequest request,
                  StreamObserver<EntityCharacterCreateTerminal> observer) {
                respond(
                    "entity.commit",
                    observer,
                    () -> {
                      assertThat(request).isEqualTo(payloads.terminal.getOriginalRequest());
                      return payloads.terminal;
                    });
              }

              @Override
              public void readEntityCharacterCreateTerminal(
                  ReadEntityCharacterCreateTerminalRequest request,
                  StreamObserver<EntityCharacterCreateTerminal> observer) {
                respond(
                    "entity.read",
                    observer,
                    () -> {
                      assertThat(request.getOriginal())
                          .isEqualTo(payloads.terminal.getOriginalRequest().getOriginal());
                      return terminalRead.get();
                    });
              }
            });
      } catch (Exception failure) {
        close();
        throw failure;
      }
    }

    private void start(Identity identity, BindableService service) throws Exception {
      servers.add(
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(
                              identity.privateKey(), identity.certificate()))
                      .trustManager(authority.certificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(
                  ServerInterceptors.intercept(
                      service, new GrpcPeerIdentityInterceptor(), new AccountCertificateOnly()))
              .build()
              .start());
    }

    private <T> void respond(
        String method, StreamObserver<T> observer, java.util.function.Supplier<T> value) {
      calls.add(method);
      try {
        assertThat(GrpcPeerIdentity.current().uri())
            .isEqualTo("spiffe://firemud/ns/test/sa/account-service");
        observer.onNext(value.get());
        observer.onCompleted();
      } catch (Throwable failure) {
        observer.onError(
            Status.INTERNAL
                .withDescription("Controlled fixture binding failure")
                .withCause(failure)
                .asRuntimeException());
      }
    }

    @Override
    public void close() throws Exception {
      for (Server server : servers) server.shutdownNow();
      for (Server server : servers)
        assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private record Payloads(
      AccountCharacterCreateIntent intent,
      AccountCharacterCreateRepository.Protection protection,
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateTargetProtection target,
      WorldCharacterCreateLifecycleProtection world,
      EntityCharacterCreateTerminal terminal) {
    private static Payloads create() throws Exception {
      var lifecycle = AuthoringFixtures.lifecycleEvidence("ACTIVE", 31L);
      var basis =
          AccountCharacterCreateTestActions.intent(
                  NAMESPACE,
                  UUID.randomUUID(),
                  lifecycle.request().canonicalTenantId(),
                  Instant.parse("2099-01-01T00:00:00Z"))
              .action();
      var prior = basis.mutation().authoredSource();
      var launch = lifecycle.launchBinding();
      var scope = lifecycle.request();
      String policy =
          prior
              .policy()
              .canonicalPolicyJson()
              .replace(basis.target().worldSlug(), scope.worldSlug());
      String policyDigest =
          "sha256:"
              + HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(policy.getBytes(StandardCharsets.UTF_8)));
      var authored =
          new CharacterCreateOperatorAction.AuthoredSource(
              prior.schema(),
              prior.bindingId(),
              prior.bindingDigest(),
              scope.canonicalTenantId().toString(),
              scope.canonicalVersionId().toString(),
              launch.descriptor().authoredWorldSourceOperationId().toString(),
              launch.descriptor().authoredWorldSourceEvidenceDigest(),
              scope.worldSlug(),
              launch.releaseAttestation().publishWorkflowId(),
              launch.releaseAttestation().commitId(),
              prior.selectedCommitDigest(),
              new CharacterCreateOperatorAction.Policy(
                  prior.policy().commitId(),
                  prior.policy().revisionId(),
                  prior.policy().logicalRevisionId(),
                  policy,
                  policyDigest),
              prior.core(),
              prior.descriptor(),
              prior.disposition(),
              prior.safeFeedback());
      var c = basis.mutation().catalog();
      var p = basis.mutation().pointer();
      var catalog =
          new CharacterCreateOperatorAction.Catalog(
              c.tenantId(),
              c.tenantSlug(),
              scope.worldSlug(),
              c.worldDisplayName(),
              c.realmId(),
              c.realmSlug(),
              c.realmDisplayName(),
              true,
              true,
              "SHARED",
              scope.playableStateNamespaceId().toString(),
              "PLAYER_CREATED",
              c.catalogRevision());
      var pointer =
          new CharacterCreateOperatorAction.Pointer(
              p.tenantId(),
              p.realmId(),
              scope.worldSlug(),
              p.realmSlug(),
              "OPEN",
              scope.canonicalGameInstanceId().toString(),
              p.pointerVersion(),
              p.catalogRevision(),
              p.updatedAt());
      var action =
          new CharacterCreateOperatorAction(
              new CharacterCreateOperatorAction.Scope(
                  NAMESPACE,
                  scope.canonicalTenantId().toString(),
                  scope.playableStateNamespaceId().toString(),
                  CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
              new CharacterCreateOperatorAction.Target(
                  basis.target().accountId(),
                  basis.target().realmId(),
                  scope.worldSlug(),
                  basis.target().realmSlug(),
                  scope.canonicalGameInstanceId().toString(),
                  scope.canonicalVersionId().toString()),
              basis.expectedVersion(),
              new CharacterCreateOperatorAction.Mutation(
                  "CHARACTER_CREATE", "PLAYER_CREATED", catalog, pointer, authored, "{}"),
              basis.auditReason());
      byte[] actionBytes = CharacterCreateOperatorActionCodec.encode(action);
      var intent =
          new AccountCharacterCreateIntent(
              scope.readRequestId(),
              UUID.randomUUID(),
              "Issued.Transport.Scope_Mixed-case",
              UUID.fromString(action.target().accountId()),
              actionBytes,
              CharacterCreateOperatorActionCodec.mutationDigest(action),
              "stipulated-transport-source-readback".getBytes(StandardCharsets.UTF_8));
      var protection =
          new AccountCharacterCreateRepository.Protection(
              intent, UUID.randomUUID(), 7L, NOW, new byte[] {1}, new byte[] {2});
      var account = AccountCharacterCreateDelegationBindings.account(NAMESPACE, intent, protection);
      var snapshot =
          CharacterCreateTargetSnapshot.newBuilder()
              .setSchemaVersion(1)
              .setOriginalAction(ByteString.copyFrom(actionBytes))
              .setTargetNamespace(NAMESPACE)
              .setOperationId(intent.operationId().toString())
              .setMutationDigest(intent.mutationDigest())
              .setCanonicalTenantId(action.scope().tenantId())
              .setRealmId(action.target().realmId())
              .setCanonicalGameInstanceId(action.target().gameInstanceId())
              .setCanonicalVersionId(action.target().versionId())
              .setPlayableStateNamespaceId(action.scope().playableStateNamespaceId())
              .setCatalogRevision(Long.parseLong(action.expectedVersion().catalogRevision()))
              .setPointerVersion(Long.parseLong(action.expectedVersion().pointerVersion()))
              .setActiveWorldEpoch(31L)
              .setCatalogReceiptDigest("sha256:" + "a".repeat(64))
              .setInitialAdmissionRequestId("stipulated-native-owner-request")
              .setInitialAdmissionOwnerProofDigest("sha256:" + "b".repeat(64))
              .setAdmissionPointerSnapshotDigest("c".repeat(64))
              .build();
      var target =
          CharacterCreateTargetProtection.newBuilder()
              .setAccountProtection(account)
              .setGameSessionProtectionId(UUID.randomUUID().toString())
              .setGameSessionProtectionFence(11L)
              .setAcquiredAt(NOW.toString())
              .setOriginalTargetEvidence(snapshot.toByteString())
              .build();
      var world =
          WorldCharacterCreateLifecycleProtection.newBuilder()
              .setAccountProtection(account)
              .setWorldProtectionId(UUID.randomUUID().toString())
              .setWorldProtectionFence(13L)
              .setAcquiredAt(NOW.toString())
              .setOriginalLifecycleEvidence(ByteString.copyFrom(lifecycle.canonicalBytes()))
              .build();
      var terminal =
          EntityCharacterCreateTerminal.newBuilder()
              .setOriginalRequest(
                  AccountCharacterCreateDelegationBindings.request(account, target, world))
              .setAccountOriginal(account.getOriginal())
              .setOriginalAccountProtection(account.toByteString())
              .setOriginalGameSessionProtection(target.toByteString())
              .setOriginalWorldProtection(world.toByteString())
              .setEntityIntakeRequestId(UUID.randomUUID().toString())
              .setEntitySourceReceiptDigest("sha256:" + "d".repeat(64))
              .setOutcome(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED)
              .setCharacterId(UUID.randomUUID().toString())
              .setTerminalId(UUID.randomUUID().toString())
              .setTerminalFence(1L)
              .setCompletedAt(NOW.toString())
              .build();
      return new Payloads(intent, protection, account, target, world, terminal);
    }
  }
}
