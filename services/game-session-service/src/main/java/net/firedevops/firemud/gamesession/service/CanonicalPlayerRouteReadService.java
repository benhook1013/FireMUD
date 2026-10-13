package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import org.jooq.exception.DataAccessException;

/**
 * Read-only composition of current Game Session catalog, OPEN proof, and owner-qualified runtime.
 */
public final class CanonicalPlayerRouteReadService {
  private final String targetNamespace;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final CanonicalInitialAdmissionRepository admissionRepository;
  private final CanonicalGameInstanceLaunchAssociationRepository launchRepository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected repository collaborators are retained only for this service's read composition")
  public CanonicalPlayerRouteReadService(
      String targetNamespace,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalInitialAdmissionRepository admissionRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository) {
    this.targetNamespace = targetNamespace;
    this.catalogRepository = catalogRepository;
    this.admissionRepository = admissionRepository;
    this.launchRepository = launchRepository;
  }

  /**
   * A default-off identity configuration produces unavailable authority without a startup error.
   */
  public static CanonicalPlayerRouteReadService unavailable() {
    return new CanonicalPlayerRouteReadService(null, null, null, null);
  }

  /** Lists only current visible public-production routes from canonical owner evidence. */
  public List<CanonicalPlayableTarget> readVisiblePublicProductionTargets() {
    requireConfigured();
    List<CanonicalRealmCatalogSnapshot> catalogs;
    try {
      catalogs = catalogRepository.readVisiblePublicProductionSnapshots(targetNamespace);
    } catch (DataAccessException ex) {
      throw new ReadUnavailableException("Canonical realm catalog read is unavailable", ex);
    } catch (RuntimeException ex) {
      throw new InvalidAuthorityException("Canonical realm catalog is invalid", ex);
    }
    if (catalogs == null) {
      throw new ReadUnavailableException("Canonical realm catalog read returned no result");
    }

    Set<UUID> tenantIds = new HashSet<>();
    ArrayList<CanonicalPlayableTarget> targets = new ArrayList<>(catalogs.size());
    for (CanonicalRealmCatalogSnapshot catalog : catalogs) {
      if (catalog == null || !tenantIds.add(catalog.tenantId())) {
        throw new InvalidAuthorityException(
            "Canonical public-production catalog has duplicate tenant authority");
      }
      targets.add(readTargetForCatalog(catalog));
    }
    return List.copyOf(targets);
  }

  /** Rereads the selected tenant's unique catalog, current OPEN pointer proof, and launch row. */
  public CanonicalPlayableTarget readCurrentTarget(CanonicalPlayableTarget expected) {
    Objects.requireNonNull(expected, "expected");
    requireConfigured();
    if (!targetNamespace.equals(expected.targetNamespace())) {
      throw new InvalidAuthorityException("Selected route belongs to another owner namespace");
    }
    return readCurrentTarget(expected.canonicalTenantId());
  }

  /**
   * Reads one current target by its canonical tenant selector, without trusting caller counters.
   */
  public CanonicalPlayableTarget readCurrentTarget(UUID canonicalTenantId) {
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    if (new UUID(0L, 0L).equals(canonicalTenantId)) {
      throw new InvalidAuthorityException("Canonical tenant selector must be non-nil");
    }
    requireConfigured();
    CanonicalRealmCatalogSnapshot catalog;
    try {
      catalog =
          catalogRepository
              .readUniqueVisiblePublicProduction(targetNamespace, canonicalTenantId)
              .orElseThrow(
                  () ->
                      new InvalidAuthorityException(
                          "Canonical public-production realm catalog is missing"));
    } catch (ReadUnavailableException | InvalidAuthorityException ex) {
      throw ex;
    } catch (DataAccessException ex) {
      throw new ReadUnavailableException("Canonical realm catalog read is unavailable", ex);
    } catch (RuntimeException ex) {
      throw new InvalidAuthorityException("Canonical realm catalog is invalid", ex);
    }
    return readTargetForCatalog(catalog);
  }

  /**
   * Returns false for reachable stale or malformed authority; transport/storage failure escapes.
   */
  public boolean matchesCurrentTarget(CanonicalPlayableTarget expected) {
    try {
      return expected.equals(readCurrentTarget(expected));
    } catch (ReadUnavailableException ex) {
      throw ex;
    } catch (InvalidAuthorityException ex) {
      return false;
    }
  }

