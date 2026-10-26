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
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
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
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;

/**
 * Actual GS durable decision owner for the Account/GS abort composition test. Launch producers,
 * initial admission target and empty-cohort/storage/index collaborators remain controlled fixtures.
 * The actual migration owner publishes VERIFIED; no collaborator supplies an Account terminal
 * result or replaces the GS reverse decision read.
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

  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource, Candidate accountCandidate) {
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
    repository.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId,
            UUID.randomUUID(),
            identity,
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.ONE),
            null,
            null));
    generation =
        repository.readProvisionalCasEvidence(transitionId).transition().bindingGeneration();
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
