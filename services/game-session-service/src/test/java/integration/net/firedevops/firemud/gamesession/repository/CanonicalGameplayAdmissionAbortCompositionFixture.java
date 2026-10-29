package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolVersion;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
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
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
  private static final String SERVICE_MIGRATIONS =
      "filesystem:"
          + Path.of("../game-session-service/src/main/resources/db/migration")
              .toAbsolutePath()
              .normalize();
  private static final String SHARED_SAGA_MIGRATIONS =
      "filesystem:"
          + Path.of("../common-saga/src/main/resources/db/migration/saga")
              .toAbsolutePath()
              .normalize();
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
  private final Map<String, AccountGameplayAdmissionLeaseEvidence> otherLeases = new HashMap<>();

  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource,
      Candidate accountCandidate,
      RedisClient issuerRedisClient) {
    this(accountDataSource, accountCandidate, issuerRedisClient, 0);
  }

  /** Installs actual retained reservations as part of the qualified initial bootstrap cohort. */
  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource,
      Candidate accountCandidate,
      RedisClient issuerRedisClient,
      int seedBootstrapMemberCount) {
    this(accountDataSource, accountCandidate, issuerRedisClient, seedBootstrapMemberCount, null);
  }

  /** Starts from a retained migration frontier when a composition test must upgrade live rows. */
  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource,
      Candidate accountCandidate,
      RedisClient issuerRedisClient,
      int seedBootstrapMemberCount,
      MigrationVersion migrationTarget) {
    this(
        accountDataSource,
        accountCandidate,
        issuerRedisClient,
        seedBootstrapMemberCount,
        migrationTarget,
        (data, intent) -> {});
  }

  /**
   * Lets a populated-parent upgrade run after actual bootstrap and before current-only NEW code.
   */
  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource,
      Candidate accountCandidate,
      RedisClient issuerRedisClient,
      int seedBootstrapMemberCount,
      MigrationVersion migrationTarget,
      BiConsumer<DriverManagerDataSource, CanonicalGameplayIssuerLayoutBootstrapIntent>
          afterBootstrap) {
    if (seedBootstrapMemberCount < 0 || seedBootstrapMemberCount > 256) {
      throw new IllegalArgumentException("Bootstrap fixture member count must be within [0, 256]");
    }
    this.issuerRedisClient =
        java.util.Objects.requireNonNull(issuerRedisClient, "issuerRedisClient");
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(
        Objects.requireNonNull(accountDataSource.getUrl(), "Account fixture JDBC URL")
            .replaceAll("currentSchema=[^&]+", "currentSchema=" + schema));
    dataSource.setUsername(accountDataSource.getUsername());
    dataSource.setPassword(accountDataSource.getPassword());
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(java.util.Map.of("serviceSchema", schema))
            .locations(SERVICE_MIGRATIONS, SHARED_SAGA_MIGRATIONS);
    if (migrationTarget != null) migration.target(migrationTarget);
    migration.load().migrate();
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
    if (seedBootstrapMemberCount > 0) {
      seedQualifiedBootstrapMembers(seedBootstrapMemberCount, migrationTarget);
    }
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
    CanonicalGameplayIssuerInstalledAnchor installedAnchor =
        new CanonicalGameplayIssuerBootstrapCoverageOwner(dsl, partitionOwner, issuerSource)
            .completeInitialBootstrap(bootstrapIntent.operationId(), bootstrapRequestId);
    Objects.requireNonNull(afterBootstrap, "afterBootstrap").accept(dataSource, bootstrapIntent);
    if (migrationTarget != null) {
      upgradeWithDefaultGameSessionMigrations(MigrationVersion.LATEST, dataSource);
    }
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(installedAnchor, issuerSource);
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

  public CanonicalGameplayIssuerInstalledAnchor retryOriginalIssuerBootstrap() {
    CanonicalGameplayIssuerInstalledAnchor originalAnchor = localOperation.anchor();
    var partitionOwner =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
            issuerRedisClient, issuerCatalog, issuerSource);
    return new CanonicalGameplayIssuerBootstrapCoverageOwner(dsl, partitionOwner, issuerSource)
        .completeInitialBootstrap(
            originalAnchor.bootstrapOperationId(), originalAnchor.bootstrapRequestId());
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

  /** Retains the existing controlled upstream lease before any provisional publication. */
  CanonicalGameplayAdmissionDecision originalPreparedDecisionWithControlledAccountLease() {
    return new CanonicalGameplayAdmissionDecision(
        transitionId,
        controlledLeaseEvidence(identity, generation, target),
        identity,
        generation,
        null,
        target);
  }

  /** Current controlled issuer source used by the actual bootstrap and local ACK owners. */
  CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentIssuerSource() {
    return issuerSource;
  }

  public void publish(AccountGameplayAdmissionLeaseEvidence lease) {
    repository.markProvisional(
        repository.readProvisionalCasEvidence(transitionId),
        new CanonicalGameplayAdmissionDecision(
            transitionId, lease, identity, generation, null, target));
    acknowledgeOriginalIssuer();
  }

  /** Actual issuer projection/readback and native SQL ACK after candidate-owned publication. */
  CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledgeOriginalIssuer() {
    var physicalReadback =
        new CanonicalGameplayIssuerBindingLocalRedisOwner(
                issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
            .projectAndRead(localOperation);
    var acknowledgement =
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, issuerSource)
            .acknowledge(physicalReadback);
    issuerAcknowledgementBytes = acknowledgement.exactBytes();
    return acknowledgement;
  }

  /** Uses a controlled Account lease input and the actual provisional SQL/Redis owners. */
  public void publishOriginalWithControlledAccountLease() {
    publish(controlledLeaseEvidence(identity, generation, target));
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

  /** Preserves the retained terminal source and every proof row across a Flyway upgrade. */
  public String retainedFirstNewAbortRowsImage() {
    return dsl.fetchSingle(
            "WITH target AS (SELECT * FROM game_session_canonical_binding_transition"
                + " WHERE transition_id = ?)"
                + " SELECT jsonb_build_object("
                + "'transition', (SELECT to_jsonb(target) FROM target),"
                + " 'candidate', (SELECT to_jsonb(candidate)"
                + " FROM game_session_canonical_gameplay_binding_inventory candidate"
                + " JOIN target ON candidate.binding_ref = target.candidate_binding_ref),"
                + " 'reservation', (SELECT to_jsonb(reservation)"
                + " FROM game_session_canonical_issuer_partition_reservation reservation"
                + " JOIN target ON reservation.reservation_id = target.issuer_reservation_id),"
                + " 'account_obligations', (SELECT coalesce(jsonb_agg(to_jsonb(obligation)"
                + " ORDER BY obligation.obligation_ordinal), '[]'::jsonb)"
                + " FROM game_session_canonical_binding_account_index_obligation obligation"
                + " JOIN target ON obligation.transition_id = target.transition_id),"
                + " 'issuer_obligations', (SELECT coalesce(jsonb_agg(to_jsonb(obligation)"
                + " ORDER BY obligation.binding_ref), '[]'::jsonb)"
                + " FROM game_session_canonical_binding_issuer_index_obligation obligation"
                + " JOIN target ON obligation.transition_id = target.transition_id),"
                + " 'region_obligations', (SELECT coalesce(jsonb_agg(to_jsonb(obligation)"
                + " ORDER BY obligation.region_id), '[]'::jsonb)"
                + " FROM game_session_canonical_binding_region_bridge_obligation obligation"
                + " JOIN target ON obligation.transition_id = target.transition_id),"
                + " 'account_member_repairs', (SELECT coalesce(jsonb_agg(to_jsonb(repair)"
                + " ORDER BY repair.operation_id), '[]'::jsonb)"
                + " FROM game_session_canonical_account_member_repair_operation repair"
                + " JOIN target ON repair.transition_id = target.transition_id))::text"
                + " FROM target",
            transitionId)
        .get(0, String.class);
  }

  /** Captures exact existing Flyway rows so a forward upgrade can prove append-only history. */
  public List<String> migrationHistoryImage() {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text AS row_json FROM flyway_schema_history"
                + " history_row ORDER BY installed_rank")
        .getValues("row_json", String.class);
  }

  /** Applies pending Game Session migrations with Flyway's default version ordering. */
  public int upgradeWithDefaultGameSessionMigrations(MigrationVersion migrationTarget) {
    return upgradeWithDefaultGameSessionMigrations(migrationTarget, dataSource);
  }

  /** Initializes rendering on every owned Flyway connection, not one unrelated SQL session. */
  public int upgradeWithDefaultGameSessionMigrations(
      MigrationVersion migrationTarget, String timeZone, String byteaOutput) {
    return upgradeWithDefaultGameSessionMigrations(
        migrationTarget, dataSourceWithRendering(timeZone, byteaOutput));
  }

  static int upgradeWithDefaultGameSessionMigrations(
      MigrationVersion migrationTarget, DriverManagerDataSource migrationDataSource) {
    String schema =
        DSL.using(migrationDataSource, SQLDialect.POSTGRES)
            .fetchSingle("SELECT current_schema()")
            .get(0, String.class);
    Flyway flyway =
        Flyway.configure()
            .target(Objects.requireNonNull(migrationTarget, "migrationTarget"))
            .dataSource(migrationDataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(java.util.Map.of("serviceSchema", schema))
            .locations(SERVICE_MIGRATIONS, SHARED_SAGA_MIGRATIONS)
            .load();
    var result = flyway.migrate();
    flyway.validate();
    return result.migrationsExecuted;
  }

  private DriverManagerDataSource dataSourceWithRendering(String timeZone, String byteaOutput) {
    return CanonicalGameplayIssuerTestFixtures.withSessionRendering(
        dataSource, timeZone, byteaOutput);
  }

  /**
   * Runs the real abort owner for one original bootstrap member using changed session rendering.
   */
  public CanonicalGameplayAdmissionAbortSource recordBootstrapAccountAbort(
      UUID reservationId, String timeZone, String byteaOutput) {
    DSLContext configured =
        DSL.using(dataSourceWithRendering(timeZone, byteaOutput), SQLDialect.POSTGRES);
    UUID bootstrapTransition =
        configured
            .fetchSingle(
                "SELECT transition_id FROM game_session_canonical_issuer_partition_reservation"
                    + " WHERE reservation_id = ?",
                reservationId)
            .get(0, UUID.class);
    var owner = new CanonicalGameplayBindingInventoryRepository(configured);
    var source = owner.readFirstNewAdmissionAbortSource(bootstrapTransition);
    return owner.recordFirstNewAccountAbort(
        source,
        UUID.nameUUIDFromBytes(
            ("bootstrap-controller-cleanup:" + reservationId).getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Exercises the deferred initial-abort SQL guard after the actual GS abort owner has written its
   * source, then advances Account control before the enclosing transaction commits.
   */
  public void recordOriginalAccountAbortThenAdvanceControlBeforeCommit() {
    var source = repository.readFirstNewAdmissionAbortSource(transitionId);
    dsl.transaction(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          var transactionRepository = new CanonicalGameplayBindingInventoryRepository(transaction);
          transactionRepository.recordFirstNewAccountAbort(
              source,
              UUID.nameUUIDFromBytes(
                  "original-controller-stale-control-cleanup".getBytes(StandardCharsets.UTF_8)));
          new CanonicalGameplayAccountAdmissionBarrierRepository(transaction)
              .beginFenceIntent(
                  identity.accountId(),
                  UUID.nameUUIDFromBytes(
                      "original-controller-stale-control-intent".getBytes(StandardCharsets.UTF_8)));
        });
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

  public CanonicalGameplayIssuerBindingLocalReleaseOperation prepareIssuerRelease(String seed) {
    return new CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(dsl, issuerSource)
        .prepareOrResume(otherOperation(seed).request().transitionId());
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
    if (operation.originalOperation().operationId().equals(localOperation.operationId())) {
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
    }
    return completed;
  }

  /** Records a controlled Account terminal input through the actual Game Session abort owner. */
  public CanonicalGameplayAdmissionAbortSource recordOriginalAccountAbort() {
    var source = repository.readFirstNewAdmissionAbortSource(transitionId);
    return repository.recordFirstNewAccountAbort(
        source,
        UUID.nameUUIDFromBytes("original-controller-cleanup".getBytes(StandardCharsets.UTF_8)));
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

  /** Uses the actual NEW SQL owner to prepare a member in a caller-selected pinned partition. */
  public UUID prepareOtherControllerNewInPartition(String seed, BigInteger partitionId) {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0
        || partitionId.compareTo(identity.issuerIndexPartitionCount()) >= 0) {
      throw new IllegalArgumentException("partitionId must be within the installed issuer layout");
    }
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
        .equals(partitionId));
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

  /** Replays the exact retained request for a previously prepared controller operation. */
  public UUID retryOtherControllerNew(String seed) {
    var operation = otherOperation(seed);
    var retried =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, issuerSource)
            .beginOrResumeOrdinaryNew(operation.request());
    otherOperations.put(seed, retried);
    return retried.operationId();
  }

  /** Uses the actual SQL preparation and physical Redis owner for another same-partition NEW. */
  public CanonicalGameplayIssuerBindingLocalRedisOwner.Readback projectOtherControllerNew(
      String seed) {
    var operation = otherOperation(seed);
    return new CanonicalGameplayIssuerBindingLocalRedisOwner(
            issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
        .projectAndRead(operation);
  }

  /** Advances the real account-wide control through a later acquisition intent. */
  public void beginAccountAcquisitionIntent(String seed) {
    new CanonicalGameplayAccountAdmissionBarrierRepository(dsl)
        .beginFenceIntent(
            identity.accountId(),
            UUID.nameUUIDFromBytes((seed + "-request").getBytes(StandardCharsets.UTF_8)));
  }

  /** Independently snapshots the actual issuer partition for no-write assertions. */
  public byte[] issuerPartitionFieldSetBytes() {
    Map<String, byte[]> fields = new LinkedHashMap<>();
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      connection
          .sync()
          .hgetall(issuerPartitionKey())
          .forEach((field, value) -> fields.put(new String(field, StandardCharsets.UTF_8), value));
    }
    return CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(fields);
  }

  /** Reads both exact physical indexes for another real issuer member. */
  public boolean hasExactOtherControllerMember(String seed) {
    var operation = otherOperation(seed);
    byte[] key = issuerPartitionKey();
    byte[] expected = operation.exactMemberValueBytes();
    byte[] capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                operation.reservation().reservationId())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] bindingField = operation.memberValue().owner().bindingRef().bytes();
    try (var connection =
        issuerRedisClient.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
      return Arrays.equals(connection.sync().hget(key, capacityField), expected)
          && Arrays.equals(connection.sync().hget(key, bindingField), expected);
    }
  }

  /** Publishes a controlled Account lease through the actual SQL and Redis provisional owners. */
  public void publishOtherControllerNew(String seed) {
    var operation = otherOperation(seed);
    var transitionId = operation.request().transitionId();
    var evidence = repository.readProvisionalCasEvidence(transitionId);
    var lease =
        otherLeases.computeIfAbsent(
            seed,
            ignored ->
                controlledLeaseEvidence(
                    operation.request().candidate(),
                    evidence.transition().bindingGeneration(),
                    target));
    repository.markProvisional(
        evidence,
        new CanonicalGameplayAdmissionDecision(
            transitionId,
            lease,
            operation.request().candidate(),
            evidence.transition().bindingGeneration(),
            null,
            target));
    new CanonicalGameplayIssuerBindingLocalRedisOwner(
            issuerRedisClient, issuerCatalog, localProjectionRepository, issuerSource)
        .projectAndRead(operation);
  }

  /** Records a controlled Account abort through the actual Game Session SQL abort owner. */
  public CanonicalGameplayAdmissionAbortSource recordOtherAccountAbort(String seed) {
    var operation = otherOperation(seed);
    var source = repository.readFirstNewAdmissionAbortSource(operation.request().transitionId());
    return repository.recordFirstNewAccountAbort(
        source, UUID.nameUUIDFromBytes((seed + "-cleanup").getBytes(StandardCharsets.UTF_8)));
  }

  CanonicalGameplayIssuerBindingLocalOperation otherOperation(String seed) {
    var operation = otherOperations.get(seed);
    if (operation == null) {
      throw new IllegalArgumentException("Other NEW operation was not prepared");
    }
    return operation;
  }

  CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork
      prepareOtherAddMutation(String seed) {
    return localProjectionRepository.prepareAddMutationWork(otherOperation(seed));
  }

  CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork
      readPreparedPartitionWork(CanonicalGameplayIssuerBindingLocalOperation operation) {
    return localProjectionRepository.readPreparedPartitionWork(operation);
  }

  CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork
      readCurrentOriginalPartitionWork() {
    return localProjectionRepository.readPreparedPartitionWork(localOperation);
  }

  public CanonicalGameplayIssuerBindingLocalRetirementOperation prepareIssuerRetirement(
      UUID transitionId) {
    return new CanonicalGameplayIssuerBindingLocalRetirementOperationRepository(dsl, issuerSource)
        .prepareOrResume(transitionId);
  }

  public CanonicalGameplayIssuerBindingLocalRetirementRedisOwner.Readback retireIssuerCapacity(
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    return new CanonicalGameplayIssuerBindingLocalRetirementRedisOwner(
            issuerRedisClient,
            retirementCatalog(),
            localProjectionRepository,
            new CanonicalGameplayIssuerBindingLocalRetirementOperationRepository(dsl, issuerSource),
            issuerSource)
        .retireAndRead(operation);
  }

  /** Public test bridge for the otherwise package-private retirement readback outcome. */
  public long retireIssuerCapacityOutcome(
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    return retireIssuerCapacity(operation).scriptOutcome();
  }

  /** Public test bridge for the otherwise package-private retirement operation status. */
  public boolean isIssuerRetirementComplete(
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    return operation.status()
        == CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED;
  }

  /** Adds the retirement script only for callers that exercise the new owner. */
  RedisScriptCatalog retirementCatalog() {
    return RedisScriptCatalog.fromContributions(
        List.of(
            new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution(),
            new CanonicalGameplayIssuerBindingLocalRedisScriptContribution(),
            new CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution(),
            new CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution()));
  }

  public byte[] issuerPartitionKey() {
    return CanonicalGameplayIssuerRedisKeys.forMember(localOperation.memberValue())
        .getBytes(StandardCharsets.US_ASCII);
  }

  public byte[] issuerPartitionKey(String seed) {
    return CanonicalGameplayIssuerRedisKeys.forMember(otherOperation(seed).memberValue())
        .getBytes(StandardCharsets.US_ASCII);
  }

  public BigInteger originalPartitionId() {
    return localOperation.reservation().partitionId();
  }

  public int originalBaselineReservationCount() {
    return localOperation.baselinePartitionProof().physicalReservationCount();
  }

  public int originalBaselineCountedCapacity() {
    return localOperation.baselinePartitionProof().physicalCountedCapacityCount();
  }

  public int originalBaselineBindingCount() {
    return localOperation.baselinePartitionProof().physicalBindingCount();
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

  private static AccountGameplayAdmissionLeaseEvidence controlledLeaseEvidence(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      CanonicalPlayableTarget target) {
    long now = System.currentTimeMillis();
    String nowSeconds = Long.toString(now / 1000L);
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("leaseKind", AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING.name());
    carrier.put("targetNamespace", target.targetNamespace());
    carrier.put(
        "callerWorkload",
        "spiffe://firemud/ns/" + target.targetNamespace() + "/sa/game-session-service");
    carrier.put("requestId", UUID.randomUUID().toString());
    carrier.put("leaseId", UUID.randomUUID().toString());
    carrier.put("leaseFence", "1");
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", candidate.accountId().toString());
    scope.put("tenantId", candidate.tenantId().toString());
    scope.put("realmId", target.realmId().toString());
    scope.put("worldSlug", target.worldSlug());
    scope.put("realmSlug", target.realmSlug());
    scope.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    scope.put("playableStateScope", target.playableStateScope());
    scope.put("gameInstanceId", target.canonicalGameInstanceId().toString());
    scope.put("characterId", candidate.characterId().toString());
    scope.put("sessionId", candidate.sessionId());
    scope.put("bindingGeneration", bindingGeneration.toString());
    scope.put("catalogRevision", Long.toString(target.catalogRevision()));
    scope.put("pointerVersion", Long.toString(target.pointerVersion()));
    scope.put("regionId", candidate.regionId().toString());
    scope.put("regionEpoch", candidate.regionEpoch().toString());
    carrier.put("bindingScope", scope);
    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", candidate.issuerAuthGeneration().toString(),
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(candidate.tenantId().toString(), "1"),
            "membershipAuthorityGeneration", Map.of(candidate.tenantId().toString(), "1"),
            "privateRealmGrantVersions", List.of()));
    carrier.put("issuanceFence", "1");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState", "ACTIVE",
            "membershipVersion", Map.of(candidate.tenantId().toString(), "1"),
            "membershipAuthorityGeneration", "1"));
    String prefix = "account:auth-authority:v1:";
    List<Map<String, Object>> checkpoints = new java.util.ArrayList<>();
    for (String stream :
        List.of(
            "account/" + candidate.accountId(),
            "issuer/" + candidate.issuerId(),
            "membership/" + candidate.accountId() + "/" + candidate.tenantId(),
            "tenant/" + candidate.tenantId())) {
      checkpoints.add(
          Map.of(
              "outboxStreamKey",
              prefix + stream,
              "outboxSequence",
              stream.startsWith("membership/") || stream.startsWith("tenant/") ? "1" : "0"));
    }
    carrier.put("outboxCheckpoints", checkpoints);
    carrier.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", candidate.accountId().toString()),
            Map.entry("operationId", UUID.randomUUID().toString()),
            Map.entry("issuanceRequestId", UUID.randomUUID().toString()),
            Map.entry("tokenJti", UUID.randomUUID().toString()),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("issuedAt", nowSeconds),
            Map.entry("notBefore", nowSeconds),
            Map.entry("expiresAt", Long.toString(now / 1000L + 60L))));
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put(
        "expiresAt",
        Long.toString(now + AccountGameplayAdmissionLeaseEvidence.MAX_DURATION_MILLIS));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private void seedQualifiedBootstrapMembers(int count, MigrationVersion migrationTarget) {
    var inventoryRepository = new CanonicalGameplayBindingInventoryRepository(dsl);
    boolean historicalProvisionalSetup =
        migrationTarget != null
            && migrationTarget.compareTo(MigrationVersion.fromVersion("1048")) < 0;
    for (int index = 0; index < count; index++) {
      seedQualifiedBootstrapMember(inventoryRepository, historicalProvisionalSetup);
    }
  }

  private void seedQualifiedBootstrapMember(
      CanonicalGameplayBindingInventoryRepository inventoryRepository,
      boolean historicalProvisionalSetup) {
    var candidate =
        new CanonicalGameplayBindingIdentity(
            UUID.randomUUID(),
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
    var reservation =
        new CanonicalIssuerReservationFenceEvidence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.ONE,
            BigInteger.TWO,
            BigInteger.ONE);
    UUID transitionId = UUID.randomUUID();
    inventoryRepository.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, UUID.randomUUID(), candidate, reservation, null, null));
    var prepared = inventoryRepository.readProvisionalCasEvidence(transitionId);
    var lease =
        controlledLeaseEvidence(candidate, prepared.transition().bindingGeneration(), target);
    var decision =
        new CanonicalGameplayAdmissionDecision(
            transitionId,
            lease,
            candidate,
            prepared.transition().bindingGeneration(),
            null,
            target);
    if (historicalProvisionalSetup) {
      seedHistoricalLeaseBoundProvisional(prepared, decision);
    } else {
      inventoryRepository.markProvisional(prepared, decision);
    }
  }

  /**
   * Controlled historical setup reproduces the predecessor's native lease-bound PROVISIONAL
   * statements at 83a13e834894a0dfdb1797a887243e27fd7c9a48, without requiring V1048 columns. This
   * setup does not prove current provisional publication or Account lease issuance. The subsequent
   * bootstrap, default-ordered upgrade and retained abort still execute their owners.
   */
  private void seedHistoricalLeaseBoundProvisional(
      CanonicalGameplayBindingProvisionalCasEvidence prepared,
      CanonicalGameplayAdmissionDecision decision) {
    assertThat(prepared.expectedPrior()).isNull();
    assertThat(prepared.transition().status())
        .isEqualTo(
            net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
                .Status.PREPARED);
    dsl.transaction(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision =
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
          BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
          BigInteger priorRevision = prepared.transition().inventoryRevision();
          assertThat(
                  transaction.fetchOne(
                      "SELECT id FROM game_instances"
                          + " WHERE tenant_id = ? AND id = ? AND game_instance_uuid = ? FOR UPDATE",
                      decision.targetEvidence().gameSessionTenantId(),
                      decision.targetEvidence().gameInstanceId(),
                      decision.targetEvidence().canonicalGameInstanceId()))
              .isNotNull();
          assertThat(
                  seedHistoricalProvisionalTransition(
                      transaction, prepared, decision, priorRevision, nextRevision))
              .isOne();
          assertThat(
                  transaction.execute(
                      "UPDATE game_session_canonical_gameplay_binding_inventory"
                          + " SET lifecycle = 'PROVISIONAL', inventory_revision = ?"
                          + " WHERE binding_ref = ? AND transition_id = ?"
                          + " AND lifecycle = 'CANDIDATE_PREPARED'"
                          + " AND binding_generation = ? AND account_index_fence = ?"
                          + " AND account_index_state = 'REPAIR_REQUIRED'"
                          + " AND issuer_index_state = 'REPAIR_REQUIRED' AND inventory_revision = ?",
                      new BigDecimal(nextRevision),
                      prepared.candidate().bindingRef().bytes(),
                      prepared.transition().transitionId(),
                      new BigDecimal(prepared.transition().bindingGeneration()),
                      prepared.transition().candidateAccountIndexFence(),
                      new BigDecimal(priorRevision)))
              .isOne();
          for (String obligationTable :
              List.of(
                  "game_session_canonical_binding_account_index_obligation",
                  "game_session_canonical_binding_issuer_index_obligation",
                  "game_session_canonical_binding_region_bridge_obligation")) {
            assertThat(
                    transaction.execute(
                        "UPDATE "
                            + obligationTable
                            + " SET inventory_revision = ? WHERE transition_id = ? AND inventory_revision = ?",
                        new BigDecimal(nextRevision),
                        prepared.transition().transitionId(),
                        new BigDecimal(priorRevision)))
                .isOne();
          }
          assertThat(
                  transaction.execute(
                      "UPDATE game_session_canonical_issuer_partition_reservation"
                          + " SET inventory_revision = ? WHERE reservation_id = ? AND transition_id = ?"
                          + " AND lifecycle = 'RESERVED' AND inventory_revision = ?",
                      new BigDecimal(nextRevision),
                      prepared.transition().issuerReservationId(),
                      prepared.transition().transitionId(),
                      new BigDecimal(priorRevision)))
              .isOne();
          assertThat(
                  transaction.execute(
                      "UPDATE game_session_canonical_binding_inventory_clock"
                          + " SET inventory_revision = ? WHERE singleton_id = 1 AND inventory_revision = ?",
                      new BigDecimal(nextRevision),
                      new BigDecimal(currentRevision)))
              .isOne();
        });
  }

  private static int seedHistoricalProvisionalTransition(
      DSLContext transaction,
      CanonicalGameplayBindingProvisionalCasEvidence current,
      CanonicalGameplayAdmissionDecision decision,
      BigInteger priorRevision,
      BigInteger nextRevision) {
    List<Object> values = new ArrayList<>();
    values.add(new BigDecimal(nextRevision));
    values.addAll(historicalAdmissionDecisionValues(decision));
    values.add(current.transition().transitionId());
    values.add(new BigDecimal(priorRevision));
    values.add(current.candidate().bindingRef().bytes());
    values.add(new BigDecimal(current.transition().bindingGeneration()));
    values.add(current.transition().candidateAccountIndexFence());
    String expectedPriorPredicate =
        "AND expected_prior_binding_ref IS NULL AND expected_prior_binding_generation IS NULL";
    values.add(decision.leaseExpiresAtEpochMillis().toString());
    String assignments =
        "admission_request_id = ?, admission_lease_id = ?, admission_lease_fence = ?,"
            + " admission_lease_kind = ?, admission_lease_digest = ?,"
            + " admission_lease_evidence = ?, admission_resume_episode_id = ?,"
            + " admission_expected_old_binding_generation = ?, admission_lease_expires_at = ?,"
            + " admission_target_namespace = ?, admission_target_tenant_slug = ?,"
            + " admission_target_tenant_id = ?, admission_target_world_slug = ?,"
            + " admission_target_world_display_name = ?, admission_target_realm_id = ?,"
            + " admission_target_realm_slug = ?, admission_target_realm_display_name = ?,"
            + " admission_target_game_session_tenant_id = ?, admission_target_game_instance_id = ?,"
            + " admission_target_playable_state_namespace_id = ?,"
            + " admission_target_playable_state_scope = ?,"
            + " admission_target_canonical_game_instance_id = ?,"
            + " admission_target_canonical_version_id = ?, admission_target_runtime_version_id = ?,"
            + " admission_target_catalog_revision = ?, admission_target_pointer_version = ?,"
            + " admission_target_pointer_snapshot_digest = ?,"
            + " admission_target_active_world_epoch = ?,"
            + " admission_target_initial_admission_request_id = ?,"
            + " admission_target_initial_admission_request_digest = ?,"
            + " admission_target_origin_kind = ?,"
            + " admission_target_expected_prior_pointer_version = ?,"
            + " admission_target_hold_id = ?, admission_target_hold_fence = ?,"
            + " admission_target_hold_binding_digest = ?, admission_target_audit_event_id = ?,"
            + " admission_target_owner_proof_digest = ?,"
            + " admission_target_owner_proof_outcome = ?,"
            + " admission_target_positive_durable_abort = ?,"
            + " admission_target_terminal_at = ?::timestamptz,"
            + " admission_target_character_creation_policy = ?";
    return transaction.execute(
        "UPDATE game_session_canonical_binding_transition"
            + " SET status = 'PROVISIONAL', inventory_revision = ?, "
            + assignments
            + " WHERE transition_id = ? AND status = 'PREPARED'"
            + " AND inventory_revision = ? AND candidate_binding_ref = ?"
            + " AND candidate_binding_generation = ? AND candidate_account_index_fence = ?"
            + " "
            + expectedPriorPredicate
            + " AND admission_request_id IS NULL AND admission_lease_id IS NULL"
            + " AND CAST(? AS NUMERIC) > FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)",
        values.toArray());
  }

  private static List<Object> historicalAdmissionDecisionValues(
      CanonicalGameplayAdmissionDecision decision) {
    CanonicalPlayableTarget target = decision.targetEvidence();
    List<Object> values = new ArrayList<>();
    values.add(decision.requestId());
    values.add(decision.leaseId());
    values.add(new BigDecimal(decision.leaseFence()));
    values.add(decision.leaseKind().name());
    values.add(decision.leaseSha256());
    values.add(decision.leaseEvidenceJson());
    values.add(decision.resumeEpisodeId());
    values.add(
        decision.expectedOldBindingGeneration() == null
            ? null
            : new BigDecimal(decision.expectedOldBindingGeneration()));
    values.add(new BigDecimal(decision.leaseExpiresAtEpochMillis()));
    values.add(target.targetNamespace());
    values.add(target.tenantSlug());
    values.add(target.canonicalTenantId());
    values.add(target.worldSlug());
    values.add(target.worldDisplayName());
    values.add(target.realmId());
    values.add(target.realmSlug());
    values.add(target.realmDisplayName());
    values.add(target.gameSessionTenantId());
    values.add(target.gameInstanceId());
    values.add(target.playableStateNamespaceId());
    values.add(target.playableStateScope());
    values.add(target.canonicalGameInstanceId());
    values.add(target.canonicalVersionId());
    values.add(target.runtimeVersionId());
    values.add(target.catalogRevision());
    values.add(target.pointerVersion());
    values.add(target.admissionPointerSnapshotDigest());
    values.add(target.activeWorldEpoch());
    values.add(target.initialAdmissionRequestId());
    values.add(target.initialAdmissionRequestDigest());
    values.add(target.initialAdmissionOriginKind().name());
    values.add(target.expectedPriorPointerVersion());
    values.add(target.holdId());
    values.add(target.holdFence());
    values.add(target.holdBindingDigest());
    values.add(target.auditEventId());
    values.add(target.ownerProofDigest());
    values.add(target.ownerProofOutcome().name());
    values.add(target.positiveDurableAbort());
    values.add(OffsetDateTime.ofInstant(target.ownerProofTerminalAt(), ZoneOffset.UTC));
    values.add(target.characterCreationPolicy());
    return values;
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