  private CanonicalPlayableTarget readTargetForCatalog(CanonicalRealmCatalogSnapshot catalog) {
    CanonicalInitialAdmissionRepository.CurrentOpenSnapshot openSnapshot;
    try {
      openSnapshot =
          admissionRepository.readCurrentOpenSnapshotForRealm(
              targetNamespace, catalog.tenantId(), catalog.realmId());
    } catch (DataAccessException ex) {
      throw new ReadUnavailableException("Canonical admission owner read is unavailable", ex);
    } catch (RuntimeException ex) {
      throw new InvalidAuthorityException(
          "Canonical OPEN pointer or terminal owner proof is invalid", ex);
    }
    CanonicalInitialAdmissionOwnerProof proof = openSnapshot.ownerProof();
    requireCatalogMatchesProof(catalog, proof);

    CanonicalInitialAdmissionLaunchTarget launch;
    try {
      launch =
          launchRepository
              .readForInitialAdmission(
                  targetNamespace,
                  catalog.tenantId(),
                  catalog.realmId(),
                  proof.canonicalGameInstanceId())
              .orElseThrow(
                  () ->
                      new InvalidAuthorityException(
                          "Canonical OPEN target has no owner-qualified launch association"));
    } catch (DataAccessException ex) {
      throw new ReadUnavailableException("Canonical launch association read is unavailable", ex);
    } catch (InvalidAuthorityException | ReadUnavailableException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new InvalidAuthorityException("Canonical launch association is invalid", ex);
    }
    requireLaunchMatchesCatalogAndProof(catalog, proof, launch);

    return new CanonicalPlayableTarget(
        targetNamespace,
        catalog.tenantSlug(),
        catalog.tenantId(),
        catalog.worldSlug(),
        catalog.sourceIntakeReceipt().source().worldDisplayName(),
        catalog.realmId(),
        catalog.realmSlug(),
        catalog.realmDisplayName(),
        launch.association().gameSessionTenantId(),
        launch.gameInstanceId(),
        catalog.playableStateNamespaceId(),
        catalog.stateScope(),
        proof.canonicalGameInstanceId(),
        proof.canonicalVersionId(),
        launch.runtimeVersionId(),
        catalog.catalogRevision(),
        proof.committedPointerVersion(),
        openSnapshot.admissionPointerSnapshotDigest(),
        proof.activeLifecycleEpoch(),
        proof.initialAdmissionRequestId(),
        proof.requestDigest(),
        proof.originKind(),
        proof.expectedPriorPointerVersion(),
        proof.holdId(),
        proof.holdFence(),
        proof.holdBindingDigest(),
        proof.auditEventId(),
        proof.proofDigest(),
        proof.outcome(),
        proof.positiveDurableAbort(),
        proof.terminalAt(),
        catalog.characterCreationPolicy());
  }

  private void requireCatalogMatchesProof(
      CanonicalRealmCatalogSnapshot catalog, CanonicalInitialAdmissionOwnerProof proof) {
    if (proof == null
        || !proof.provesCommit()
        || !targetNamespace.equals(proof.targetNamespace())
        || !targetNamespace.equals(catalog.targetNamespace())
        || !catalog.tenantId().equals(proof.canonicalTenantId())
        || !catalog.worldSlug().equals(proof.worldSlug())
        || !catalog.realmId().equals(proof.realmId())
        || !catalog.playableStateNamespaceId().equals(proof.playableStateNamespaceId())
        || !catalog.stateScope().equals(proof.playableStateScope())
        || catalog.catalogRevision() != proof.expectedCatalogRevision()
        || proof.committedPointerVersion() == null
        || proof.auditEventId() == null
        || proof.proofDigest() == null
        || proof.terminalAt() == null) {
      throw new InvalidAuthorityException(
          "Canonical catalog does not match the committed current OPEN proof");
    }
  }

  private void requireLaunchMatchesCatalogAndProof(
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionOwnerProof proof,
      CanonicalInitialAdmissionLaunchTarget launch) {
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    if (association.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING
        || !association.targetNamespace().equals(targetNamespace)
        || !association.canonicalTenantId().equals(catalog.tenantId())
        || !association.worldSlug().equals(catalog.worldSlug())
        || !association.playableStateNamespaceId().equals(catalog.playableStateNamespaceId())
        || !association.playableStateScope().name().equals(catalog.stateScope())
        || !association.publicProduction()
        || !association.gameInstanceUuid().equals(proof.canonicalGameInstanceId())
        || !launch.realmId().equals(catalog.realmId())
        || !launch.canonicalVersionId().equals(proof.canonicalVersionId())) {
      throw new InvalidAuthorityException(
          "Canonical launch association does not match the current catalog and OPEN proof");
    }
  }

  private void requireConfigured() {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || catalogRepository == null
        || admissionRepository == null
        || launchRepository == null) {
      throw new ReadUnavailableException("Canonical player route authority is not configured");
    }
  }

  public static final class ReadUnavailableException extends RuntimeException {
    public ReadUnavailableException(String message) {
      super(message);
    }

    public ReadUnavailableException(String message, RuntimeException cause) {
      super(message, cause);
    }
  }

  public static final class InvalidAuthorityException extends RuntimeException {
    public InvalidAuthorityException(String message) {
      super(message);
    }

    public InvalidAuthorityException(String message, RuntimeException cause) {
      super(message, cause);
    }
  }
}
