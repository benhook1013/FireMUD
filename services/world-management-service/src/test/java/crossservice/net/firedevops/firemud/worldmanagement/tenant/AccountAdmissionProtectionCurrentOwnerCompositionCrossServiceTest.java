package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.GrpcOriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionCurrentAttemptReadGrpcService;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Account currentness/acquisition and GS owner SQL over Account-to-GS loopback mTLS.
 * Creator/legal/issuer/environment and Logging claim inputs retain their controlled fixture
 * provenance. Entry World/GS peer contexts and World preparation/hold values are stipulated only.
 * This proves no World execution, initial-admission terminal/settlement, OPEN pointer, production
 * custody, registration or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountAdmissionProtectionCurrentOwnerCompositionCrossServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> ACCOUNT =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> GS =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("admission-current-owner-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> REPLICA =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(REDIS)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "admission-current-owner-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void actualAccountAcquisitionAndExactRetryUseAuthenticatedGsOriginalAndRefuseChangedExpectations()
      throws Exception {
    UUID tenant = UUID.randomUUID();
    var gsData = new DriverManagerDataSource(GS.getJdbcUrl(), GS.getUsername(), GS.getPassword());
    gsData.setSchema("admission_current_owner_" + UUID.randomUUID().toString().replace("-", ""));
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT.getJdbcUrl(),
            ACCOUNT.getUsername(),
            ACCOUNT.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            Files.createDirectories(temporary.resolve("account")),
            tenant)) {
      Flyway.configure()
          .dataSource(gsData)
          .schemas(gsData.getSchema())
          .defaultSchema(gsData.getSchema())
          .locations(
              "filesystem:"
                  + Path.of("../game-session-service/src/main/resources/db/migration")
                      .toAbsolutePath()
                      .normalize())
          .load()
          .migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(gsData), SQLDialect.POSTGRES);
      var manager = new DataSourceTransactionManager(gsData);
      var write = new TransactionTemplate(manager);
      write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var attempts =
          new GameSessionStartSessionOperatorAttemptRepository(dsl, Duration.ofMinutes(5));
      var claim =
          new AtomicReference<GameSessionStartSessionOperatorAttemptRepository.AttemptClaim>();
      var issuer =
          new AccountOriginalStartSessionAuthorizationProofFixture(
              account, Files.createDirectories(temporary.resolve("issuer")), NAMESPACE);
      var action =
          new StartSessionOperatorAction(
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
              new StartSessionOperatorAction.Scope(tenant, NAMESPACE),
              new StartSessionOperatorAction.Target(91L, issuer.creatorAccountId()),
              StartSessionOperatorAction.ExpectedVersion.ABSENT,
              new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
              "TEST_ONLY Account-to-GS admission protection current-owner composition");
      var original =
          issuer.issueAndRedeemOriginal(
              "admission-current-owner-" + UUID.randomUUID(),
              action,
              UUID.randomUUID(),
              31L,
              new AccountOriginalStartSessionAuthorizationProofFixture.OwnerAttemptLifecycle() {
                @Override
                public AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim reserve(
                    StartSessionPostAuthorizationExecutionTuple tuple) {
                  var retained =
                      Objects.requireNonNull(write.execute(ignored -> attempts.reserve(tuple)))
                          .claim()
                          .orElseThrow();
                  claim.set(retained);
                  return new AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim(
                      retained.ownerAttemptId(), retained.ownerMutationId(), retained.ownerFence());
                }

                @Override
                public void attachAccountRedemptionProjection(
                    AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim owner,
                    StartSessionPostAuthorizationExecutionTuple tuple) {
                  var authority =
                      StartSessionAuthorityEvidenceBundle.decode(
                          tuple.authorityEvidenceBundleBytes());
                  write.executeWithoutResult(
                      ignored ->
                          attempts.attachAccountRedemptionProjection(
                              claim.get(),
                              new GameSessionStartSessionOperatorAttemptRepository
                                  .AccountRedemptionProjection(
                                  tuple.authorizationReferenceFingerprint(),
                                  tuple.authorityEvidenceBundleBytes(),
                                  authority.issuanceOperationId(),
                                  Long.parseLong(tuple.issuanceFence()),
                                  tuple.canonicalBytes())));
                }
              });
      var tuple = original.originalTuple();
      var ownerClaim = claim.get();
      var pki =
          LoopbackMtlsTestPki.create(
              Files.createDirectories(temporary.resolve("pki")),
              "Account GS current-owner test CA",
              new LoopbackMtlsTestPki.IdentitySpec("gs", uri("game-session-service"), true),
              new LoopbackMtlsTestPki.IdentitySpec("account", uri("account-service"), false));
      var serverIdentity = pki.identity("gs");
      var calls = new AtomicInteger();
      var count =
          new ServerInterceptor() {
            @Override
            public <Q, A> ServerCall.Listener<Q> interceptCall(
                ServerCall<Q, A> call, Metadata headers, ServerCallHandler<Q, A> next) {
              calls.incrementAndGet();
              return next.startCall(call, headers);
            }
          };
      Server server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(
                              serverIdentity.privateKey(), serverIdentity.certificate()))
                      .trustManager(pki.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(
                  ServerInterceptors.intercept(
                      new OriginalStartSessionCurrentAttemptReadGrpcService(
                          attempts, manager, NAMESPACE),
                      count,
                      new GrpcPeerIdentityInterceptor()))
              .build()
              .start();
      try {
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
        try (var client =
            new GrpcOriginalStartSessionCurrentAttemptClient(
                endpoints,
                pki.clientProperties(temporary, pki.identity("account")),
                new GrpcChannelFactory(),
                NAMESPACE)) {
          client.init();
          var expected =
              new OriginalStartSessionCurrentAttemptEvidence.Request(
                  UUID.randomUUID(),
                  NAMESPACE,
                  tuple.canonicalBytes(),
                  ownerClaim.ownerAttemptId(),
                  ownerClaim.ownerMutationId(),
                  ownerClaim.ownerFence());
          var gsBefore = rows(dsl, "game_session_start_session_operator_attempt");
          var observed = client.read(expected);
          assertThat(observed.request().expectedOwnerAttemptId())
              .isEqualTo(ownerClaim.ownerAttemptId());
          assertThat(observed.request().expectedOwnerMutationId())
              .isEqualTo(ownerClaim.ownerMutationId());
          assertThat(observed.request().expectedOwnerFence()).isEqualTo(ownerClaim.ownerFence());
          assertThat(observed.accountRedemptionProjection())
              .containsExactly(tuple.canonicalBytes());
          assertThat(rows(dsl, "game_session_start_session_operator_attempt")).isEqualTo(gsBefore);

          UUID instance = UUID.randomUUID();
          // Controlled World receiving input, with the full selectors required by the actual
          // Account SQL validator. It is not a GS launch or authenticated World preparation.
          String preparation = controlledPreparation(tuple, instance);
          var worldRequest =
              new AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest(
                  tuple.canonicalBytes(),
                  ownerClaim.ownerMutationId(),
                  ownerClaim.ownerAttemptId(),
                  ownerClaim.ownerFence(),
                  instance,
                  preparation,
                  prefixedDigest(preparation.getBytes(StandardCharsets.UTF_8)));
          var participation =
              asPeer(
                  "world-management-service",
                  () -> issuer.worldParticipationAcquisitionService(client).acquire(worldRequest));
          assertThat(participation.originalAttemptEvidence().result().accountRedemptionProjection())
              .containsExactly(tuple.canonicalBytes());
          var hold = controlledHold(tenant, instance);
          var request =
              AccountStartSessionAdmissionProtectionRequest.create(
                  tuple.canonicalBytes(),
                  observed.accountRedemptionProjection(),
                  ownerClaim.ownerMutationId(),
                  ownerClaim.ownerAttemptId(),
                  ownerClaim.ownerFence(),
                  observed.originalLeaseExpiresAt(),
                  participation.participation().participationId(),
                  participation.participation().participationFence(),
                  hold);
          var acquisition = issuer.admissionProtectionAcquisitionService(client);
          int beforeAcquire = calls.get();
          var acquired = asPeer("game-session-service", () -> acquisition.acquire(request));
          assertThat(calls.get()).isEqualTo(beforeAcquire + 1);
          assertThat(acquired.request().canonicalBytes()).containsExactly(request.canonicalBytes());
          assertThat(acquired.request().originalLeaseExpiresAt())
              .isEqualTo(observed.originalLeaseExpiresAt());
          assertThat(acquired.sourceEvidenceVector()).isNotEmpty();
          assertThat(acquired.accountProtectionFence()).isPositive();
          int beforeRetry = calls.get();
          var replay = asPeer("game-session-service", () -> acquisition.acquire(request));
          assertThat(calls.get()).isEqualTo(beforeRetry + 1);
          assertThat(replay.canonicalBytes()).containsExactly(acquired.canonicalBytes());
          assertThat(rows(issuer.sourceDsl(), "account_start_session_admission_protections"))
              .hasSize(1);
          var accountBefore = protectedRows(issuer.sourceDsl());

          for (boolean changeFence : List.of(true, false)) {
            UUID expectedOwner = changeFence ? ownerClaim.ownerAttemptId() : UUID.randomUUID();
            long expectedFence =
                changeFence ? ownerClaim.ownerFence() + 1 : ownerClaim.ownerFence();
            var wrong =
                new OriginalStartSessionCurrentAttemptEvidence.Request(
                    UUID.randomUUID(),
                    NAMESPACE,
                    tuple.canonicalBytes(),
                    expectedOwner,
                    ownerClaim.ownerMutationId(),
                    expectedFence);
            assertThatThrownBy(() -> client.read(wrong))
                .isInstanceOfSatisfying(
                    StatusRuntimeException.class,
                    failure ->
                        assertThat(failure.getStatus().getCode())
                            .isEqualTo(Status.Code.FAILED_PRECONDITION));
            var changedRequest =
                AccountStartSessionAdmissionProtectionRequest.create(
                    tuple.canonicalBytes(),
                    observed.accountRedemptionProjection(),
                    ownerClaim.ownerMutationId(),
                    expectedOwner,
                    expectedFence,
                    observed.originalLeaseExpiresAt(),
                    request.accountWorldParticipationId(),
                    request.accountWorldParticipationFence(),
                    hold);
            assertThatThrownBy(
                    () -> asPeer("game-session-service", () -> acquisition.acquire(changedRequest)))
                .isInstanceOfSatisfying(
                    StatusRuntimeException.class,
                    failure ->
                        assertThat(failure.getStatus().getCode())
                            .isEqualTo(Status.Code.FAILED_PRECONDITION));
            assertThat(rows(dsl, "game_session_start_session_operator_attempt"))
                .isEqualTo(gsBefore);
            assertThat(protectedRows(issuer.sourceDsl())).isEqualTo(accountBefore);
          }
          var finalReplay = asPeer("game-session-service", () -> acquisition.acquire(request));
          assertThat(finalReplay.canonicalBytes()).containsExactly(acquired.canonicalBytes());
          assertThat(protectedRows(issuer.sourceDsl())).isEqualTo(accountBefore);
          assertThat(dsl.fetchCount(DSL.table("game_session_canonical_initial_admission_attempt")))
              .isZero();
          assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
        }
      } finally {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      var admin =
          DSL.using(
              new DriverManagerDataSource(GS.getJdbcUrl(), GS.getUsername(), GS.getPassword()),
              SQLDialect.POSTGRES);
      admin.execute("DROP SCHEMA IF EXISTS \"" + gsData.getSchema() + "\" CASCADE");
    }
  }

  private static String controlledPreparation(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID instance) throws Exception {
    var scope = tuple.preAuthorizationTuple().action().scope();
    Map<String, Object> selectors =
        Map.of(
            "targetNamespace",
            NAMESPACE,
            "canonicalTenantId",
            scope.tenantId().toString(),
            "controlPlaneRequestId",
            tuple.controlPlaneRequestId(),
            "canonicalGameInstanceId",
            instance.toString(),
            "worldSlug",
            "controlled-admission-world");
    var evidence = new java.util.LinkedHashMap<>(selectors);
    evidence.put(
        "descriptorJson",
        JsonMapper.builder().build().writeValueAsString(Map.of("gameTemplateId", 91L)));
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(
            JsonMapper.builder()
                .build()
                .writeValueAsString(
                    Map.of(
                        "schemaVersion",
                        1,
                        "identity",
                        selectors,
                        "gameSessionReadRequest",
                        selectors,
                        "gameSessionReadEvidence",
                        evidence,
                        "launchBinding",
                        Map.of(
                            "targetNamespace",
                            NAMESPACE,
                            "canonicalTenantId",
                            scope.tenantId().toString(),
                            "controlPlaneRequestId",
                            tuple.controlPlaneRequestId(),
                            "worldSlug",
                            "controlled-admission-world")))),
        StandardCharsets.UTF_8);
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity controlledHold(
      UUID tenant, UUID instance) {
    UUID realm = UUID.randomUUID(), playable = UUID.randomUUID(), version = UUID.randomUUID();
    String operation = "controlled-admission-" + UUID.randomUUID();
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            tenant,
            "controlled-admission-world",
            realm,
            playable,
            "SHARED",
            instance,
            version,
            1,
            1,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            operation);
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            tenant,
            "controlled-admission-world",
            realm,
            playable,
            "SHARED",
            instance,
            version,
            1,
            operation,
            digest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1,
            null),
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private static Map<String, List<String>> protectedRows(DSLContext dsl) {
    return Map.of(
        "protection",
        rows(dsl, "account_start_session_admission_protections"),
        "sources",
        rows(dsl, "account_start_session_admission_protection_sources"),
        "participation",
        rows(dsl, "account_start_session_world_participations"),
        "attempt",
        rows(dsl, "account_start_session_world_original_attempt_evidence"));
  }

  private static List<String> rows(DSLContext dsl, String table) {
    return dsl.fetch(
            "SELECT xmin::text || ':' || to_jsonb(t)::text AS retained FROM "
                + table
                + " t ORDER BY to_jsonb(t)::text")
        .getValues("retained", String.class);
  }

  private static String prefixedDigest(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static <T> T asPeer(String service, Callable<T> action) throws Exception {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, new GrpcPeerIdentity(uri(service), NAMESPACE, service))
        .call(action);
  }

  private static String uri(String service) {
    return "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service;
  }
}
