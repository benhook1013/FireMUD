package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolVersion;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberAcknowledgement;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;

/**
 * Actual GS durable decision owner for the Account/GS abort composition test. Launch producers,
 * initial admission target, trusted issuer layout/checkpoint/incarnation, and empty-cohort/storage
 * collaborators remain controlled fixtures. The actual bootstrap and migration owners install the
 * physical anchor and publish VERIFIED; no collaborator supplies an Account terminal result or
 * replaces the GS reverse decision read.
 */
public final class CanonicalGameplayAdmissionAbortCompositionFixture implements AutoCloseable {
  private final DriverManagerDataSource dataSource;
  private final String schema =
      "gs_abort_composition_" + UUID.randomUUID().toString().replace("-", "");
  private final CanonicalGameplayBindingInventoryRepository repository;
  private final CanonicalPlayableTarget target;
  private final CanonicalGameplayBindingIdentity identity;
  private final UUID transitionId = UUID.randomUUID();
  private final BigInteger generation;
  private final DSLContext dsl;
  private GenericContainer<?> memberRedis;
  private RedisClient memberRedisApplication;
  private CanonicalGameplayAccountMemberAcknowledgement memberAcknowledgement;
  private final AtomicReference<
          CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState>
      issuerState;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      issuerSource;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      initialIssuerState;
  private final CanonicalGameplayIssuerBindingLocalOperation localOperation;
  private final CanonicalGameplayIssuerBindingLocalProjectionRepository localProjectionRepository;
  private byte[] issuerAcknowledgementBytes;
  private final RedisClient issuerRedisClient;
  private final RedisScriptCatalog issuerCatalog;
  private final Map<String, CanonicalGameplayIssuerBindingLocalOperation> otherOperations =
      new HashMap<>();

  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource,
      Candidate accountCandidate,
      RedisClient issuerRedisClient) {
    this.issuerRedisClient =
        java.util.Objects.requireNonNull(issuerRedisClient, "issuerRedisClient");
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(
        Objects.requireNonNull(accountDataSource.getUrl(), "Account fixture JDBC URL")
            .replaceAll("currentSchema=[^&]+", "currentSchema=" + schema));
    dataSource.setUsername(accountDataSource.getUsername());
    dataSource.setPassword(accountDataSource.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations(
            "filesystem:"
                + Path.of("../game-session-service/src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    var runtime =
        CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
            dataSource, accountCandidate.tenantId(), "test");
    target = sealControlledInitialTarget(runtime.playableTarget());
    identity =
        new CanonicalGameplayBindingIdentity(
            accountCandidate.accountId(),
            accountCandidate.tenantId(),
            target.playableStateNamespaceId(),
            accountCandidate.characterId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            target.gameInstanceId(),
            accountCandidate.sessionId().toString(),
            accountCandidate.regionId(),
            accountCandidate.regionEpoch(),
            accountCandidate.issuerId(),
            accountCandidate.issuerAuthGeneration(),
            accountCandidate.issuerIndexLayoutVersion(),
            accountCandidate.issuerIndexPartitionCount(),
            accountCandidate.issuerIndexPartitionCapacity());
    establishActualLegacyDisposition(dsl);
    repository = new CanonicalGameplayBindingInventoryRepository(dsl);
    BigInteger layoutVersion = identity.issuerIndexLayoutVersion();
    BigInteger partitionCount = identity.issuerIndexPartitionCount();
    BigInteger partitionCapacity = identity.issuerIndexPartitionCapacity();
    String mapping = CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    String exactDescriptor =
        "issuer-layout-descriptor/v1|"
            + identity.issuerId()
            + "|"
            + layoutVersion
            + "|"
            + partitionCount
            + "|"
            + partitionCapacity
            + "|"
            + mapping;
    var trustedLayout =
        new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor(
            identity.issuerId(),
            layoutVersion,
            partitionCount,
            partitionCapacity,
            mapping,
            exactDescriptor.getBytes(StandardCharsets.UTF_8));
    var sourceCheckpoint =
        trustedSourceCheckpoint(identity.issuerId(), identity.issuerAuthGeneration());
    UUID bootstrapRequestId = UUID.randomUUID();
    var bootstrapIntent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(bootstrapRequestId, trustedLayout, sourceCheckpoint);
    initialIssuerState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            bootstrapIntent.sourceCheckpoint(), stableIncarnation());
    issuerState = new AtomicReference<>(initialIssuerState);
    issuerSource = issuerState::get;
    issuerCatalog =
        RedisScriptCatalog.fromContributions(
            List.of(
                new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution(),
                new CanonicalGameplayIssuerBindingLocalRedisScriptContribution(),
                new CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution()));
    var partitionOwner =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
            issuerRedisClient, issuerCatalog, issuerSource);
    new CanonicalGameplayIssuerBootstrapCoverageOwner(dsl, partitionOwner, issuerSource)
        .completeInitialBootstrap(bootstrapIntent.operationId(), bootstrapRequestId);
    var request =
        new CanonicalGameplayIssuerBindingLocalOperation.Request(
            UUID.randomUUID(), transitionId, identity, null, null);
    localOperation =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, issuerSource)
            .beginOrResumeOrdinaryNew(request);
    generation = localOperation.preparedTransition().bindingGeneration();
    localProjectionRepository =
        new CanonicalGameplayIssuerBindingLocalProjectionRepository(dsl, issuerSource);
    new CanonicalGameplayIssuerBindingLocalRedisOwner(
            issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
        .projectAndRead(localOperation);
  }

  /**
   * Tuple-consistent controlled upstream target; it does not claim a live initial-admission
   * producer.
   */
  private static CanonicalPlayableTarget sealControlledInitialTarget(
      CanonicalPlayableTarget original) {
    var request =
        new WorldCanonicalInitialAdmissionHold.Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.worldSlug(),
            original.realmId(),
            original.playableStateNamespaceId(),
            original.playableStateScope(),
            original.canonicalGameInstanceId(),
            original.canonicalVersionId(),
            original.activeWorldEpoch(),
            original.initialAdmissionRequestId(),
            original.initialAdmissionRequestDigest(),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            original.catalogRevision(),
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request, original.holdId(), original.holdFence());
    return new CanonicalPlayableTarget(
        original.targetNamespace(),
        original.tenantSlug(),
        original.canonicalTenantId(),
        original.worldSlug(),
        original.worldDisplayName(),
        original.realmId(),
        original.realmSlug(),
        original.realmDisplayName(),
        original.gameSessionTenantId(),
        original.gameInstanceId(),
        original.playableStateNamespaceId(),
        original.playableStateScope(),
        original.canonicalGameInstanceId(),
        original.canonicalVersionId(),
        original.runtimeVersionId(),
        original.catalogRevision(),
        original.pointerVersion(),
        original.admissionPointerSnapshotDigest(),
        original.activeWorldEpoch(),
        original.initialAdmissionRequestId(),
        original.initialAdmissionRequestDigest(),
        original.initialAdmissionOriginKind(),
        original.expectedPriorPointerVersion(),
        original.holdId(),
        original.holdFence(),
        hold.holdBindingDigest(),
        original.auditEventId(),
        original.ownerProofDigest(),
        original.ownerProofOutcome(),
        original.positiveDurableAbort(),
        original.ownerProofTerminalAt(),
        original.characterCreationPolicy());
  }

  /** Actual migration state machine over explicitly controlled empty-cohort collaborators. */
  private static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "controlled-cluster-uid",
            "controlled-namespace-uid",
            "controlled-producer-pod-uid",
            "controlled-container-id",
            "controlled-node-uid",
            "123456789",
            42L,
            "controlled-postgres-volume-uid",
            "controlled-redis-run-id",
            "controlled-redis-volume-uid");
    var fencedCohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return storageIdentity;
          }

          @Override
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storageIdentity.equals(expected)) {
              throw new IllegalStateException("Controlled test storage identity changed");
            }
          }
        };
    var operation =
        new CanonicalGameplayLegacyMigrationOwner(
                migration,
                inventory,
                () -> fencedCohort,
                ignored ->
                    new CanonicalGameplayLegacyMigrationSourceSnapshot(
                        EnumSet.of(
                            Family.TENANT_SESSION_CONTEXT,
                            Family.SESSION_ALIAS_CONTEXT,
                            Family.GAMEPLAY_IDENTITY_CONTEXT,
                            Family.GAMEPLAY_NAME_CONTEXT,
                            Family.MOVEMENT_EFFECT,
                            Family.DURABLE_EFFECT),
                        List.of()),
                new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
                  @Override
                  public void rebuildExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                      CanonicalGameplayLegacyMigrationSourceSnapshot source,
                      CanonicalGameplayBindingInventorySnapshot canonical) {
                    requireEmptyCanonical(canonical);
                  }

                  @Override
                  public CanonicalGameplayLegacyMigrationReadback readBackExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                      CanonicalGameplayBindingInventorySnapshot canonical) {
                    requireEmptyCanonical(canonical);
                    return new CanonicalGameplayLegacyMigrationReadback(
                        List.of(), List.of(), List.of(), List.of());
                  }
                })
            .migrate();
    if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
      throw new IllegalStateException("Actual legacy migration owner did not publish VERIFIED");
    }
  }

  private static void requireEmptyCanonical(CanonicalGameplayBindingInventorySnapshot canonical) {
    if (!canonical.bindings().isEmpty()
        || !canonical.reservations().isEmpty()
        || !canonical.transitions().isEmpty()
        || !canonical.accountIndexObligations().isEmpty()
        || !canonical.issuerIndexObligations().isEmpty()
        || !canonical.regionBridgeObligations().isEmpty()) {
      throw new IllegalStateException("Controlled empty-cohort collaborator found canonical state");
    }
  }

  public Candidate candidate() {
    return new Candidate(
        identity.accountId(),
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        identity.playableStateScope(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        UUID.fromString(identity.sessionId()),
        identity.regionId(),
        identity.regionEpoch(),
        identity.issuerId(),
        identity.issuerAuthGeneration(),
        identity.issuerIndexLayoutVersion(),
        identity.issuerIndexPartitionCount(),
        identity.issuerIndexPartitionCapacity());
  }

  public Target target() {
    return new Target(
        target.targetNamespace(),
        target.tenantSlug(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.worldDisplayName(),
        target.realmId(),
        target.realmSlug(),
        target.realmDisplayName(),
        target.gameSessionTenantId(),
        target.gameInstanceId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.admissionPointerSnapshotDigest(),
        target.activeWorldEpoch(),
        target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind.NO_PRIOR_POINTER,
        null,
        target.holdId(),
        target.holdFence(),
        target.holdBindingDigest(),
        target.auditEventId(),
        target.ownerProofDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED,
        false,
        target.ownerProofTerminalAt(),
        target.characterCreationPolicy());
  }

  public void publish(AccountGameplayAdmissionLeaseEvidence lease) {
    repository.markProvisional(
        repository.readProvisionalCasEvidence(transitionId),
        new CanonicalGameplayAdmissionDecision(
            transitionId, lease, identity, generation, null, target));
    var physicalReadback =
        new CanonicalGameplayIssuerBindingLocalRedisOwner(
                issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
            .projectAndRead(localOperation);
    issuerAcknowledgementBytes =
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, issuerSource)
            .acknowledge(physicalReadback)
            .exactBytes();
  }

  /**
   * Start the disposable GS role before lease allocation so container startup consumes no lease
   * lifetime.
   */
  public void prepareMemberRepairRedis() {
    memberRedis = new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);
    memberRedis.start();
    String password = UUID.randomUUID().toString();
    var bootstrap =
        RedisClient.create(
            RedisURI.Builder.redis(memberRedis.getHost(), memberRedis.getMappedPort(6379)).build());
    try {
      try (var connection = bootstrap.connect(ByteArrayCodec.INSTANCE)) {
        connection
            .sync()
            .aclSetuser(
                "gamesession_coord_app",
                new AclSetuserArgs()
                    .reset()
                    .on()
                    .addPassword(password)
                    .keyPattern("session:game:index:account:*")
                    .addCommand(CommandType.HELLO)
                    .addCommand(CommandType.PING)
                    .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
                    .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
                    .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
                    .addCommand(CommandType.EVALSHA)
                    .addCommand(CommandType.SADD)
                    .addCommand(CommandType.TYPE)
                    .addCommand(CommandType.PTTL)
                    .addCommand(CommandType.SISMEMBER));
      }
    } finally {
      bootstrap.shutdown();
    }
    var uri =
        RedisURI.Builder.redis(memberRedis.getHost(), memberRedis.getMappedPort(6379))
            .withAuthentication("gamesession_coord_app", password)
            .build();
    uri.setTimeout(Duration.ofSeconds(5));
    memberRedisApplication = RedisClient.create(uri);
    memberRedisApplication.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(ProtocolVersion.RESP2)
            .build());
  }

  /**
   * Actual SQL allocation -> role-authenticated physical Redis projection/read -> durable SQL ACK.
   */
  public CanonicalGameplayAccountMemberAcknowledgement repairAccountMember() {
    memberAcknowledgement =
        new CanonicalGameplayAccountMemberRepairOwner(
                dsl,
                Objects.requireNonNull(
                    memberRedisApplication, "GS member role must be prepared before acquire"),
                RedisScriptCatalog.fromContributions(
                    List.of(new CanonicalGameplayAccountMemberRepairRedisScriptContribution())))
            .repair(transitionId);
    return memberAcknowledgement;
  }

  public String memberAcknowledgementImage() {
    return dsl.fetchSingle(
            "SELECT to_jsonb(repair)::text FROM game_session_canonical_account_member_repair_operation repair WHERE transition_id = ?",
            transitionId)
        .get(0, String.class);
  }

  public String accountObligationPhaseImage() {
    return dsl.fetchSingle(
            "SELECT (to_jsonb(obligation) - 'inventory_revision')::text FROM game_session_canonical_binding_account_index_obligation obligation WHERE transition_id = ? AND obligation_ordinal = 0",
            transitionId)
        .get(0, String.class);
  }

  /** Independent read under the same real GS role; retained ACK never implies physical removal. */
  public boolean hasExactAccountMember() {
    var acknowledgement =
        Objects.requireNonNull(memberAcknowledgement, "Actual completed member repair required");
    try (var connection = Objects.requireNonNull(memberRedisApplication).connect()) {
      String key = "session:game:index:account:" + identity.accountId();
      return "gamesession_coord_app".equals(connection.sync().aclWhoami())
          && "set".equals(connection.sync().type(key))
          && connection.sync().pttl(key) == -1L
          && connection.sync().sismember(key, acknowledgement.member());
    }
  }

  public CanonicalGameplayIssuerBindingLocalReleaseOperation prepareIssuerRelease() {
    return new CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(dsl, issuerSource)
        .prepareOrResume(transitionId);
  }

  public CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.Readback projectIssuerRelease(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    return new CanonicalGameplayIssuerBindingLocalReleaseRedisOwner(
            issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
        .releaseAndRead(operation);
  }

  /** Models a lost caller response after the real owner has written and independently read back. */
  public void projectIssuerReleaseAndLoseReply(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    var readback = projectIssuerRelease(operation);
    if (!readbackHasExactReleasedMember(operation, readback)) {
      throw new IllegalStateException(
          "Actual issuer release did not produce its exact terminal readback");
    }
    throw new IllegalStateException("controlled lost issuer release reply after physical readback");
  }

  public CanonicalGameplayIssuerBindingLocalReleaseOperation completeIssuerRelease(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation,
      CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.Readback readback) {
    var completed =
        new CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(dsl, issuerSource)
            .completeWithReadback(operation, readback);
    var historicalAcknowledgement =
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, issuerSource)
            .readHistorical(localOperation.operationId());
    if (issuerAcknowledgementBytes == null
        || historicalAcknowledgement == null
        || !java.util.Arrays.equals(
            issuerAcknowledgementBytes, historicalAcknowledgement.exactBytes())) {
      throw new IllegalStateException(
          "Issuer release did not retain the exact original binding-local acknowledgement");
    }
    return completed;
  }

  public UUID prepareOtherControllerNew(String seed) {
    CanonicalGameplayBindingIdentity other;
    do {
      other =
          new CanonicalGameplayBindingIdentity(
              identity.accountId(),
              identity.tenantId(),
              identity.playableStateNamespaceId(),
              UUID.randomUUID(),
              identity.playableStateScope(),
              identity.gameInstanceId(),
              identity.runtimeGameInstanceId(),
              UUID.randomUUID().toString(),
              identity.regionId(),
              identity.regionEpoch(),
              identity.issuerId(),
              identity.issuerAuthGeneration(),
              identity.issuerIndexLayoutVersion(),
              identity.issuerIndexPartitionCount(),
              identity.issuerIndexPartitionCapacity());
    } while (!other
        .bindingRef()
        .partitionId(identity.issuerIndexPartitionCount())
        .equals(localOperation.reservation().partitionId()));
    var operation =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, issuerSource)
            .beginOrResumeOrdinaryNew(
                new CanonicalGameplayIssuerBindingLocalOperation.Request(
                    UUID.nameUUIDFromBytes((seed + "-request").getBytes(StandardCharsets.UTF_8)),
                    UUID.nameUUIDFromBytes((seed + "-transition").getBytes(StandardCharsets.UTF_8)),
                    other,
                    null,
                    null));
    otherOperations.put(seed, operation);
    return operation.operationId();
  }

  public BigInteger preparedLocalCountedReservations() {
    var work = localProjectionRepository.readPreparedPartitionWork(localOperation);
    return BigInteger.valueOf(
        work.localEntries().stream()
            .filter(
                entry ->
                    entry
                        .memberValue()
                        .reservation()
                        .partitionId()
                        .equals(localOperation.reservation().partitionId()))
            .filter(
                CanonicalGameplayIssuerBindingLocalProjectionRepository.LocalEntry
                    ::countsTowardCapacity)
            .count());
  }

  public void moveIssuerIncarnation() {
    var current = issuerState.get();
    issuerState.set(
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            current.accountCheckpoint(), stableIncarnation("moved-release-incarnation")));
  }

  public void moveIssuerSourceCheckpoint() {
    var current = issuerState.get();
    var trusted =
        trustedSourceCheckpoint(
            identity.issuerId(), identity.issuerAuthGeneration().add(BigInteger.ONE));
    byte[] checkpointBytes = trusted.checkpointBytes();
    byte[] eventBytes = Objects.requireNonNull(trusted.latestEventBytes());
    var checkpoint =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
            trusted.issuerId(),
            trusted.issuerAuthGeneration(),
            checkpointBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(checkpointBytes),
            eventBytes,
            CanonicalGameplayIssuerMemberV2Codec.sha256(eventBytes));
    issuerState.set(
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            checkpoint, current.incarnation()));
  }

  public void restoreIssuerState() {
    issuerState.set(initialIssuerState);
  }

  public boolean readbackHasExactReleasedMember(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation,
      CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.Readback readback) {
    Map<String, byte[]> fields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            readback.exactPhysicalFieldSetBytes());
    String capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            operation.releasedMemberValue().reservation().reservationId());
    String bindingField =
        new String(
            operation.releasedMemberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
    return readback.operationId().equals(operation.operationId())
        && Arrays.equals(fields.get(capacityField), operation.exactReleasedMemberValueBytes())
        && !fields.containsKey(bindingField);
  }

  public byte[] readReleaseCapacityValue(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
            .getBytes(StandardCharsets.US_ASCII);
    String field =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            operation.releasedMemberValue().reservation().reservationId());
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      return connection.sync().hget(key, field.getBytes(StandardCharsets.US_ASCII));
    }
  }

  public byte[] readReleaseBindingValue(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] field = operation.releasedMemberValue().owner().bindingRef().bytes();
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      return connection.sync().hget(key, field);
    }
  }

  public byte[] otherMemberValueBytes(String seed) {
    var operation = otherOperations.get(seed);
    if (operation == null)
      throw new IllegalArgumentException("Other NEW operation was not prepared");
    return operation.exactMemberValueBytes();
  }

  /**
   * Places another real SQL operation's exact value under this reservation's field as a conflict.
   */
  public void installConflictingReleaseCapacity(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation, byte[] conflictingBytes) {
    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] field =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                operation.releasedMemberValue().reservation().reservationId())
            .getBytes(StandardCharsets.US_ASCII);
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      connection.sync().hset(key, field, conflictingBytes);
    }
  }

  public void restoreOriginalReleaseCapacity(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] field =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                operation.releasedMemberValue().reservation().reservationId())
            .getBytes(StandardCharsets.US_ASCII);
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      connection.sync().hset(key, field, operation.exactOriginalMemberValueBytes());
    }
  }

  /** Injects a conflicting newer member postimage for the negative physical-owner proof. */
  public void installConflictingReleaseBinding(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] field = operation.releasedMemberValue().owner().bindingRef().bytes();
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      connection.sync().hset(key, field, operation.exactReleasedMemberValueBytes());
    }
  }

  /** Restores the saved exact preimage after the conflict-preservation assertion. */
  public void restoreOriginalReleaseBinding(
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] field = operation.originalOperation().memberValue().owner().bindingRef().bytes();
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      connection.sync().hset(key, field, operation.exactOriginalMemberValueBytes());
    }
  }

  public CanonicalGameplayIssuerBindingLocalRedisOwner.Readback projectOtherControllerNew(
      String seed) {
    var operation = otherOperations.get(seed);
    if (operation == null)
      throw new IllegalArgumentException("Other NEW operation was not prepared");
    return new CanonicalGameplayIssuerBindingLocalRedisOwner(
            issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
        .projectAndRead(operation);
  }

  /** Attempts a newer candidate; the retained unresolved original must fence preparation. */
  public BigInteger prepareNewerCandidate() {
    var newer =
        new CanonicalGameplayBindingIdentity(
            identity.accountId(),
            identity.tenantId(),
            identity.playableStateNamespaceId(),
            identity.characterId(),
            identity.playableStateScope(),
            identity.gameInstanceId(),
            identity.runtimeGameInstanceId(),
            UUID.randomUUID().toString(),
            identity.regionId(),
            identity.regionEpoch(),
            identity.issuerId(),
            identity.issuerAuthGeneration(),
            identity.issuerIndexLayoutVersion(),
            identity.issuerIndexPartitionCount(),
            identity.issuerIndexPartitionCapacity());
    return repository
        .prepare(
            new CanonicalGameplayBindingTransitionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                newer,
                new CanonicalIssuerReservationFenceEvidence(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BigInteger.ONE,
                    BigInteger.ONE,
                    BigInteger.ONE),
                null,
                null))
        .bindingGeneration();
  }

  /** Uses the actual runtime repository; a retained pending lease must fence this mutation. */
  public void stopRuntime() {
    var runtimeRepository = new GameInstanceRepository(dsl);
    var runtime = runtimeRepository.findById(target.gameInstanceId()).orElseThrow();
    runtime.setStatus("STOPPED");
    runtimeRepository.save(runtime);
  }

  public UUID transitionId() {
    return transitionId;
  }

  public BigInteger generation() {
    return generation;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Test fixture deliberately shares its actual SQL context with unregistered owner factories")
  public DSLContext dsl() {
    return dsl;
  }

  public CanonicalGameplayAdmissionAbortSource source() {
    return repository.readFirstNewAdmissionAbortSource(transitionId);
  }

  /** Comparison accessor for the actual private schema-v2 value; no production API is widened. */
  public net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation
      releasedReservation(CanonicalGameplayIssuerBindingLocalReleaseOperation operation) {
    return operation.releasedMemberValue().reservation();
  }

  public UUID originalOperationId() {
    return localOperation.operationId();
  }

  public byte[] originalMemberValueBytes() {
    return localOperation.exactMemberValueBytes();
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      trustedSourceCheckpoint(String issuerId, BigInteger generation) {
    if (BigInteger.ONE.equals(generation)) {
      // Controlled initial checkpoint: initialized authority has no mutation event at sequence
      // zero.
      // Keep the actual Account generation; never invent a source event or advance authority.
      byte[] checkpoint =
          ("controlled-initial-issuer-checkpoint/v1|" + issuerId + "|1")
              .getBytes(StandardCharsets.UTF_8);
      return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
          issuerId, generation, checkpoint, null);
    }
    long sequence = generation.longValueExact() - 1;
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuerId,
                Long.toString(sequence),
                issuerId,
                generation.toString(),
                generation.toString(),
                "ISSUER_SECURITY_CHANGE"));
    byte[] bytes = event.canonicalJsonUtf8();
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
        issuerId, generation, bytes, bytes);
  }

  private static CanonicalGameplayIssuerMemberV2Value.Incarnation stableIncarnation() {
    return stableIncarnation("controlled-release-composition");
  }

  private static CanonicalGameplayIssuerMemberV2Value.Incarnation stableIncarnation(String seed) {
    String digest =
        CanonicalGameplayIssuerMemberV2Codec.sha256(seed.getBytes(StandardCharsets.UTF_8));
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-release-deployment-" + seed,
            "controlled-release-generation-" + seed,
            List.of("controlled-release-node-" + seed),
            "controlled-release-redis-build-" + seed,
            "controlled-release-registry-" + seed,
            digest,
            "READY",
            "controlled-release-boot-" + seed,
            "controlled-release-startup-" + seed,
            "controlled-release-storage-" + seed,
            "controlled-release-aof-" + seed);
    return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
        "controlled-release-attestor-" + seed, digest, deployment, digest, digest);
  }

  @Override
  public void close() {
    try {
      if (memberRedisApplication != null) memberRedisApplication.shutdown();
    } finally {
      try {
        if (memberRedis != null) memberRedis.stop();
      } finally {
        dsl.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
      }
    }
  }
}
