package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixedPresetResources;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit owner-local fixture CAS and independent receipt readback. Not a Spring component, RPC,
 * admission proof, or replacement for authenticated lifecycle/hold composition.
 */
public final class WorldCanonicalInstanceFixtureActivationOwner {
  private final DSLContext dsl;
  private final WorldCanonicalInstanceAssociationRepository associations;
  private final WorldCanonicalInstanceFixtureActivationRepository storage;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public WorldCanonicalInstanceFixtureActivationOwner(
      DSLContext dsl, PlatformTransactionManager manager,
      WorldCanonicalInstanceAssociationRepository associations) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.associations = Objects.requireNonNull(associations, "associations");
    storage = new WorldCanonicalInstanceFixtureActivationRepository(dsl);
    write = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read = new TransactionTemplate(manager);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  public WorldCanonicalInstanceFixtureActivationResult activate(
      RunOwnedCanonicalWorldLaunchEvidence evidence, PreparedWorldInstanceRequest originalRequest,
      WorldCanonicalInstanceFixtureActivationRequest request) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(evidence, "evidence").requireFixedGameSessionPeer();
    Objects.requireNonNull(originalRequest, "originalRequest");
    Objects.requireNonNull(request, "request");
    evidence.requireMatches(originalRequest);
    if (evidence.gameSessionAssociation() == null
        || !request.canonicalGameInstanceId().equals(evidence.gameSessionAssociation().gameInstanceUuid())) {
      throw new IllegalArgumentException("Fixture activation Game Session instance differs");
    }
    Optional<WorldCanonicalInstanceFixtureActivationResult> retained = read(request, evidence, originalRequest);
    if (retained.isPresent()) return retained.orElseThrow();
    try {
      write.executeWithoutResult(status -> {
        var association = associations.readRunOwnedFixtureAssociationInActivationTransaction(
            evidence, originalRequest);
        var plan = plan(association);
        RunOwnedWorldFixedPresetResources.readCommitted(dsl, plan,
            association.worldInstanceId(), association.runOwnedFixture().worldPrivateKeys().gameInstanceId());
        storage.activateInOwnerTransaction(association, request, plan, request.canonicalBytes());
      });
    } catch (RuntimeException failure) {
      // A concurrent exact commit or ambiguous reply is accepted only after independent full proof.
      try {
        var reconciled = read(request, evidence, originalRequest);
        if (reconciled.isPresent()) return reconciled.orElseThrow();
      } catch (RuntimeException readFailure) {
        failure.addSuppressed(readFailure);
      }
      throw failure;
    }
    return read(request, evidence, originalRequest).orElseThrow(
        () -> new IllegalStateException("Fixture activation committed receipt was not reconstructed"));
  }

  public Optional<WorldCanonicalInstanceFixtureActivationResult> read(
      WorldCanonicalInstanceFixtureActivationRequest request) {
    return read(request, null, null);
  }

  private Optional<WorldCanonicalInstanceFixtureActivationResult> read(
      WorldCanonicalInstanceFixtureActivationRequest request,
      RunOwnedCanonicalWorldLaunchEvidence evidence, PreparedWorldInstanceRequest originalRequest) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    return Objects.requireNonNull(read.execute(status -> {
      var association = associations.readFixtureActivationAssociationInOwnerTransaction(request);
      if (association.isEmpty()) return Optional.<WorldCanonicalInstanceFixtureActivationResult>empty();
      var retained = association.orElseThrow();
      if (evidence != null) {
        WorldCanonicalInstanceAssociationRepository.requireFixtureAssociationMatches(
            retained, evidence, originalRequest);
      }
      var plan = plan(retained);
      var result = storage.readCommittedResultInOwnerTransaction(
          retained, request, plan, request.canonicalBytes());
      if (result.isEmpty()) throw new IllegalStateException("Fixture activation receipt proof differs");
      RunOwnedWorldFixedPresetResources.readActivated(dsl, plan, retained.worldInstanceId(),
          retained.runOwnedFixture().worldPrivateKeys().gameInstanceId(), request);
      return Optional.of(WorldCanonicalInstanceFixtureActivationResult.fromStored(request, result.orElseThrow()));
    }));
  }

  private static RunOwnedWorldFixedPresetResources.FixedPresetPlan plan(
      WorldCanonicalInstanceAssociation association) {
    return RunOwnedWorldFixedPresetResources.plan(association,
        association.runOwnedFixture().sourceIntakeReceipt(), association.versionIdentity());
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Fixture activation owner cannot join an ambient transaction");
    }
  }
}
