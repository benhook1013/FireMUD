package net.firedevops.firemud.gamesession.repository;

import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
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
  private static final int TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS = 128;
  private static final long TEST_CAPACITY_EVIDENCE_BYTES_PER_RECORD = 1_048_576L;
  private static final String SECOND_ADMISSION_SEED = "after-quota-recovery";
  private final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);
  private final AtomicInteger finalReadCalls = new AtomicInteger();
  private final Set<UUID> requestIds = ConcurrentHashMap.newKeySet();
  private RedisClient administrator;
  private StatefulRedisConnection<byte[], byte[]> administration;
  private RedisClient coordination;
  private RedisURI coordinationUri;
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
      Path directory)
      throws Exception {
    var fixture = new CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture();
    try {
      fixture.prepare(accountDataSource, candidate, initialLogin, directory);
      return fixture;
    } catch (Exception | Error failure) {
      try {
        fixture.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture() {}

  private void prepare(
      DriverManagerDataSource accountDataSource,
      Candidate candidate,
      CanonicalGameplayLoginResult initialLogin,
      Path directory)
      throws Exception {
    redis.start();
    administrator = RedisClient.create(RedisURI.create(redis.getHost(), redis.getMappedPort(6379)));
    administration = administrator.connect(ByteArrayCodec.INSTANCE);
    String password = UUID.randomUUID().toString();
    administration
        .sync()
        .aclSetuser(
            "gamesession_coord_app",
            new AclSetuserArgs()
                .reset()
                .on()
                .addPassword(password)
                .keyPattern("session:game:*")
                .keyPattern("tick:*")
                .keyPattern("tick-executor-lease:*")
                .addCommand(CommandType.HELLO)
                .addCommand(CommandType.PING)
                .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
                .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
                .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
                .addCommand(CommandType.EVALSHA)
                .addCommand(CommandType.TYPE)
                .addCommand(CommandType.PTTL)
                .addCommand(CommandType.GET)
                .addCommand(CommandType.SET)
                .addCommand(CommandType.SADD)
                .addCommand(CommandType.SISMEMBER)
                .addCommand(CommandType.HGET)
                .addCommand(CommandType.HGETALL)
                .addCommand(CommandType.HSET)
                .addCommand(CommandType.HLEN)
                .addCommand(CommandType.HDEL)
                .addCommand(CommandType.PEXPIRE)
                .addCommand(CommandType.TIME));
    coordinationUri =
        RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379))
            .withAuthentication("gamesession_coord_app", password)
            .build();
    coordination = RedisClient.create(coordinationUri);
    coordination.setOptions(ClientOptions.builder().autoReconnect(false).build());
    binding =
        new CanonicalGameplayAdmissionAbortCompositionFixture(
            accountDataSource, candidate, coordination);
    tags = new CanonicalGameplayTenantGameplayTagRepository(binding.dsl(), "test", 1);
    scripts =
        RedisScriptCatalog.fromContributions(
            List.of(
                new CanonicalGameplayAccountInitialBarrierRedisScriptContribution(),
                new CanonicalGameplayAccountMemberRepairRedisScriptContribution(),
                new CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution(),
                new CanonicalGameplayOriginalNewCandidateRedisScriptContribution(),
                new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution(),
                new CanonicalGameplayRegionLeaseRedisScriptContribution(),
                new CanonicalGameplayRegionBindingBridgeRedisScriptContribution()));
    // This pre-lease description is used only to prepare controlled World/Entity response servers.
    // No controlled lease reaches candidate installation, finalization, or the final coordinator.
    sources =
        new CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources(
            binding.dsl(), binding.originalPreparedDecisionWithControlledAccountLease(), directory);
    region =
        CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(
            binding.dsl(),
            coordination,
            coordinationUri,
            scripts,
            CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture
                ::controlledRegionIncarnation,
            sources.resolver,
            sources.entity,
            new RuntimeIdentity(
                "game-session-service",
                "actual-final-composition",
                "localhost",
                Instant.now(),
                "test",
                "test",
                "controlled-test-image"),
            Duration.ofSeconds(30),
            CLOCK);
    attestor = new CanonicalGameplayControlledInitialObservationFixture();
    initialRepository =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository(binding.dsl());
    recordInitialLogin(accountDataSource, initialLogin);
    var evidence = initialLogin.evidence();
    var original =
        new InitialGameplayLoginAttemptCoordinates(
            evidence.requestId(), evidence.callerContextId(), 1L);
    var opened =
        initialOwner()
            .initializeOnce(
                original,
                initialLogin,
                binding.candidate().tenantId(),
                binding.candidate().playableStateNamespaceId());
    if (!(opened instanceof CanonicalGameplayAccountInitialOpenProof.CurrentOpen actual)) {
      throw new IllegalStateException("Actual INITIAL owner did not establish current OPEN");
    }
    initialBarrierKey = actual.key();
  }

  private void recordInitialLogin(
      DriverManagerDataSource accountDataSource, CanonicalGameplayLoginResult initialLogin) {
    String schema = binding.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    var dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(
        java.util.Objects.requireNonNull(accountDataSource.getUrl())
            .replaceAll("currentSchema=[^&]+", "currentSchema=" + schema));
    dataSource.setUsername(accountDataSource.getUsername());
    dataSource.setPassword(accountDataSource.getPassword());
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var journal =
        new InitialGameplayLoginCoordinationJournalRepository(
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES));
    var evidence = initialLogin.evidence();
    var original =
        new InitialGameplayLoginAttemptCoordinates(
            evidence.requestId(), evidence.callerContextId(), 1L);
    transaction.executeWithoutResult(status -> journal.reserve(original));
    if (journal.readCommitted(original).isEmpty())
      throw new IllegalStateException("Original journal reservation absent");
    transaction.executeWithoutResult(
        status -> journal.recordVerifiedResult(original, initialLogin));
    if (journal.readCommitted(original).orElseThrow().verifiedWitness().isEmpty()) {
      throw new IllegalStateException("Actual verified initial LOGIN witness absent");
    }
  }

  private CanonicalGameplayAccountInitialBarrierRedisOwner initialOwner() {
    return new CanonicalGameplayAccountInitialBarrierRedisOwner(
        coordination, scripts, initialRepository, tags, attestor.receiver(initialRepository, tags));
  }

  public Server startServer(KeyManagerFactory keys, TrustManagerFactory trust, UUID requestId)
      throws Exception {
    requestIds.add(requestId);
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            keys,
            trust,
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    var decisionGuard = new CanonicalGameplayAdmissionDecisionReadWorkloadGuard("test");
    var actualDecisionReceiver =
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
            new CanonicalGameplayBindingInventoryRepository(binding.dsl()), decisionGuard, "test");
    // Only the upstream published-target purpose is controlled. Exact lease-bound provisional
    // reads delegate unchanged to the actual production receiver/repository.
    var controlledPublishedSource =
        new net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .CanonicalGameplayAdmissionDecisionReadServiceImplBase() {
          @Override
          public void getCanonicalGameplayAdmissionDecision(
              net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest
                  request,
              io.grpc.stub.StreamObserver<
                      net.firedevops.firemud.gamesession.v1
                          .GetCanonicalGameplayAdmissionDecisionResponse>
                  observer) {
            actualDecisionReceiver.getCanonicalGameplayAdmissionDecision(request, observer);
          }

          @Override
          public void resolvePublishedRealmAdmissionTarget(
              net.firedevops.firemud.gamesession.v1.ResolvePublishedRealmAdmissionTargetRequest
                  request,
              io.grpc.stub.StreamObserver<
                      net.firedevops.firemud.gamesession.v1
                          .ResolvePublishedRealmAdmissionTargetResponse>
                  observer) {
            decisionGuard.requireAccountCaller();
            final CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector parsed;
            try {
              parsed =
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(
                      request);
            } catch (IllegalArgumentException malformed) {
              observer.onError(io.grpc.Status.INVALID_ARGUMENT.asRuntimeException());
              return;
            }
            if (!requestIds.contains(parsed.readRequestId())
                || !candidate().accountId().equals(parsed.accountId())) {
              observer.onError(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
              return;
            }
            try {
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.requireRetainedTargetMatches(
                  parsed, target());
            } catch (IllegalArgumentException changedTarget) {
              observer.onError(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
              return;
            }
            observer.onNext(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
                    parsed, target()));
            observer.onCompleted();
          }

          @Override
          public void getPublishedRealmAdmissionOwnerRead(
              net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest
                  request,
              io.grpc.stub.StreamObserver<
                      net.firedevops.firemud.gamesession.v1
                          .GetPublishedRealmAdmissionOwnerReadResponse>
                  observer) {
            decisionGuard.requireAccountCaller();
            var parsed =
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(request);
            UUID selectedRequestId = parsed.selector().requestUuid();
            var expected = publishedSelector(selectedRequestId);
            if (!requestIds.contains(selectedRequestId) || !expected.equals(parsed.selector())) {
              observer.onError(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
              return;
            }
            observer.onNext(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
                    parsed,
                    controlledPublishedEvidence(expected),
                    target(),
                    controlledWorldLifecycle(expected)));
            observer.onCompleted();
          }
        };
    // Every handler authenticates first. The upstream publication and World lifecycle captures
    // remain explicitly controlled; the actual retained-decision receiver is unchanged.
    factory.addService(
        ServerInterceptors.interceptForward(
            controlledPublishedSource, new GrpcPeerIdentityInterceptor()));
    var finalGuard = new CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard("test");
    factory.addService(
        ServerInterceptors.interceptForward(
            new CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService(
                new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
                    binding.dsl(), coordination, tags, "test", CLOCK),
                finalGuard,
                "test"),
            new GrpcPeerIdentityInterceptor(),
            finalGuard,
            new FinalReadCounter()));
    server = factory.createServer().start();
    return server;
  }

  private net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
      publishedSelector(UUID requestId) {
    var target = target();
    return new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request(
        requestId,
        candidate().accountId(),
        "test",
        target.canonicalTenantId(),
        target.worldSlug(),
        target.realmId(),
        target.realmSlug(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.canonicalVersionId(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.activeWorldEpoch());
  }

  private net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
      controlledPublishedEvidence(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
              selector) {
    var target = target();
    var holdRequest =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
            "test",
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            target.canonicalVersionId(),
            target.activeWorldEpoch(),
            target.initialAdmissionRequestId(),
            target.initialAdmissionRequestDigest(),
            net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                .InitialAdmissionOrigin.NO_PRIOR_POINTER,
            target.catalogRevision(),
            null);
    var hold =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest, target.holdId(), target.holdFence());
    var controlledProof =
        new net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof
                .Outcome.COMMITTED,
            target.pointerVersion(),
            target.auditEventId(),
            target.ownerProofDigest(),
            false,
            target.ownerProofTerminalAt());
    byte[] retained =
        binding
            .dsl()
            .fetchSingle(
                "SELECT complete_policy_set_evidence FROM "
                    + "game_session_canonical_realm_catalog WHERE realm_id = ?",
                target.realmId())
            .get(0, byte[].class);
    var published =
        net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec.decode(retained);
    var policies =
        published.policies().stream()
            .map(
                policy ->
                    new net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence(
                        policy.policyId(),
                        policy.source().commitId(),
                        policy.source().revisionId(),
                        policy.source().logicalRevisionId(),
                        policy.source().policy(),
                        policy.policyDigest()))
            .toList();
    var complete =
        new net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence(
            published.target(),
            published.versionNumber(),
            published.sourceCommitId(),
            published.sourceEpoch(),
            published.publishedReleaseBundleRef(),
            published.publishedReleaseBundleDigest(),
            published.publishWorkflowId(),
            published.manifestHash(),
            published.publicationVersionStateEpoch(),
            published.operationBytes(),
            published.captureBytes(),
            published.terminalEvidenceBytes(),
            published.policyCount(),
            published.policySetDigest(),
            policies);
    return new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence(
        selector, target.admissionPointerSnapshotDigest(), controlledProof, complete);
  }

  /** Retained launch descriptor and publication selector with controlled World capture facts. */
  private net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
      controlledWorldLifecycle(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
              selector) {
    var target = target();
    var launch =
        new CanonicalGameInstanceLaunchAssociationRepository(binding.dsl(), "test")
            .readForInitialAdmission(
                "test",
                target.canonicalTenantId(),
                target.realmId(),
                target.canonicalGameInstanceId())
            .orElseThrow()
            .association();
    var descriptor = launch.launchBindingEvidence().descriptor();
    // The stored Game Session launch release is intentionally minimal. Rebuild the controlled
    // World binding from this target's retained complete publication, keeping the actual launch
    // descriptor and using its retained start-location selector rather than a guessed room.
    byte[] policyBytes =
        java.util.Objects.requireNonNull(
            binding
                .dsl()
                .fetchSingle(
                    "SELECT complete_policy_set_evidence FROM game_session_canonical_realm_catalog "
                        + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ?",
                    "test",
                    target.canonicalTenantId(),
                    target.realmId())
                .get(0, byte[].class),
            "Controlled complete publication fixture is required");
    var publishedSet =
        net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec.decode(
            policyBytes);
    var publishedRelease =
        net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.fromStored(
                publishedSet.terminalEvidenceBytes())
            .releaseContent();
    var worldSelector = publishedRelease.worldStartLocationEvidence();
    var participants =
        publishedRelease.participantDigests().stream()
            .map(
                participant ->
                    new net.firedevops.firemud.common.gamedesign
                        .AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        false,
                        null,
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        participant.digestSchemaVersion(),
                        participant.abilitySchemaDigest() != null,
                        participant.abilitySchemaDigest()))
            .toList();
    var release =
        net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            publishedRelease.canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            publishedRelease.publishWorkflowId(),
            worldSelector.request().appliedCommitId(),
            participants,
            publishedRelease.manifestHash(),
            publishedRelease.manifestSchemaVersion(),
            publishedRelease.requiredManifestAssetKeys(),
            publishedRelease.artifactDigests(),
            publishedRelease.commandDefinitions(),
            descriptor.generationConfigRevision(),
            worldSelector);
    var complete =
        new net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence(
            descriptor, release);
    var start =
        net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence.fromStored(
            worldSelector.selectorReceiptBytes());
    var request =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request(
            1,
            selector.requestUuid(),
            "test",
            target.canonicalTenantId(),
            target.worldSlug(),
            target.canonicalGameInstanceId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            true,
            launch.controlPlaneRequestId(),
            target.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
        request,
        complete,
        start.startLocation(),
        1L,
        "ACTIVE",
        target.activeWorldEpoch(),
        1L,
        UUID.nameUUIDFromBytes(
            ("controlled-world-capture:" + selector.requestUuid())
                .getBytes(StandardCharsets.UTF_8)),
        start.graphDigest().substring(7),
        "sha256:" + "a".repeat(64),
        java.util.Map.of(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222")));
  }

  public void installActualCandidate(AccountGameplayAdmissionLeaseEvidence actualLease) {
    installActualCandidate(
        actualLease,
        TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
        TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS);
  }

  public void installActualCandidate(
      AccountGameplayAdmissionLeaseEvidence actualLease,
      int maximumActiveRecords,
      int maximumRetainedRecords) {
    decision = originalDecision(binding.transitionId(), actualLease);
    candidateInstallation = installCandidateAndRepairAccountMember(decision);
    binding.acknowledgeOriginalIssuer();
    regionInstallation = region.installForTransition(binding.transitionId(), sources.request);
    region.bridge(regionInstallation);
    var assignment = tags.assignmentForTransition(binding.transitionId());
    binding
        .dsl()
        .execute(
            "INSERT INTO game_session_capacity_evidence_budget"
                + " (tenant_id,maximum_records,maximum_binding_bytes,maximum_retained_records)"
                + " VALUES (?,?,?,?)"
                + " ON CONFLICT DO NOTHING",
            assignment.tenantId(),
            maximumActiveRecords,
            maximumActiveRecords * TEST_CAPACITY_EVIDENCE_BYTES_PER_RECORD,
            maximumRetainedRecords);
    binding
        .dsl()
        .execute(
            "INSERT INTO game_session_region_transfer_history_budget"
                + " (tenant_id,maximum_transfers,maximum_items) VALUES (?,256,1024)",
            assignment.tenantId());
  }

  private CanonicalGameplayAdmissionDecision originalDecision(
      UUID transitionId, AccountGameplayAdmissionLeaseEvidence actualLease) {
    var transition =
        new CanonicalGameplayBindingInventoryRepository(binding.dsl())
            .readProvisionalCasEvidence(transitionId)
            .transition();
    return new CanonicalGameplayAdmissionDecision(
        transitionId,
        actualLease,
        transition.candidate(),
        transition.bindingGeneration(),
        null,
        binding.originalPreparedDecisionWithControlledAccountLease().targetEvidence());
  }

  private CanonicalGameplayOriginalNewCandidateRedisOwner.Installation
      installCandidateAndRepairAccountMember(CanonicalGameplayAdmissionDecision originalDecision) {
    var installation =
        new CanonicalGameplayOriginalNewCandidateRedisOwner(
                coordination,
                scripts,
                new CanonicalGameplayBindingInventoryRepository(binding.dsl()),
                tags)
            .installOnce(originalDecision);
    new CanonicalGameplayAccountMemberRepairOwner(binding.dsl(), coordination, scripts)
        .repair(originalDecision.bindingDecisionId());
    return installation;
  }

  public SecondAdmission prepareSecondAdmission() {
    var admission = new SecondAdmission();
    requestIds.add(admission.requestId());
    return admission;
  }

  public final class SecondAdmission {
    private final CanonicalGameplayIssuerBindingLocalOperation operation;
    private final Candidate candidate;
    private final CanonicalInitialPlayerLocationRequest worldRequest;
    private CanonicalGameplayAdmissionDecision secondDecision;
    private CanonicalGameplayRegionLeaseOwner.Installation secondRegionInstallation;
    private CanonicalGameplayOriginalNewCandidateRedisOwner.Installation
        secondCandidateInstallation;

    private SecondAdmission() {
      binding.prepareOtherControllerNew(
          SECOND_ADMISSION_SEED, UUID.randomUUID(), UUID.randomUUID());
      operation = binding.otherOperation(SECOND_ADMISSION_SEED);
      var other = operation.request().candidate();
      candidate =
          new Candidate(
              other.accountId(),
              other.tenantId(),
              other.playableStateNamespaceId(),
              other.characterId(),
              other.playableStateScope(),
              other.gameInstanceId(),
              other.runtimeGameInstanceId(),
              UUID.fromString(other.sessionId()),
              other.regionId(),
              other.regionEpoch(),
              other.issuerId(),
              other.issuerAuthGeneration(),
              other.issuerIndexLayoutVersion(),
              other.issuerIndexPartitionCount(),
              other.issuerIndexPartitionCapacity());
      worldRequest = sources.registerSecondPlacement(other.characterId());
      binding.projectOtherControllerNew(SECOND_ADMISSION_SEED);
    }

    public UUID requestId() {
      return operation.request().requestId();
    }

    public UUID decisionId() {
      return operation.request().transitionId();
    }

    public BigInteger generation() {
      return operation.preparedTransition().bindingGeneration();
    }

    public Candidate candidate() {
      return candidate;
    }

    public void install(AccountGameplayAdmissionLeaseEvidence actualLease) {
      secondDecision = originalDecision(decisionId(), actualLease);
      secondCandidateInstallation = installCandidateAndRepairAccountMember(secondDecision);
      new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(
              binding.dsl(), binding.currentIssuerSource())
          .acknowledge(binding.projectOtherControllerNew(SECOND_ADMISSION_SEED));
      secondRegionInstallation =
          region.installForTransition(regionInstallation, decisionId(), worldRequest);
      region.bridge(secondRegionInstallation);
    }

    public byte[] physicalSession() {
      return administration.sync().get(ascii(secondCandidateInstallation.sessionKey()));
    }
  }

  public Receipt complete(
      AccountGameplayAdmissionLeaseClient account,
      AccountPublicJwksCache jwks,
      String originalLoginJwt,
      long deadline,
      net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient capacityClient,
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request
          capacityRequest) {
    return complete(
        decision,
        regionInstallation,
        account,
        jwks,
        originalLoginJwt,
        deadline,
        capacityClient,
        capacityRequest);
  }

  public Receipt complete(
      SecondAdmission second,
      AccountGameplayAdmissionLeaseClient account,
      AccountPublicJwksCache jwks,
      String originalLoginJwt,
      long deadline,
      net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient capacityClient,
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request
          capacityRequest) {
    return complete(
        second.secondDecision,
        second.secondRegionInstallation,
        account,
        jwks,
        originalLoginJwt,
        deadline,
        capacityClient,
        capacityRequest);
  }

  private Receipt complete(
      CanonicalGameplayAdmissionDecision admission,
      CanonicalGameplayRegionLeaseOwner.Installation installation,
      AccountGameplayAdmissionLeaseClient account,
      AccountPublicJwksCache jwks,
      String originalLoginJwt,
      long deadline,
      net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient capacityClient,
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request
          capacityRequest) {
    // Recreate the coordinator and its SQL/INITIAL/issuer/final owners on every invocation.
    var issuerSource = binding.currentIssuerSource();
    var issuer =
        new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
            coordination,
            scripts,
            new CanonicalGameplayIssuerAdmissionBarrierRepository(
                binding.dsl(), tags, issuerSource),
            issuerSource);
    var coordinator =
        new CanonicalGameplayOriginalNewFinalAdmissionCoordinator(
            new CanonicalGameplayBindingInventoryRepository(binding.dsl()),
            account,
            initialOwner(),
            issuer,
            region,
            new CanonicalGameplayOriginalNewBoundCredentialVerifier(jwks, CLOCK),
            new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                coordination, scripts, CLOCK, tags),
            CLOCK,
            capacityClient,
            new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                binding.dsl(), coordination, scripts, CLOCK, tags));
    var result =
        coordinator.completeOriginalNew(
            admission, originalLoginJwt, installation, 120_000L, deadline, capacityRequest);
    return new Receipt(
        result.transitionId(),
        result.admissionAtMillis(),
        result.continuityExpiresAtMillis(),
        result.canonicalSessionDurationMillis(),
        result.finalSessionSha256());
  }

  public Candidate candidate() {
    return binding.candidate();
  }

  public Target target() {
    return binding.target();
  }

  public java.math.BigInteger generation() {
    return binding.generation();
  }

  public UUID decisionId() {
    return binding.transitionId();
  }

  public DSLContext dsl() {
    return binding.dsl();
  }

  public int finalReadCalls() {
    return finalReadCalls.get();
  }

  public byte[] physicalSession() {
    return administration.sync().get(ascii(candidateInstallation.sessionKey()));
  }

  /** Actual Account commit followed by a controlled selected-owner movement before GS binding. */
  public OwnerMovementFault commitThenMoveSelectedOwnerForFault(
      net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient client,
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request request,
      long deadline) {
    var source =
        new CanonicalGameplayBindingInventoryRepository(binding.dsl())
            .readOriginalNewFinalAdmissionSource(decision);
    var executionOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            binding.dsl(), coordination, scripts, CLOCK, tags);
    var selected = executionOwner.requireRequestBinding(source, request, deadline);
    executionOwner.precharge(source, request, selected, deadline);
    var result = client.commit(request, deadline);
    var identity = source.candidate().bindingIdentity();
    if (binding
            .dsl()
            .execute(
                "UPDATE game_session_region_runtime_status SET executor_fence = executor_fence + 1 WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
                identity.tenantId(),
                identity.gameInstanceId(),
                identity.regionId())
        != 1)
      throw new IllegalStateException("Controlled owner movement did not affect the selected row");
    return new OwnerMovementFault(
        result, () -> executionOwner.bind(source, request, result, deadline, selected));
  }

  public record OwnerMovementFault(
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result reservation,
      Runnable bindOriginal) {}

  /** Read-only images of the isolated fixture's actual admission SQL and Redis state. */
  public java.util.Map<String, String> capacityStateImage() {
    var result = new java.util.TreeMap<String, String>();
    for (String table :
        java.util.List.of(
            "game_session_canonical_binding_transition",
            "game_session_capacity_evidence_charge",
            "game_session_original_new_capacity_execution")) {
      String rows =
          binding
              .dsl()
              .fetch(
                  "SELECT (to_jsonb(t) || jsonb_build_object('_xmin', t.xmin::text))::text AS image FROM "
                      + table
                      + " t ORDER BY 1")
              .getValues("image", String.class)
              .toString();
      result.put(
          "sql:" + table,
          net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution.sha256(
              rows.getBytes(StandardCharsets.UTF_8)));
    }
    var commands = administration.sync();
    for (byte[] key : commands.keys("*")) {
      result.put(
          "redis:" + new String(key, StandardCharsets.UTF_8),
          net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution.sha256(
              CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(commands, key)));
    }
    return java.util.Map.copyOf(result);
  }

  /** Controlled TTL-only fault used to prove the composition image observes absolute expiry. */
  public String setPhysicalSessionExpiryForFault() {
    var commands = administration.sync();
    byte[] key = ascii(candidateInstallation.sessionKey());
    long expiresAt = Math.addExact(CLOCK.millis(), 60_000L);
    if (commands.pexpiretime(key) != -1L
        || !commands.pexpireat(key, expiresAt)
        || commands.pexpiretime(key) != expiresAt)
      throw new IllegalStateException(
          "Expected a persistent candidate with only its expiry changed");
    return "redis:" + candidateInstallation.sessionKey();
  }

  /** Controlled post-retention serving loss only, not proof of actual runtime teardown ordering. */
  public void removeServingIndexesAfterCapacityRetentionForFault() {
    String state =
        binding
            .dsl()
            .fetchSingle(
                "SELECT status FROM game_session_original_new_capacity_execution WHERE transition_id = ?",
                decisionId())
            .get(0, String.class);
    if (!"COMMITTED".equals(state))
      throw new IllegalStateException("Immutable actual outcome must precede this fixture fault");
    administration
        .sync()
        .del(
            new byte[][] {
              ascii(candidateInstallation.sessionKey()),
                  ascii(candidateInstallation.characterIndexKey()),
              ascii(candidateInstallation.accountTenantIndexKey()),
                  ascii(candidateInstallation.tenantIndexKey())
            });
  }

  public void removeInitialBarrierForFault() {
    administration.sync().del(new byte[][] {ascii(initialBarrierKey)});
  }

  public boolean initialBarrierAbsent() {
    return administration.sync().exists(ascii(initialBarrierKey)) == 0L;
  }

  public record Receipt(
      UUID transitionId,
      long admissionAtMillis,
      long continuityExpiresAtMillis,
      long canonicalSessionDurationMillis,
      String finalSessionSha256) {}

  private final class FinalReadCounter implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        io.grpc.Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
      finalReadCalls.incrementAndGet();
      return next.startCall(call, headers);
    }
  }

  /** Controlled stable deployment source; never used to reconstruct a private region grant. */
  private static CanonicalGameplayRegionIncarnationSource.StableEvidence
      controlledRegionIncarnation(CanonicalGameplayRegionIncarnationSource.RegionScope scope) {
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-final-region",
            "generation-1",
            List.of("node-a"),
            "redis-build-1",
            "registry-v1",
            "sha256:" + "a".repeat(64),
            "READY",
            "boot-1",
            "startup-1",
            "storage-1",
            "aof-1");
    return CanonicalGameplayRegionIncarnationSource.StableEvidence.fromCurrentSource(
        scope,
        "controlled-final-region-attestor",
        "b".repeat(64),
        deployment,
        "c".repeat(64),
        "d".repeat(64),
        true,
        "controlled-non-reset-profile-v1",
        "e".repeat(64),
        true,
        "f".repeat(64),
        true,
        "1".repeat(64));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  @Override
  public void close() throws Exception {
    try {
      if (server != null) {
        server.shutdownNow();
        if (!server.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("GS final server teardown timed out");
      }
    } finally {
      try {
        if (sources != null) sources.close();
      } finally {
        try {
          if (binding != null) binding.close();
        } finally {
          try {
            if (administration != null) administration.close();
          } finally {
            try {
              if (coordination != null) coordination.shutdown();
            } finally {
              try {
                if (administrator != null) administrator.shutdown();
              } finally {
                redis.stop();
              }
            }
          }
        }
      }
    }
  }
}
