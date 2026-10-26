package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.UUID;
import java.util.EnumSet;
import java.util.List;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Actual GS durable decision owner for the Account/GS abort composition test. Launch producers,
 * initial admission target and empty-cohort/storage/index collaborators remain controlled fixtures.
 * The actual migration owner publishes VERIFIED; no collaborator supplies an Account terminal
 * result or replaces the GS reverse decision read.
 */
public final class CanonicalGameplayAdmissionAbortCompositionFixture implements AutoCloseable {
  private final DriverManagerDataSource dataSource;
  private final String schema = "gs_abort_composition_" + UUID.randomUUID().toString().replace("-", "");
  private final CanonicalGameplayBindingInventoryRepository repository;
  private final CanonicalPlayableTarget target;
  private final CanonicalGameplayBindingIdentity identity;
  private final UUID transitionId = UUID.randomUUID();
  private final BigInteger generation;
  private final DSLContext dsl;

  public CanonicalGameplayAdmissionAbortCompositionFixture(
      DriverManagerDataSource accountDataSource, Candidate accountCandidate) {
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(accountDataSource.getUrl().replaceAll("currentSchema=[^&]+", "currentSchema=" + schema));
    dataSource.setUsername(accountDataSource.getUsername());
    dataSource.setPassword(accountDataSource.getPassword());
    Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations("filesystem:" + Path.of("../game-session-service/src/main/resources/db/migration").toAbsolutePath().normalize())
        .load().migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    var runtime = CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, accountCandidate.tenantId(), "test");
    target = runtime.playableTarget();
    identity = new CanonicalGameplayBindingIdentity(
        accountCandidate.accountId(), accountCandidate.tenantId(), target.playableStateNamespaceId(),
        accountCandidate.characterId(), target.playableStateScope(), target.canonicalGameInstanceId(),
        target.gameInstanceId(), accountCandidate.sessionId().toString(), accountCandidate.regionId(),
        accountCandidate.regionEpoch(), accountCandidate.issuerId(), accountCandidate.issuerAuthGeneration(),
        accountCandidate.issuerIndexLayoutVersion(), accountCandidate.issuerIndexPartitionCount(),
        accountCandidate.issuerIndexPartitionCapacity());
    establishActualLegacyDisposition(dsl);
    repository = new CanonicalGameplayBindingInventoryRepository(dsl);
    repository.prepare(new CanonicalGameplayBindingTransitionRequest(
        transitionId, UUID.randomUUID(), identity,
        new CanonicalIssuerReservationFenceEvidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            BigInteger.ONE, BigInteger.ONE, BigInteger.ONE), null, null));
    generation = repository.readProvisionalCasEvidence(transitionId).transition().bindingGeneration();
  }

  /** Actual migration state machine over explicitly controlled empty-cohort collaborators. */
  private static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity = new CanonicalGameplayLegacyMigrationStorageIdentity(
        "controlled-cluster-uid", "controlled-namespace-uid", "controlled-producer-pod-uid",
        "controlled-container-id", "controlled-node-uid", "123456789", 42L,
        "controlled-postgres-volume-uid", "controlled-redis-run-id", "controlled-redis-volume-uid");
    var fencedCohort = new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
      @Override public UUID cohortId() { return cohortId; }
      @Override public UUID legacyWriterFence() { return writerFence; }
      @Override public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
        return storageIdentity;
      }
      @Override public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
        if (!storageIdentity.equals(expected)) {
          throw new IllegalStateException("Controlled test storage identity changed");
        }
      }
    };
    var operation = new CanonicalGameplayLegacyMigrationOwner(
        migration, inventory, () -> fencedCohort,
        ignored -> new CanonicalGameplayLegacyMigrationSourceSnapshot(
            EnumSet.of(Family.TENANT_SESSION_CONTEXT, Family.SESSION_ALIAS_CONTEXT,
                Family.GAMEPLAY_IDENTITY_CONTEXT, Family.GAMEPLAY_NAME_CONTEXT,
                Family.MOVEMENT_EFFECT, Family.DURABLE_EFFECT), List.of()),
        new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
          @Override public void rebuildExact(CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
              CanonicalGameplayLegacyMigrationSourceSnapshot source,
              CanonicalGameplayBindingInventorySnapshot canonical) {
            requireEmptyCanonical(canonical);
          }
          @Override public CanonicalGameplayLegacyMigrationReadback readBackExact(
              CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
              CanonicalGameplayBindingInventorySnapshot canonical) {
            requireEmptyCanonical(canonical);
            return new CanonicalGameplayLegacyMigrationReadback(List.of(), List.of(), List.of(), List.of());
          }
        }).migrate();
    if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
      throw new IllegalStateException("Actual legacy migration owner did not publish VERIFIED");
    }
  }

  private static void requireEmptyCanonical(CanonicalGameplayBindingInventorySnapshot canonical) {
    if (!canonical.bindings().isEmpty() || !canonical.reservations().isEmpty()
        || !canonical.transitions().isEmpty() || !canonical.accountIndexObligations().isEmpty()
        || !canonical.issuerIndexObligations().isEmpty() || !canonical.regionBridgeObligations().isEmpty()) {
      throw new IllegalStateException("Controlled empty-cohort collaborator found canonical state");
    }
  }

  public Candidate candidate() {
    return new Candidate(identity.accountId(), identity.tenantId(), identity.playableStateNamespaceId(),
        identity.characterId(), identity.playableStateScope(), identity.gameInstanceId(), identity.runtimeGameInstanceId(),
        UUID.fromString(identity.sessionId()), identity.regionId(), identity.regionEpoch(), identity.issuerId(),
        identity.issuerAuthGeneration(), identity.issuerIndexLayoutVersion(), identity.issuerIndexPartitionCount(),
        identity.issuerIndexPartitionCapacity());
  }

  public Target target() {
    return new Target(target.targetNamespace(), target.tenantSlug(), target.canonicalTenantId(), target.worldSlug(),
        target.worldDisplayName(), target.realmId(), target.realmSlug(), target.realmDisplayName(), target.gameSessionTenantId(),
        target.gameInstanceId(), target.playableStateNamespaceId(), target.playableStateScope(), target.canonicalGameInstanceId(),
        target.canonicalVersionId(), target.runtimeVersionId(), target.catalogRevision(), target.pointerVersion(),
        target.admissionPointerSnapshotDigest(), target.activeWorldEpoch(), target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(), CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind.NO_PRIOR_POINTER,
        null, target.holdId(), target.holdFence(), target.holdBindingDigest(), target.auditEventId(), target.ownerProofDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED, false,
        target.ownerProofTerminalAt(), target.characterCreationPolicy());
  }

  public void publish(AccountGameplayAdmissionLeaseEvidence lease) {
    repository.markProvisional(repository.readProvisionalCasEvidence(transitionId),
        new CanonicalGameplayAdmissionDecision(transitionId, lease, identity, generation, null, target));
  }

  /** Attempts a newer candidate; the retained unresolved original must fence preparation. */
  public BigInteger prepareNewerCandidate() {
    var newer = new CanonicalGameplayBindingIdentity(identity.accountId(), identity.tenantId(),
        identity.playableStateNamespaceId(), identity.characterId(), identity.playableStateScope(),
        identity.gameInstanceId(), identity.runtimeGameInstanceId(), UUID.randomUUID().toString(),
        identity.regionId(), identity.regionEpoch(), identity.issuerId(), identity.issuerAuthGeneration(),
        identity.issuerIndexLayoutVersion(), identity.issuerIndexPartitionCount(), identity.issuerIndexPartitionCapacity());
    return repository.prepare(new CanonicalGameplayBindingTransitionRequest(UUID.randomUUID(), UUID.randomUUID(), newer,
        new CanonicalIssuerReservationFenceEvidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            BigInteger.ONE, BigInteger.ONE, BigInteger.ONE), null, null)).bindingGeneration();
  }

  /** Uses the actual runtime repository; a retained pending lease must fence this mutation. */
  public void stopRuntime() {
    var runtimeRepository = new GameInstanceRepository(dsl);
    var runtime = runtimeRepository.findById(target.gameInstanceId()).orElseThrow();
    runtime.setStatus("STOPPED");
    runtimeRepository.save(runtime);
  }

  public UUID transitionId() { return transitionId; }
  public BigInteger generation() { return generation; }
  public DSLContext dsl() { return dsl; }
  public CanonicalGameplayAdmissionAbortSource source() { return repository.readFirstNewAdmissionAbortSource(transitionId); }

  @Override public void close() {
    dsl.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
  }
}
