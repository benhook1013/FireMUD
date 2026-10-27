package net.firedevops.firemud.gamesession.repository;

import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.AclSetuserArgs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountInitialBarrierRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionBindingBridgeRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Test-only access to the package-private final coordinator. Every GS physical owner uses one
 * run-owned Redis role/database. Actual owners mint the candidate, member/issuer acknowledgements,
 * region grant, final CAS and SQL receipt. PKI, upstream World/Entity responses, initial response
 * delivery, signer custody, issuer checkpoint and incarnation observation remain controlled inputs.
 * The actual original region owner/private grant stays live across recreated coordinator calls.
 */
@SuppressWarnings("resource")
public final class CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture
    implements AutoCloseable {
  private static final Clock CLOCK = Clock.systemUTC();
  private final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);
  private final AtomicInteger finalReadCalls = new AtomicInteger();
  private RedisClient administrator;
  private StatefulRedisConnection<byte[], byte[]> administration;
  private RedisClient coordination;
  private CanonicalGameplayAdmissionAbortCompositionFixture binding;
  private CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources sources;
  private CanonicalGameplayTenantGameplayTagRepository tags;
  private RedisScriptCatalog scripts;
  private CanonicalGameplayControlledInitialObservationFixture attestor;
  private CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository;
  private CanonicalGameplayRegionBindingBridgeOwner region;
  private CanonicalGameplayRegionLeaseOwner.Installation regionInstallation;
  private CanonicalGameplayOriginalNewCandidateRedisOwner.Installation candidateInstallation;
  private CanonicalGameplayAdmissionDecision decision;
  private String initialBarrierKey;
  private Server server;

  public static CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture open(
      DriverManagerDataSource accountDataSource,
      Candidate candidate,
      CanonicalGameplayLoginResult initialLogin,
      Path directory) throws Exception {
    var fixture = new CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture();
    try {
      fixture.prepare(accountDataSource, candidate, initialLogin, directory);
      return fixture;
    } catch (Exception | Error failure) {
      try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture() {}

  private void prepare(DriverManagerDataSource accountDataSource, Candidate candidate,
      CanonicalGameplayLoginResult initialLogin, Path directory) throws Exception {
    redis.start();
    administrator = RedisClient.create(RedisURI.create(redis.getHost(), redis.getMappedPort(6379)));
    administration = administrator.connect(ByteArrayCodec.INSTANCE);
    String password = UUID.randomUUID().toString();
    administration.sync().aclSetuser("gamesession_coord_app", new AclSetuserArgs()
        .reset().on().addPassword(password).keyPattern("session:game:*")
        .keyPattern("tick:*").keyPattern("tick-executor-lease:*")
        .addCommand(CommandType.HELLO).addCommand(CommandType.PING)
        .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
        .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
        .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD).addCommand(CommandType.EVALSHA)
        .addCommand(CommandType.TYPE).addCommand(CommandType.PTTL)
        .addCommand(CommandType.GET).addCommand(CommandType.SET)
        .addCommand(CommandType.SADD).addCommand(CommandType.SISMEMBER)
        .addCommand(CommandType.HGET).addCommand(CommandType.HGETALL)
        .addCommand(CommandType.HSET).addCommand(CommandType.HLEN).addCommand(CommandType.HDEL)
        .addCommand(CommandType.PEXPIRE).addCommand(CommandType.TIME));
    coordination = RedisClient.create(RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379))
        .withAuthentication("gamesession_coord_app", password).build());
    coordination.setOptions(ClientOptions.builder().autoReconnect(false).build());
    binding = new CanonicalGameplayAdmissionAbortCompositionFixture(accountDataSource, candidate, coordination);
    tags = new CanonicalGameplayTenantGameplayTagRepository(binding.dsl(), "test", 1);
    scripts = RedisScriptCatalog.fromContributions(List.of(
        new CanonicalGameplayAccountInitialBarrierRedisScriptContribution(),
        new CanonicalGameplayAccountMemberRepairRedisScriptContribution(),
        new CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution(),
        new CanonicalGameplayOriginalNewCandidateRedisScriptContribution(),
        new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution(),
        new CanonicalGameplayRegionLeaseRedisScriptContribution(),
        new CanonicalGameplayRegionBindingBridgeRedisScriptContribution()));
    // This pre-lease description is used only to prepare controlled World/Entity response servers.
    // No controlled lease reaches candidate installation, finalization, or the final coordinator.
    sources = new CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources(
        binding.dsl(), binding.originalPreparedDecisionWithControlledAccountLease(), directory);
    region = CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(binding.dsl(), coordination,
        scripts, CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture::controlledRegionIncarnation,
        sources.resolver, sources.entity,
        new RuntimeIdentity("game-session-service", "actual-final-composition", "localhost",
            Instant.now(), "test", "test", "controlled-test-image"),
        Duration.ofSeconds(30), CLOCK);
    attestor = new CanonicalGameplayControlledInitialObservationFixture();
    initialRepository = new CanonicalGameplayAccountInitialBarrierInitializerRepository(binding.dsl());
    recordInitialLogin(accountDataSource, initialLogin);
    var evidence = initialLogin.evidence();
    var original = new InitialGameplayLoginAttemptCoordinates(evidence.requestId(), evidence.callerContextId(), 1L);
    var opened = initialOwner().initializeOnce(original, initialLogin,
        binding.candidate().tenantId(), binding.candidate().playableStateNamespaceId());
    if (!(opened instanceof CanonicalGameplayAccountInitialOpenProof.CurrentOpen actual)) {
      throw new IllegalStateException("Actual INITIAL owner did not establish current OPEN");
    }
    initialBarrierKey = actual.key();
  }

  private void recordInitialLogin(DriverManagerDataSource accountDataSource,
      CanonicalGameplayLoginResult initialLogin) {
    String schema = binding.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    var dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(java.util.Objects.requireNonNull(accountDataSource.getUrl())
        .replaceAll("currentSchema=[^&]+", "currentSchema=" + schema));
    dataSource.setUsername(accountDataSource.getUsername());
    dataSource.setPassword(accountDataSource.getPassword());
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var journal = new InitialGameplayLoginCoordinationJournalRepository(
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES));
    var evidence = initialLogin.evidence();
    var original = new InitialGameplayLoginAttemptCoordinates(evidence.requestId(), evidence.callerContextId(), 1L);
    transaction.executeWithoutResult(status -> journal.reserve(original));
    if (journal.readCommitted(original).isEmpty()) throw new IllegalStateException("Original journal reservation absent");
    transaction.executeWithoutResult(status -> journal.recordVerifiedResult(original, initialLogin));
    if (journal.readCommitted(original).orElseThrow().verifiedWitness().isEmpty()) {
      throw new IllegalStateException("Actual verified initial LOGIN witness absent");
    }
  }

  private CanonicalGameplayAccountInitialBarrierRedisOwner initialOwner() {
    return new CanonicalGameplayAccountInitialBarrierRedisOwner(coordination, scripts,
        initialRepository, tags, attestor.receiver(initialRepository, tags));
  }

  public Server startServer(KeyManagerFactory keys, TrustManagerFactory trust, UUID requestId) throws Exception {
    var factory = new BoundedGrpcServerFactory("127.0.0.1:0", List.of(), keys, trust,
        TlsServerCredentials.ClientAuth.REQUIRE, GrpcRequestBytePolicy.ordinary(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    var decisionGuard = new CanonicalGameplayAdmissionDecisionReadWorkloadGuard("test");
    var actualDecisionReceiver = new CanonicalGameplayAdmissionDecisionReadGrpcService(
        new CanonicalGameplayBindingInventoryRepository(binding.dsl()), decisionGuard, "test");
    // Only the upstream published-target purpose is controlled. Exact lease-bound provisional
    // reads delegate unchanged to the actual production receiver/repository.
    var controlledPublishedSource = new net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc
        .CanonicalGameplayAdmissionDecisionReadServiceImplBase() {
      @Override public void getCanonicalGameplayAdmissionDecision(
          net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest request,
          io.grpc.stub.StreamObserver<net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse> observer) {
        actualDecisionReceiver.getCanonicalGameplayAdmissionDecision(request, observer);
      }
      @Override public void getPublishedRealmAdmissionOwnerRead(
          net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest request,
          io.grpc.stub.StreamObserver<net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse> observer) {
        decisionGuard.requireAccountCaller();
        var parsed = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(request);
        var expected = publishedSelector(requestId);
        if (!expected.equals(parsed.selector())) {
          observer.onError(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
          return;
        }
        observer.onNext(CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
            parsed, controlledPublishedEvidence(expected), target()));
        observer.onCompleted();
      }
    };
    // Both handlers authenticate first; the actual decision-only interceptor intentionally
    // refuses the separate published-source purpose and cannot guard this test-only combined adapter.
    factory.addService(ServerInterceptors.interceptForward(controlledPublishedSource,
        new GrpcPeerIdentityInterceptor()));
    var finalGuard = new CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard("test");
    factory.addService(ServerInterceptors.interceptForward(
        new CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService(
            new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(binding.dsl(), coordination, tags, "test", CLOCK),
            finalGuard, "test"), new GrpcPeerIdentityInterceptor(), finalGuard, new FinalReadCounter()));
    server = factory.createServer().start();
    return server;
  }

  private net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
      publishedSelector(UUID requestId) {
    var target = target();
    return new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request(
        requestId, candidate().accountId(), "test", target.canonicalTenantId(), target.worldSlug(),
        target.realmId(), target.realmSlug(), target.playableStateNamespaceId(), target.playableStateScope(),
        target.canonicalGameInstanceId(), target.canonicalVersionId(), target.catalogRevision(),
        target.pointerVersion(), target.activeWorldEpoch());
  }

  private net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
      controlledPublishedEvidence(net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request selector) {
    var target = target();
    var holdRequest = new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
        "test", target.canonicalTenantId(), target.worldSlug(), target.realmId(),
        target.playableStateNamespaceId(), target.playableStateScope(), target.canonicalGameInstanceId(),
        target.canonicalVersionId(), target.activeWorldEpoch(), target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(), net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
            .InitialAdmissionOrigin.NO_PRIOR_POINTER, target.catalogRevision(), null);
    var hold = new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
        holdRequest, target.holdId(), target.holdFence());
    var controlledProof = new net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof(
        hold, net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        target.pointerVersion(), target.auditEventId(), target.ownerProofDigest(), false, target.ownerProofTerminalAt());
    byte[] retained = binding.dsl().fetchSingle("SELECT complete_policy_set_evidence FROM "
        + "game_session_canonical_realm_catalog WHERE realm_id = ?", target.realmId()).get(0, byte[].class);
    var published = net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec.decode(retained);
    var policies = published.policies().stream().map(policy ->
        new net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence(
            policy.policyId(), policy.source().commitId(), policy.source().revisionId(),
            policy.source().logicalRevisionId(), policy.source().policy(), policy.policyDigest())).toList();
    var complete = new net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence(
        published.target(), published.versionNumber(), published.sourceCommitId(), published.sourceEpoch(),
        published.publishedReleaseBundleRef(), published.publishedReleaseBundleDigest(), published.publishWorkflowId(),
        published.manifestHash(), published.publicationVersionStateEpoch(), published.operationBytes(),
        published.captureBytes(), published.terminalEvidenceBytes(), published.policyCount(), published.policySetDigest(), policies);
    return new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence(
        selector, target.admissionPointerSnapshotDigest(), controlledProof, complete);
  }

  public void installActualCandidate(AccountGameplayAdmissionLeaseEvidence actualLease) {
    decision = new CanonicalGameplayAdmissionDecision(binding.transitionId(), actualLease,
        new CanonicalGameplayBindingInventoryRepository(binding.dsl()).readProvisionalCasEvidence(binding.transitionId())
            .transition().candidate(), binding.generation(), null,
        binding.originalPreparedDecisionWithControlledAccountLease().targetEvidence());
    candidateInstallation = new CanonicalGameplayOriginalNewCandidateRedisOwner(coordination,
        scripts, new CanonicalGameplayBindingInventoryRepository(binding.dsl()), tags).installOnce(decision);
    new CanonicalGameplayAccountMemberRepairOwner(binding.dsl(), coordination, scripts).repair(binding.transitionId());
    binding.acknowledgeOriginalIssuer();
    regionInstallation = region.installForTransition(binding.transitionId(), sources.request);
    region.bridge(regionInstallation);
  }

  public Receipt complete(AccountGameplayAdmissionLeaseClient account,
      AccountPublicJwksCache jwks, String originalLoginJwt, long deadline) {
    // Recreate the coordinator and its SQL/INITIAL/issuer/final owners on every invocation.
    var issuerSource = binding.currentIssuerSource();
    var issuer = new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(coordination, scripts,
        new CanonicalGameplayIssuerAdmissionBarrierRepository(binding.dsl(), tags, issuerSource), issuerSource);
    var coordinator = new CanonicalGameplayOriginalNewFinalAdmissionCoordinator(
        new CanonicalGameplayBindingInventoryRepository(binding.dsl()), account, initialOwner(), issuer,
        region, new CanonicalGameplayOriginalNewBoundCredentialVerifier(jwks, CLOCK),
        new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(coordination, scripts, CLOCK, tags), CLOCK);
    var result = coordinator.completeOriginalNew(decision, originalLoginJwt, regionInstallation, 120_000L, deadline);
    return new Receipt(result.transitionId(), result.admissionAtMillis(), result.continuityExpiresAtMillis(),
        result.canonicalSessionDurationMillis(), result.finalSessionSha256());
  }

  public Candidate candidate() { return binding.candidate(); }
  public Target target() { return binding.target(); }
  public java.math.BigInteger generation() { return binding.generation(); }
  public UUID decisionId() { return binding.transitionId(); }
  public DSLContext dsl() { return binding.dsl(); }
  public int finalReadCalls() { return finalReadCalls.get(); }
  public byte[] physicalSession() { return administration.sync().get(ascii(candidateInstallation.sessionKey())); }
  public void removeInitialBarrierForFault() { administration.sync().del(new byte[][] {ascii(initialBarrierKey)}); }
  public boolean initialBarrierAbsent() { return administration.sync().exists(ascii(initialBarrierKey)) == 0L; }

  public record Receipt(UUID transitionId, long admissionAtMillis, long continuityExpiresAtMillis,
      long canonicalSessionDurationMillis, String finalSessionSha256) {}

  private final class FinalReadCounter implements ServerInterceptor {
    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, io.grpc.Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      finalReadCalls.incrementAndGet();
      return next.startCall(call, headers);
    }
  }

  /** Controlled stable deployment source; never used to reconstruct a private region grant. */
  private static CanonicalGameplayRegionIncarnationSource.StableEvidence controlledRegionIncarnation(
      CanonicalGameplayRegionIncarnationSource.RegionScope scope) {
    var deployment = new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
        "controlled-final-region", "generation-1", List.of("node-a"), "redis-build-1", "registry-v1",
        "sha256:" + "a".repeat(64), "READY", "boot-1", "startup-1", "storage-1", "aof-1");
    return CanonicalGameplayRegionIncarnationSource.StableEvidence.fromCurrentSource(scope,
        "controlled-final-region-attestor", "b".repeat(64), deployment, "c".repeat(64), "d".repeat(64),
        true, "controlled-non-reset-profile-v1", "e".repeat(64), true, "f".repeat(64), true, "1".repeat(64));
  }

  private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

  @Override public void close() throws Exception {
    try {
      if (server != null) {
        server.shutdownNow();
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("GS final server teardown timed out");
      }
    } finally {
      try { if (sources != null) sources.close(); }
      finally {
        try { if (binding != null) binding.close(); }
        finally {
          try { if (administration != null) administration.close(); }
          finally {
            try { if (coordination != null) coordination.shutdown(); }
            finally { try { if (administrator != null) administrator.shutdown(); } finally { redis.stop(); } }
          }
        }
      }
    }
  }
}
