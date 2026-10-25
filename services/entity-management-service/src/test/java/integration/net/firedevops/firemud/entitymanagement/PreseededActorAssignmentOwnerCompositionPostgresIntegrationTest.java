package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import net.firedevops.firemud.account.v1.AccountActorStagingEligibilityServiceGrpc;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityCurrentness;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityDecision;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityPurpose;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityRequest;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityResponse;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityRequest;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityResponse;
import net.firedevops.firemud.account.v1.RuntimeAccountIdentityServiceGrpc;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadGrpcCodec;
import net.firedevops.firemud.entitymanagement.client.AccountPreseededActorStagingEligibilityClient;
import net.firedevops.firemud.entitymanagement.client.AccountRuntimeIdentityClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload.ActorKind;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededActorAssignmentCapability;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalPreseededActorAssignmentGrpcService;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical mTLS-to-Entity assignment and receipt proof with the actual Spring-proxied repository
 * and current PostgreSQL migrations. Account and Game Session answers are controlled owner-shaped
 * responses; this proves neither upstream custody/issuance nor production runtime activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    classes = EntityManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class PreseededActorAssignmentOwnerCompositionPostgresIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String RUN_ID = "entity-preseeded-assignment-test";
  private static final String PROJECT = "entity-preseeded-assignment";
  private static final String ENTITY_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service";
  private static final Path TLS_DIRECTORY = createTlsDirectory();
  private static final TestTls TLS = createTestTls(TLS_DIRECTORY);

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Autowired private CharacterRepository characterRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SslBundles sslBundles;

  // Prevent the unrelated packaged server lifecycle from starting; the exact scoped service below
  // is hosted by the real bounded factory with the generated active test trust bundle.
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "entity_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
    registry.add(
        "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate",
        () -> TLS.entityServerChain().toString());
    registry.add(
        "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key",
        () -> TLS.entityServerKey().toString());
    registry.add(
        "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate", () -> TLS.caFile().toString());
    registry.add("spring.grpc.server.ssl.enabled", () -> "true");
    registry.add("spring.grpc.server.ssl.bundle", () -> "firemud-grpc");
    registry.add("spring.grpc.server.ssl.client-auth", () -> "REQUIRE");
  }

  @Test
  void composesExactMtlsOwnerReadsCapabilityAndActualPostgresAssignmentWriterAndReceiptRead()
      throws Exception {
    var ownerFixture = readOwnerFixture();
    var assignment = assignmentRequest(ownerFixture.request());
    var grantDirectory = Files.createDirectory(TLS_DIRECTORY.resolve("protected-capability"));
    setDirectoryPermissions(grantDirectory);
    Path capabilityFile = grantDirectory.resolve("assignment-grant.json");
    writeCapability(capabilityFile, assignment, RUN_ID, PROJECT, trustRootFingerprint());

    var identityOwner = new ControlledRuntimeIdentityOwner();
    var stagingOwner = new ControlledStagingOwner();
    var gameSessionOwner = new ControlledGameSessionOwner(ownerFixture.response());
    var sourceServers =
        List.of(
            boundedServer(
                "account-server", identityOwner, stagingOwner, new GrpcPeerIdentityInterceptor()),
            boundedServer(
                "game-session-server", gameSessionOwner, new GrpcPeerIdentityInterceptor()));
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + sourceServers.getFirst().getPort());
    endpoints.setGameSessionService("127.0.0.1:" + sourceServers.get(1).getPort());
    CommonGrpcClientProperties entityClientTls =
        TLS.pki().clientProperties(TLS_DIRECTORY, TLS.pki().identity("entity-client"));
    var configuration = new PreseededActorAssignmentConfiguration();
    var channelFactory = new GrpcChannelFactory();
    AccountRuntimeIdentityClient identityClient =
        configuration.preseededActorAccountIdentityClient(
            endpoints, entityClientTls, channelFactory, NAMESPACE);
    AccountPreseededActorStagingEligibilityClient stagingClient =
        configuration.preseededActorStagingEligibilityClient(
            endpoints, entityClientTls, channelFactory, NAMESPACE);
    GameSessionPreseededActorAssignmentOwnerReadClient gameSessionClient =
        configuration.preseededActorAssignmentOwnerReadClient(
            endpoints, entityClientTls, channelFactory, NAMESPACE);
    try (identityClient;
        stagingClient;
        gameSessionClient) {
      identityClient.init();
      stagingClient.init();
      gameSessionClient.init();
      assertThat(
              RunOwnedPreseededActorAssignmentCapability.activeTrustRootFingerprint(
                  sslBundles, "firemud-grpc", TLS.caFile().toString()))
          .isEqualTo(trustRootFingerprint());
      var authority =
          configuration.runOwnedPreseededAssignmentAuthorityFromCapability(
              capabilityFile.toString(),
              RUN_ID,
              PROJECT,
              NAMESPACE,
              TLS.caFile().toString(),
              "firemud-grpc",
              sslBundles);
      CharacterRepository observedRepository = observeRepositoryEntry(characterRepository);
      var assignmentService =
          configuration.preseededActorAssignmentService(
              authority,
              configuration.enabledPreseededActorAccountIdentityPort(identityClient),
              configuration.enabledPreseededActorStagingEligibilityPort(stagingClient),
              configuration.preseededActorAssignmentOwnerEvidencePort(gameSessionClient, NAMESPACE),
              observedRepository);
      var receiptService = configuration.preseededActorAssignmentReceiptService(observedRepository);
      CanonicalPreseededActorAssignmentGrpcService handler =
          configuration.canonicalPreseededActorAssignmentGrpcService(
              NAMESPACE, assignmentService, receiptService);
      Server entityServer =
          boundedEntityServer(
              handler,
              configuration.canonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE),
              new GrpcPeerIdentityInterceptor());
      try {
        var baseline = mutationCounts(assignment);
        AssignPreseededActorRequest wireRequest = toWire(assignment);

        // Exact method guard rejects a same-namespace wrong role and a right role from the wrong
        // namespace before the handler can reach Account, Game Session, or the database.
        assertCallDenied(
            entityServer, "account-client", wireRequest, Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);
        assertCallDenied(
            entityServer,
            "foreign-game-session-client",
            wireRequest,
            Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);
        assertCallDenied(entityServer, "world-client", wireRequest, Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);

        // The protected file is checked against the live server bundle and exact run binding.
        writeCapability(capabilityFile, assignment, "other-run", PROJECT, trustRootFingerprint());
        assertCallDenied(
            entityServer, "game-session-client", wireRequest, Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);
        writeCapability(capabilityFile, assignment, RUN_ID, PROJECT, "0".repeat(64));
        assertCallDenied(
            entityServer, "game-session-client", wireRequest, Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);
        writeCapability(capabilityFile, assignment, RUN_ID, PROJECT, trustRootFingerprint());

        // Closed request digest and capability-bound target checks happen before owner reads.
        String changedDigest =
            assignment.mutationIntentDigest().startsWith("0")
                ? "1" + assignment.mutationIntentDigest().substring(1)
                : "0" + assignment.mutationIntentDigest().substring(1);
        assertCallDenied(
            entityServer,
            "game-session-client",
            wireRequest.toBuilder().setMutationIntentDigest(changedDigest).build(),
            Status.Code.INVALID_ARGUMENT);
        assertNoMutation(baseline, assignment);
        var changedTarget =
            new PreseededActorAssignmentExpectedTarget(
                assignment.expectedTarget().canonicalTenantUuid(),
                assignment.expectedTarget().realmUuid(),
                "other-world",
                assignment.expectedTarget().realmSlug(),
                assignment.expectedTarget().gameInstanceId(),
                assignment.expectedTarget().catalogRevision(),
                assignment.expectedTarget().canonicalVersionUuid(),
                assignment.expectedTarget().frozenPolicyDigest(),
                assignment.expectedTarget().playableStateNamespaceId(),
                assignment.expectedTarget().publishedReleaseBundleRef(),
                assignment.expectedTarget().playableStateScope());
        var changedTargetRequest =
            new PreseededActorAssignmentRequest(
                assignment.assignmentUuid(),
                assignment.canonicalAccountUuid(),
                assignment.corePayload(),
                changedTarget);
        assertCallDenied(
            entityServer,
            "game-session-client",
            toWire(changedTargetRequest),
            Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);

        // Owner-shaped Account and GS responses travel over the actual clients and TLS pins.
        identityOwner.returnWrongAccount.set(true);
        assertCallDenied(
            entityServer, "game-session-client", wireRequest, Status.Code.FAILED_PRECONDITION);
        identityOwner.returnWrongAccount.set(false);
        assertNoMutation(baseline, assignment);
        stagingOwner.returnIneligible.set(true);
        assertCallDenied(
            entityServer, "game-session-client", wireRequest, Status.Code.FAILED_PRECONDITION);
        stagingOwner.returnIneligible.set(false);
        assertNoMutation(baseline, assignment);
        gameSessionOwner.nextFailure.set(Status.NOT_FOUND);
        assertCallDenied(entityServer, "game-session-client", wireRequest, Status.Code.UNAVAILABLE);
        gameSessionOwner.nextFailure.set(Status.FAILED_PRECONDITION);
        assertCallDenied(entityServer, "game-session-client", wireRequest, Status.Code.UNAVAILABLE);
        gameSessionOwner.returnStaleEvidence.set(true);
        assertCallDenied(entityServer, "game-session-client", wireRequest, Status.Code.UNAVAILABLE);
        gameSessionOwner.returnStaleEvidence.set(false);
        assertNoMutation(baseline, assignment);

        // Invalidate the capability only after all three independent source RPCs have returned.
        // The service's second read is the last authorization step before the proxied SQL writer.
        gameSessionOwner.afterNextRead.set(
            () ->
                writeCapabilityUnchecked(
                    capabilityFile, assignment, "changed-during-source-read", PROJECT));
        assertCallDenied(
            entityServer, "game-session-client", wireRequest, Status.Code.PERMISSION_DENIED);
        assertNoMutation(baseline, assignment);
        writeCapability(capabilityFile, assignment, RUN_ID, PROJECT, trustRootFingerprint());

        // Controlled upstream owner-shaped answers now reach the actual repository proxy and
        // current Flyway-managed PostgreSQL schema. The source responses do not prove custody.
        AssignPreseededActorResponse assigned =
            call(entityServer, "game-session-client", wireRequest);
        assertThat(assigned.getOutcome())
            .isEqualTo(PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED);
        UUID characterUuid = UUID.fromString(assigned.getCharacterUuid());
        assertThat(writerEntryCount.get()).isEqualTo(1);
        assertThat(writerEnteredOutsideTransaction.get()).isTrue();
        assertThat(identityOwner.lastCallerUri.get()).isEqualTo(ENTITY_URI);
        assertThat(stagingOwner.lastCallerUri.get()).isEqualTo(ENTITY_URI);
        assertThat(gameSessionOwner.lastCallerUri.get()).isEqualTo(ENTITY_URI);
        assertThat(identityOwner.calls.get()).isPositive();
        assertThat(stagingOwner.calls.get()).isPositive();
        assertThat(gameSessionOwner.calls.get()).isPositive();
        assertThat(mutationCounts(assignment).actors()).isEqualTo(1L);
        assertThat(mutationCounts(assignment).receipts()).isEqualTo(1L);
        var persistedActor =
            jdbcTemplate.queryForMap(
                "SELECT account_uuid, tenant_uuid, playable_state_namespace_id, actor_identity_status "
                    + "FROM characters WHERE character_uuid = ?",
                characterUuid);
        assertThat(persistedActor.get("account_uuid")).isEqualTo(assignment.canonicalAccountUuid());
        assertThat(persistedActor.get("tenant_uuid"))
            .isEqualTo(assignment.expectedTarget().canonicalTenantUuid());
        assertThat(persistedActor.get("playable_state_namespace_id"))
            .isEqualTo(assignment.expectedTarget().playableStateNamespaceId());
        assertThat(persistedActor.get("actor_identity_status")).isEqualTo("OWNER_RESOLVED");
        PreseededActorAssignmentReceiptRequest receiptRequest =
            new PreseededActorAssignmentReceiptRequest(
                assignment.assignmentUuid(),
                assignment.canonicalAccountUuid(),
                characterUuid,
                assignment.mutationIntentDigest(),
                assignment.expectedTarget());
        PreseededActorAssignmentReceipt receipt =
            characterRepository.readPreseededActorAssignmentReceipt(receiptRequest).orElseThrow();
        assertThat(receipt.corePayload()).isEqualTo(assignment.corePayload());
        assertThat(receipt.target()).isEqualTo(assignment.expectedTarget());
        assertThat(receipt.sourceAccountRowId()).isEqualTo(23L);
        assertThat(receipt.status()).isEqualTo("ASSIGNED");

        List<String> persistedRows = assignmentAndActorRows(assignment, characterUuid);
        MutationCounts assignedBaseline = mutationCounts(assignment);
        ReadPreseededActorAssignmentReceiptRequest receiptReadRequest =
            toReceiptReadWire(
                assignment, characterUuid, UUID.fromString("80000000-0000-4000-8000-000000000008"));

        // Only the exact same-namespace World peer can read this persisted Entity receipt.
        // Rejected peers do not reach the repository or alter either stored row.
        assertReceiptReadCallDenied(
            entityServer, "game-session-client", receiptReadRequest, Status.Code.PERMISSION_DENIED);
        assertReceiptReadCallDenied(
            entityServer, "account-client", receiptReadRequest, Status.Code.PERMISSION_DENIED);
        assertReceiptReadCallDenied(
            entityServer,
            "foreign-world-client",
            receiptReadRequest,
            Status.Code.PERMISSION_DENIED);
        assertThat(receiptReadEntryCount.get()).isZero();
        assertNoMutation(assignedBaseline, assignment);

        ReadPreseededActorAssignmentReceiptResponse persistedRead =
            callReceiptRead(entityServer, "world-client", receiptReadRequest);
        assertThat(persistedRead.getSchemaVersion()).isEqualTo(1);
        assertThat(persistedRead.getPlacementOperationUuid())
            .isEqualTo(receiptReadRequest.getPlacementOperationUuid());
        assertThat(persistedRead.getAssignmentUuid())
            .isEqualTo(assignment.assignmentUuid().toString());
        assertThat(persistedRead.getCanonicalAccountUuid())
            .isEqualTo(assignment.canonicalAccountUuid().toString());
        assertThat(persistedRead.getCharacterUuid()).isEqualTo(characterUuid.toString());
        assertThat(persistedRead.getIntentDigest()).isEqualTo(assignment.mutationIntentDigest());
        assertThat(persistedRead.getExpectedTarget())
            .isEqualTo(toWire(assignment).getExpectedTarget());
        assertThat(persistedRead.getCorePayload().getDisplayName())
            .isEqualTo(assignment.corePayload().displayName());
        assertThat(persistedRead.getStatus())
            .isEqualTo(
                PreseededActorAssignmentReceiptStatus
                    .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED);
        assertThat(receiptReadEntryCount.get()).isEqualTo(1);
        assertThat(receiptReadEnteredOutsideTransaction.get()).isTrue();
        assertThat(receiptReadCallerUri.get()).isEqualTo(WORLD_URI);
        assertThat(assignmentAndActorRows(assignment, characterUuid))
            .containsExactlyElementsOf(persistedRows);
        assertNoMutation(assignedBaseline, assignment);

        ReadPreseededActorAssignmentReceiptRequest changedSelector =
            receiptReadRequest.toBuilder()
                .setCharacterUuid("90000000-0000-4000-8000-000000000009")
                .build();
        assertReceiptReadCallDenied(
            entityServer, "world-client", changedSelector, Status.Code.NOT_FOUND);
        assertThat(assignmentAndActorRows(assignment, characterUuid))
            .containsExactlyElementsOf(persistedRows);
        assertNoMutation(assignedBaseline, assignment);

        byte[] terminalResponse = assigned.toByteArray();
        AssignPreseededActorResponse replay =
            call(entityServer, "game-session-client", wireRequest);
        assertThat(replay.toByteArray()).containsExactly(terminalResponse);
        assertThat(assignmentAndActorRows(assignment, characterUuid))
            .containsExactlyElementsOf(persistedRows);
        assertThat(characterRepository.readPreseededActorAssignmentReceipt(receiptRequest))
            .contains(receipt);

        // The separately granted changed intent reuses the operation UUID and reaches the existing
        // Entity writer's immutable conflict outcome without disclosing the stored actor UUID.
        var changedCoreRequest =
            new PreseededActorAssignmentRequest(
                assignment.assignmentUuid(),
                assignment.canonicalAccountUuid(),
                new PreseededActorCorePayload(ActorKind.PLAYER, "Ari Changed"),
                assignment.expectedTarget());
        writeCapability(
            capabilityFile, changedCoreRequest, RUN_ID, PROJECT, trustRootFingerprint());
        AssignPreseededActorResponse conflict =
            call(entityServer, "game-session-client", toWire(changedCoreRequest));
        assertThat(conflict.getOutcome())
            .isEqualTo(
                PreseededActorAssignmentOutcome
                    .PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT);
        assertThat(conflict.hasCharacterUuid()).isFalse();
        assertThat(assignmentAndActorRows(assignment, characterUuid))
            .containsExactlyElementsOf(persistedRows);
        assertThat(characterRepository.readPreseededActorAssignmentReceipt(receiptRequest))
            .contains(receipt);
      } finally {
        entityServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      for (Server server : sourceServers) {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private CharacterRepository observeRepositoryEntry(CharacterRepository repository) {
    ProxyFactory proxy = new ProxyFactory(repository);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        (MethodInterceptor)
            invocation -> {
              if (invocation.getMethod().getName().equals("assignPreseededActor")) {
                writerEntryCount.incrementAndGet();
                writerEnteredOutsideTransaction.set(
                    !TransactionSynchronizationManager.isActualTransactionActive());
                if (!writerEnteredOutsideTransaction.get()) {
                  throw new IllegalStateException(
                      "Assignment writer was entered inside a caller-owned SQL transaction");
                }
              } else if (invocation
                  .getMethod()
                  .getName()
                  .equals("readPreseededActorAssignmentReceipt")) {
                receiptReadEntryCount.incrementAndGet();
                receiptReadEnteredOutsideTransaction.set(
                    !TransactionSynchronizationManager.isActualTransactionActive());
                receiptReadCallerUri.set(GrpcPeerIdentity.current().uri());
                if (!receiptReadEnteredOutsideTransaction.get()) {
                  throw new IllegalStateException(
                      "Assignment receipt read was entered inside a caller-owned SQL transaction");
                }
              }
              return invocation.proceed();
            });
    return (CharacterRepository) proxy.getProxy();
  }

  private final AtomicInteger writerEntryCount = new AtomicInteger();
  private final AtomicBoolean writerEnteredOutsideTransaction = new AtomicBoolean();
  private final AtomicInteger receiptReadEntryCount = new AtomicInteger();
  private final AtomicBoolean receiptReadEnteredOutsideTransaction = new AtomicBoolean();
  private final AtomicReference<String> receiptReadCallerUri = new AtomicReference<>();

  private MutationCounts mutationCounts(PreseededActorAssignmentRequest request) {
    UUID tenant = request.expectedTarget().canonicalTenantUuid();
    UUID namespace = request.expectedTarget().playableStateNamespaceId();
    return new MutationCounts(
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_tenant_identities WHERE canonical_tenant_uuid = ?",
                Long.class,
                tenant),
            "Tenant identity count query returned null"),
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_playable_state_namespace_scopes "
                    + "WHERE tenant_uuid = ? AND playable_state_namespace_id = ?",
                Long.class,
                tenant,
                namespace),
            "Playable namespace scope count query returned null"),
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE tenant_uuid = ? AND account_uuid = ? "
                    + "AND playable_state_namespace_id = ? "
                    + "AND actor_identity_status = 'OWNER_RESOLVED'",
                Long.class,
                tenant,
                request.canonicalAccountUuid(),
                namespace),
            "Owner-resolved character count query returned null"),
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_preseeded_actor_assignment_operations "
                    + "WHERE assignment_uuid = ?",
                Long.class,
                request.assignmentUuid()),
            "Assignment operation count query returned null"));
  }

  private void assertNoMutation(MutationCounts baseline, PreseededActorAssignmentRequest request) {
    assertThat(mutationCounts(request)).isEqualTo(baseline);
  }

  private List<String> assignmentAndActorRows(
      PreseededActorAssignmentRequest request, UUID characterUuid) {
    return jdbcTemplate.queryForList(
        "SELECT 'actor:' || xmin::text || ':' || row_to_json(c)::text AS row_value "
            + "FROM characters c WHERE character_uuid = ? UNION ALL "
            + "SELECT 'receipt:' || xmin::text || ':' || row_to_json(o)::text AS row_value "
            + "FROM entity_preseeded_actor_assignment_operations o WHERE assignment_uuid = ? "
            + "ORDER BY row_value",
        String.class,
        characterUuid,
        request.assignmentUuid());
  }

  private static void assertCallDenied(
      Server server,
      String callerAlias,
      AssignPreseededActorRequest request,
      Status.Code expectedCode)
      throws Exception {
    assertThatThrownBy(() -> call(server, callerAlias, request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expectedCode));
  }

  private static void assertReceiptReadCallDenied(
      Server server,
      String callerAlias,
      ReadPreseededActorAssignmentReceiptRequest request,
      Status.Code expectedCode)
      throws Exception {
    assertThatThrownBy(() -> callReceiptRead(server, callerAlias, request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expectedCode));
  }

  private static AssignPreseededActorResponse call(
      Server server, String callerAlias, AssignPreseededActorRequest request) throws Exception {
    CommonGrpcClientProperties callerTls =
        TLS.pki().clientProperties(TLS_DIRECTORY, TLS.pki().identity(callerAlias));
    ManagedChannel channel =
        new GrpcChannelFactory()
            .buildChannel("127.0.0.1:" + server.getPort(), 6565, callerTls, false);
    try {
      return net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc
          .newBlockingStub(channel)
          .withDeadlineAfter(15, TimeUnit.SECONDS)
          .assignPreseededActor(request);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static ReadPreseededActorAssignmentReceiptResponse callReceiptRead(
      Server server, String callerAlias, ReadPreseededActorAssignmentReceiptRequest request)
      throws Exception {
    CommonGrpcClientProperties callerTls =
        TLS.pki().clientProperties(TLS_DIRECTORY, TLS.pki().identity(callerAlias));
    ManagedChannel channel =
        new GrpcChannelFactory()
            .buildChannel("127.0.0.1:" + server.getPort(), 6565, callerTls, false);
    try {
      return CanonicalPreseededActorAssignmentServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(15, TimeUnit.SECONDS)
          .readPreseededActorAssignmentReceipt(request);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static Server boundedServer(
      String serverAlias, BindableService service, ServerInterceptor... interceptors)
      throws Exception {
    return boundedServer(serverAlias, new BindableService[] {service}, interceptors);
  }

  private static Server boundedServer(
      String serverAlias,
      BindableService first,
      BindableService second,
      ServerInterceptor... interceptors)
      throws Exception {
    return boundedServer(serverAlias, new BindableService[] {first, second}, interceptors);
  }

  private static Server boundedServer(
      String serverAlias, BindableService[] services, ServerInterceptor... interceptors)
      throws Exception {
    var identity = TLS.pki().identity(serverAlias);
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            TLS.pki().keyManagerFactory(identity),
            TLS.pki().trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    for (BindableService service : services) {
      factory.addService(ServerInterceptors.intercept(service, interceptors));
    }
    return factory.createServer().start();
  }

  private Server boundedEntityServer(BindableService service, ServerInterceptor... interceptors)
      throws Exception {
    var managers = sslBundles.getBundle("firemud-grpc").getManagers();
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            managers.getKeyManagerFactory(),
            managers.getTrustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, interceptors));
    return factory.createServer().start();
  }

  private static PreseededActorAssignmentRequest assignmentRequest(
      PreseededActorAssignmentOwnerReadEvidence.Request source) {
    return new PreseededActorAssignmentRequest(
        source.assignmentUuid(),
        source.canonicalAccountUuid(),
        new PreseededActorCorePayload(ActorKind.PLAYER, "Ari"),
        new PreseededActorAssignmentExpectedTarget(
            source.canonicalTenantUuid(),
            source.realmUuid(),
            source.worldSlug(),
            source.realmSlug(),
            source.canonicalGameInstanceUuid().toString(),
            source.expectedCatalogRevision(),
            source.canonicalVersionUuid(),
            source.expectedPolicyDigest(),
            source.playableStateNamespaceUuid(),
            source.expectedPublishedReleaseBundleRef(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
  }

  private static AssignPreseededActorRequest toWire(PreseededActorAssignmentRequest request) {
    var target = request.expectedTarget();
    return AssignPreseededActorRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.assignmentUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setExpectedTarget(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
                .newBuilder()
                .setCanonicalTenantUuid(target.canonicalTenantUuid().toString())
                .setRealmUuid(target.realmUuid().toString())
                .setWorldSlug(target.worldSlug())
                .setRealmSlug(target.realmSlug())
                .setGameInstanceId(target.gameInstanceId())
                .setCatalogRevision(target.catalogRevision())
                .setCanonicalVersionUuid(target.canonicalVersionUuid().toString())
                .setFrozenPolicyDigest(target.frozenPolicyDigest())
                .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
                .setPublishedReleaseBundleRef(target.publishedReleaseBundleRef())
                .setPlayableStateScope(target.playableStateScope())
                .build())
        .setCorePayload(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                .newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName(request.corePayload().displayName())
                .build())
        .setMutationIntentDigest(request.mutationIntentDigest())
        .build();
  }

  private static ReadPreseededActorAssignmentReceiptRequest toReceiptReadWire(
      PreseededActorAssignmentRequest request, UUID characterUuid, UUID placementOperationUuid) {
    return ReadPreseededActorAssignmentReceiptRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(placementOperationUuid.toString())
        .setAssignmentUuid(request.assignmentUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setCharacterUuid(characterUuid.toString())
        .setIntentDigest(request.mutationIntentDigest())
        .setExpectedTarget(toWire(request).getExpectedTarget())
        .build();
  }

  private static OwnerFixture readOwnerFixture() throws Exception {
    try (InputStream input =
            PreseededActorAssignmentOwnerCompositionPostgresIntegrationTest.class
                .getResourceAsStream("/gameplay/preseeded-assignment-owner-read.bin.gz");
        GZIPInputStream gzip = new GZIPInputStream(input)) {
      var response = GetPreseededActorAssignmentOwnerReadResponse.parseFrom(gzip.readAllBytes());
      var request = PreseededActorAssignmentOwnerReadGrpcCodec.fromRequest(response.getRequest());
      return new OwnerFixture(
          request,
          PreseededActorAssignmentOwnerReadGrpcCodec.fromResponse(request, response),
          response);
    }
  }

  private static void writeCapability(
      Path file,
      PreseededActorAssignmentRequest request,
      String runId,
      String project,
      String trustRootFingerprint)
      throws Exception {
    if (Files.exists(file)) {
      Files.setPosixFilePermissions(
          file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    Files.writeString(file, capabilityJson(request, runId, project, trustRootFingerprint));
    Files.setPosixFilePermissions(
        file,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
  }

  private static String capabilityJson(
      PreseededActorAssignmentRequest request,
      String runId,
      String project,
      String trustRootFingerprint) {
    var target = request.expectedTarget();
    return """
        {\
          "schema": "firemud.run-owned-preseeded-actor-assignment.v3",%n\
          "runId": "%s",%n\
          "composeProjectName": "%s",%n\
          "action": "PRESEEDED_ACTOR_ASSIGNMENT",%n\
          "trustedGameSessionUriSan": "%s",%n\
          "trustedGameSessionNamespace": "%s",%n\
          "assignmentUuid": "%s",%n\
          "canonicalAccountUuid": "%s",%n\
          "expectedTarget": {%n\
            "canonicalTenantUuid": "%s",%n\
            "realmUuid": "%s",%n\
            "worldSlug": "%s",%n\
            "realmSlug": "%s",%n\
            "gameInstanceId": "%s",%n\
            "catalogRevision": %d,%n\
            "canonicalVersionUuid": "%s",%n\
            "frozenPolicyDigest": "%s",%n\
            "playableStateNamespaceUuid": "%s",%n\
            "publishedReleaseBundleRef": "%s",%n\
            "playableStateScope": "%s"%n\
          },%n\
          "corePayload": {%n\
            "actorKind": "PLAYER",%n\
            "displayName": "%s"%n\
          },%n\
          "intentDigest": "%s",%n\
          "entityServerTrustRootSha256": "%s"%n\
        }%n\
        """
        .formatted(
            runId,
            project,
            GAME_SESSION_URI,
            NAMESPACE,
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            target.canonicalTenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceId(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.frozenPolicyDigest(),
            target.playableStateNamespaceId(),
            target.publishedReleaseBundleRef(),
            target.playableStateScope().name(),
            request.corePayload().displayName(),
            request.mutationIntentDigest(),
            trustRootFingerprint)
        .replace(System.lineSeparator(), "\n");
  }

  private static void writeCapabilityUnchecked(
      Path file, PreseededActorAssignmentRequest request, String runId, String project) {
    try {
      writeCapability(file, request, runId, project, trustRootFingerprint());
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to update controlled test capability", failure);
    }
  }

  private static String trustRootFingerprint() throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(TLS.pki().caCertificate().getEncoded()));
  }

  private static void setDirectoryPermissions(Path directory) throws Exception {
    Files.setPosixFilePermissions(
        directory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  private static Path createTlsDirectory() {
    try {
      return Files.createTempDirectory("entity-preseeded-assignment-owner-composition-");
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static TestTls createTestTls(Path directory) {
    try {
      var pki =
          LoopbackMtlsTestPki.create(
              directory,
              "Entity preseeded assignment owner composition test CA",
              identity("entity-server", NAMESPACE, "entity-management-service", true),
              identity("account-server", NAMESPACE, "account-service", true),
              identity("game-session-server", NAMESPACE, "game-session-service", true),
              identity("entity-client", NAMESPACE, "entity-management-service", false),
              identity("game-session-client", NAMESPACE, "game-session-service", false),
              identity("world-client", NAMESPACE, "world-management-service", false),
              identity("account-client", NAMESPACE, "account-service", false),
              identity("foreign-game-session-client", "other", "game-session-service", false),
              identity("foreign-world-client", "other", "world-management-service", false));
      var serverIdentity = pki.identity("entity-server");
      Path chain = directory.resolve("entity-server-chain.pem");
      Files.writeString(
          chain,
          pem("CERTIFICATE", serverIdentity.certificate().getEncoded())
              + pem("CERTIFICATE", pki.caCertificate().getEncoded()),
          StandardCharsets.US_ASCII);
      Path privateKey = directory.resolve("entity-server-key.pem");
      Files.writeString(
          privateKey,
          pem("PRIVATE KEY", serverIdentity.privateKey().getEncoded()),
          StandardCharsets.US_ASCII);
      Path caFile = directory.resolve("test-ca.crt");
      Files.setPosixFilePermissions(
          caFile,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ));
      return new TestTls(pki, caFile, chain, privateKey);
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static IdentitySpec identity(
      String alias, String namespace, String service, boolean server) {
    return new IdentitySpec(alias, "spiffe://firemud/ns/" + namespace + "/sa/" + service, server);
  }

  private static String pem(String type, byte[] encoded) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(encoded)
        + "\n-----END "
        + type
        + "-----\n";
  }

  private record OwnerFixture(
      PreseededActorAssignmentOwnerReadEvidence.Request request,
      PreseededActorAssignmentOwnerReadEvidence evidence,
      GetPreseededActorAssignmentOwnerReadResponse response) {}

  private record MutationCounts(
      long tenantIdentities, long namespaceScopes, long actors, long receipts) {}

  private record TestTls(
      LoopbackMtlsTestPki.Pki pki, Path caFile, Path entityServerChain, Path entityServerKey) {}

  private static final class ControlledRuntimeIdentityOwner
      extends RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceImplBase {
    private final AtomicBoolean returnWrongAccount = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastCallerUri = new AtomicReference<>();

    @Override
    public void resolveRuntimeAccountIdentity(
        ResolveRuntimeAccountIdentityRequest request,
        StreamObserver<ResolveRuntimeAccountIdentityResponse> observer) {
      calls.incrementAndGet();
      lastCallerUri.set(GrpcPeerIdentity.current().uri());
      observer.onNext(
          ResolveRuntimeAccountIdentityResponse.newBuilder()
              .setSchemaVersion(1)
              .setTargetNamespace(NAMESPACE)
              .setRequestId(request.getRequestId())
              .setCanonicalAccountId(
                  returnWrongAccount.get()
                      ? "99999999-9999-4999-8999-999999999999"
                      : request.getCanonicalAccountId())
              .setSourceAccountRowId(23L)
              .setAccountUuidProvenance("ACCOUNT_REPOSITORY_INSERT")
              .build());
      observer.onCompleted();
    }
  }

  private static final class ControlledStagingOwner
      extends AccountActorStagingEligibilityServiceGrpc
          .AccountActorStagingEligibilityServiceImplBase {
    private final AtomicBoolean returnIneligible = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastCallerUri = new AtomicReference<>();

    @Override
    public void resolvePreseededActorStagingEligibility(
        ResolvePreseededActorStagingEligibilityRequest request,
        StreamObserver<ResolvePreseededActorStagingEligibilityResponse> observer) {
      calls.incrementAndGet();
      lastCallerUri.set(GrpcPeerIdentity.current().uri());
      boolean eligible = !returnIneligible.get();
      AccountActorStagingEligibilityEvidence evidence =
          AccountActorStagingEligibilityEvidence.seal(
              request.getTargetNamespace(),
              UUID.fromString(request.getRequestId()),
              UUID.fromString(request.getCanonicalAccountId()),
              UUID.fromString(request.getCanonicalTenantId()),
              AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
              Instant.parse("2026-10-08T03:04:05Z"),
              "ACCOUNT_REPOSITORY_INSERT",
              "ACTIVE",
              eligible ? "ACTIVE" : "INACTIVE",
              eligible,
              "EXPLICIT_JOIN",
              17L,
              19L,
              "FRESH_GAME_DESIGN",
              UUID.fromString("77777777-7777-4777-8777-777777777777"),
              "sha256:" + "b".repeat(64),
              23L,
              UUID.fromString("88888888-8888-4888-8888-888888888888"),
              "sha256:" + "c".repeat(64),
              false);
      observer.onNext(stagingResponse(evidence));
      observer.onCompleted();
    }

    private static ResolvePreseededActorStagingEligibilityResponse stagingResponse(
        AccountActorStagingEligibilityEvidence evidence) {
      return ResolvePreseededActorStagingEligibilityResponse.newBuilder()
          .setSchemaVersion(evidence.schemaVersion())
          .setTargetNamespace(evidence.targetNamespace())
          .setRequestId(evidence.requestId().toString())
          .setCanonicalAccountId(evidence.canonicalAccountId().toString())
          .setCanonicalTenantId(evidence.canonicalTenantId().toString())
          .setPurpose(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY)
          .setCurrentness(ActorStagingEligibilityCurrentness.CURRENT_AT_REVALIDATION)
          .setObservedAt(
              Timestamp.newBuilder()
                  .setSeconds(evidence.observedAt().getEpochSecond())
                  .setNanos(evidence.observedAt().getNano())
                  .build())
          .setDecision(
              evidence.decision()
                      == AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE
                  ? ActorStagingEligibilityDecision.STAGING_ELIGIBLE
                  : ActorStagingEligibilityDecision.STAGING_INELIGIBLE)
          .setAccountUuidProvenance(evidence.accountUuidProvenance())
          .setAccountLifecycleState(evidence.accountLifecycleState())
          .setMembershipLifecycleState(evidence.membershipLifecycleState())
          .setGameplayAdmissionAllowed(evidence.gameplayAdmissionAllowed())
          .setMembershipAuthorityProvenance(evidence.membershipAuthorityProvenance())
          .setMembershipVersion(evidence.membershipVersion())
          .setMembershipAuthorityGeneration(evidence.membershipAuthorityGeneration())
          .setTenantProvenanceKind(evidence.tenantProvenanceKind())
          .setTenantSourceOperationId(evidence.tenantSourceOperationId().toString())
          .setTenantProvenanceDigest(evidence.tenantProvenanceDigest())
          .setMembershipEventSequence(evidence.membershipEventSequence())
          .setMembershipEventId(evidence.membershipEventId().toString())
          .setMembershipEventDigest(evidence.membershipEventDigest())
          .setLastTransitionInvalidated(evidence.lastTransitionInvalidated())
          .setEligibilityDecisionDigest(evidence.eligibilityDecisionDigest())
          .setAuthoritySnapshotDigest(evidence.authoritySnapshotDigest())
          .build();
    }
  }

  private static final class ControlledGameSessionOwner
      extends CanonicalGameplayRosterOwnerReadServiceGrpc
          .CanonicalGameplayRosterOwnerReadServiceImplBase {
    private final GetPreseededActorAssignmentOwnerReadResponse successResponse;
    private final AtomicReference<Status> nextFailure = new AtomicReference<>();
    private final AtomicBoolean returnStaleEvidence = new AtomicBoolean();
    private final AtomicReference<Runnable> afterNextRead = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastCallerUri = new AtomicReference<>();

    private ControlledGameSessionOwner(
        GetPreseededActorAssignmentOwnerReadResponse successResponse) {
      this.successResponse = successResponse;
    }

    @Override
    public void getPreseededActorAssignmentOwnerRead(
        GetPreseededActorAssignmentOwnerReadRequest request,
        StreamObserver<GetPreseededActorAssignmentOwnerReadResponse> observer) {
      calls.incrementAndGet();
      lastCallerUri.set(GrpcPeerIdentity.current().uri());
      Status failure = nextFailure.getAndSet(null);
      if (failure != null) {
        observer.onError(failure.asRuntimeException());
        return;
      }
      if (!request.equals(successResponse.getRequest())) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("controlled stale target")
                .asRuntimeException());
        return;
      }
      Runnable hook = afterNextRead.getAndSet(null);
      if (hook != null) {
        hook.run();
      }
      GetPreseededActorAssignmentOwnerReadResponse response =
          returnStaleEvidence.get()
              ? successResponse.toBuilder()
                  .setRequest(
                      successResponse.getRequest().toBuilder().setWorldSlug("stale").build())
                  .build()
              : successResponse;
      observer.onNext(response);
      observer.onCompleted();
    }
  }
}
