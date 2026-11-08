package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerAdmissionSharedEntitlementFixture;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.gamesession.client.AccountPlayerAdmissionEntitlementClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextIssuedConnectScope;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginPhysicalCompositionFixture;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;

/**
 * Actual LOGIN and PLAYER_ADMISSION producer/consumer with a genuine absent player and shared
 * Account/GS/World sources and physical GS journal/PENDING/INSTALLED state, followed by actual
 * first JOIN, positive source read and known-token retirement. DEMO policy, caller coordinates,
 * PKI, reset/promotion qualification and receiving custody remain controlled. No public ingress,
 * protected receiving custody, World hold across JOIN, runtime activation or PLAY is proved.
 */
final class WorldPlayerAdmissionEntitlementComposition {
  private WorldPlayerAdmissionEntitlementComposition() {}

  static void exercise(
      Path root,
      AccountControlUiOriginalOrderFixture original,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      CanonicalPlayableTarget target,
      org.jooq.DSLContext gs,
      org.springframework.transaction.PlatformTransactionManager gsTransactions,
      String redisHost,
      int redisPort)
      throws Exception {
    var clock = Clock.systemUTC();
    String namespace = target.targetNamespace();
    Path directory = Files.createDirectories(root.resolve("player-admission"));
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Controlled genuine player admission sources",
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs", "spiffe://firemud/ns/" + namespace + "/sa/game-session-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/" + namespace + "/sa/account-service", true));
    var creation = original.creationEvidence();
    var demo =
        new DemoTenantEntitlementRequest(
            UUID.randomUUID(),
            creation.canonicalTenantId(),
            creation.creationRequestId(),
            creation.requestDigest(),
            null,
            null,
            null,
            true,
            true,
            true,
            true,
            new DemoTenantEntitlementRequest.Quotas(3, 2, 4096));
    var fixture =
        new AccountPlayerAdmissionSharedEntitlementFixture(
            original, directory, targets, demo, clock);
    var loginOwner = fixture.login();
    var keys = pki.keyManagerFactory(pki.identity("account"));
    var trust = pki.trustManagerFactory();
    Server loginServer =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forServer(keys))
                    .trustManager(trust)
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    loginOwner.authenticateReceiver(namespace), new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try (var loginLifetime = WorldPublishedAdmissionSourceComposition.stopOnClose(loginServer)) {
      Server entitlementServer =
          fixture.receiver().createUnregisteredServer("127.0.0.1:0", keys, trust);
      entitlementServer.start();
      try (var entitlementLifetime =
          WorldPublishedAdmissionSourceComposition.stopOnClose(entitlementServer)) {
        var loginEndpoints = new ServiceEndpointsProperties();
        loginEndpoints.setAccountService("localhost:" + loginServer.getPort());
        var entitlementEndpoints = new ServiceEndpointsProperties();
        entitlementEndpoints.setAccountService("localhost:" + entitlementServer.getPort());
        var tls = pki.clientProperties(directory, pki.identity("gs"));
        var channels = new GrpcChannelFactory();
        var source = fixture.trustedJwksSource();
        var verifier =
            new AccountAsymmetricJwtVerifier(
                new AccountPublicJwksCache(
                    source, source.sourceIdentity(), clock, Duration.ofSeconds(30)),
                clock);
        try (var loginClient =
                new CanonicalGameplayLoginAccountClient(
                    loginEndpoints,
                    tls,
                    channels,
                    BlockingGrpcStubCustomizer.noop(),
                    namespace,
                    clock,
                    Duration.ofSeconds(15),
                    verifier);
            var entitlementClient =
                new AccountPlayerAdmissionEntitlementClient(
                    entitlementEndpoints, tls, channels, namespace, clock)) {
          loginClient.initialize();
          entitlementClient.init();
          // This request is only a controlled credential source. GS allocates real coordinates.
          var credentialSource =
              loginOwner.loginRequest(
                  UUID.randomUUID(),
                  new GameplayCredentialSourceContext(
                      UUID.randomUUID(), "192.0.2.42", "trusted_tcp_proxy"));
          try (var physicalLogin =
              InitialGameplayLoginPhysicalCompositionFixture.open(
                  gs,
                  gsTransactions,
                  redisHost,
                  redisPort,
                  loginClient,
                  credentialSource.email(),
                  "192.0.2.42",
                  credentialSource.credential().toCharArray())) {
            var login = physicalLogin.loginResult();
            var evidence = login.evidence();
            loginOwner.assertCommittedNonAdmittingLogin(
                evidence.requestId(),
                evidence.callerContextId(),
                evidence.tokenJti(),
                evidence.tokenSha256(),
                evidence.expiresAtEpochSecond());
            var transport = new ObservedDirectTextTransport("1", UUID.randomUUID());
            var realmScope =
                fixture.issueScopeWithControlledGameSessionCaller(
                    new DirectTextCallerContext(
                        evidence.accountId(),
                        target.canonicalTenantId(),
                        target.realmId(),
                        target.playableStateNamespaceId(),
                        target.playableStateScope(),
                        target.canonicalGameInstanceId(),
                        transport.transportSessionId(),
                        UUID.randomUUID().toString()),
                    new DirectTextJoinTarget(
                        target.canonicalTenantId(),
                        target.realmId(),
                        target.worldSlug(),
                        target.realmSlug(),
                        target.playableStateNamespaceId(),
                        target.playableStateScope(),
                        target.canonicalGameInstanceId(),
                        target.catalogRevision(),
                        target.pointerVersion()));
            var scope = realmScope.scope();
            var issued =
                DirectTextIssuedConnectScope.fromAccountResponse(
                    evidence.accountId().toString(),
                    DirectTextAuthenticatedLobbyScope.targetFor(target),
                    IssueDirectTextConnectScopeResponse.newBuilder()
                        .setConnectScopeId(scope.connectScopeId())
                        .setEvaluatedAt(scope.evaluatedAt())
                        .setConnectScopeExpiresAt(scope.connectScopeExpiresAt())
                        .build(),
                    clock);
            var coordinates = physicalLogin.coordinates();
            // Physical INSTALLED readback is real; protected receiving custody remains controlled.
            var lobby =
                new DirectTextAuthenticatedLobbyScope(
                    UUID.randomUUID(),
                    physicalLogin.installedReadback(),
                    transport,
                    target,
                    issued);
            var received =
                entitlementClient.read(
                    lobby, coordinates, transport, UUID.randomUUID(), login, () -> true);
            fixture.assertNonAdmittingEvidence(received);
            assertThat(received.fields()).containsEntry("variant", "PLAYER_ADMISSION");
            // Actual Account owner transition; caller binding is controlled and no World hold
            // spans this JOIN transaction. Reuse only the still-current verified LOGIN result.
            fixture.commitFirstJoin(
                realmScope, UUID.randomUUID().toString(), transport.transportSessionId());
            var joined =
                entitlementClient.read(
                    lobby, coordinates, transport, UUID.randomUUID(), login, () -> true);
            fixture.assertAdmittingEvidence(joined);
            var journal = new InitialGameplayLoginCoordinationJournalRepository(gs);
            var beforeCustodyRefusal = journal.readCommitted(coordinates).orElseThrow();
            assertThatThrownBy(
                    () ->
                        entitlementClient.read(
                            lobby, coordinates, transport, UUID.randomUUID(), login, () -> false))
                .isExactlyInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure -> {
                      var status = ((StatusRuntimeException) failure).getStatus();
                      assertThat(status.getCode()).isEqualTo(Status.Code.CANCELLED);
                      assertThat(status.getDescription())
                          .isEqualTo("Account player-admission entitlement read did not complete");
                    });
            fixture.assertAdmittingEvidence(joined);
            assertThat(login.evidence()).isEqualTo(evidence);
            assertThat(journal.readCommitted(coordinates)).contains(beforeCustodyRefusal);
            assertThat(physicalLogin.abandonOriginalAfterCustodyLoss().status())
                .isEqualTo(
                    net.firedevops.firemud.gamesession.service.InitialGameplayLoginCleanupOwner
                        .Status.ACCOUNT_RETIRED);
            var beforeClosedResultRefusal = journal.readCommitted(coordinates).orElseThrow();
            // Cleanup has closed the private result. This proves local disposal refusal,
            // not an Account-side retired-token read over the wire.
            assertThatThrownBy(
                    () ->
                        entitlementClient.read(
                            lobby, coordinates, transport, UUID.randomUUID(), login, () -> true))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Canonical LOGIN private result is closed");
            fixture.assertAdmittingEvidence(joined);
            assertThat(login.evidence()).isEqualTo(evidence);
            assertThat(journal.readCommitted(coordinates)).contains(beforeClosedResultRefusal);
          } catch (RuntimeException | Error failure) {
            failure.addSuppressed(
                new AssertionError(
                    "Account LOGIN owner stages: " + loginOwner.ownerObservations()));
            throw failure;
          }
        }
      }
    }
  }
}
