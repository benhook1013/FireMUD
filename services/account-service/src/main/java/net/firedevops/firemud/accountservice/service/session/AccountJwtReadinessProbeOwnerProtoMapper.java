package net.firedevops.firemud.accountservice.service.session;

import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;

/** Strict closed-wire mapper; caller-local Account JWKS pins are validated but never echoed. */
public final class AccountJwtReadinessProbeOwnerProtoMapper {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_RECEIVER_METADATA_REQUEST_BYTES = 256;
  private static final String SHA256 = "[0-9a-f]{64}";
  private static final String UID =
      "[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  /** Strict parse of the two caller-selected local values; neither is owner evidence. */
  public ReceiverMetadataSelector parseReceiverMetadataRequest(
      GetCurrentReadinessReceiverMetadataRequest request) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || request.getSerializedSize() > MAX_RECEIVER_METADATA_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()) {
      throw new InvalidOwnerReadRequestException();
    }
    String podUid = request.getProjectedPodUid();
    String serverLeafSpkiSha256 = request.getServerLeafSpkiSha256();
    try {
      if (podUid.length() != 36 || serverLeafSpkiSha256.length() != 64) {
        throw new IllegalArgumentException();
      }
      UUID parsedUid = UUID.fromString(podUid);
      if (!parsedUid.toString().equals(podUid)
          || !podUid.matches(UID)
          || !serverLeafSpkiSha256.matches(SHA256)) {
        throw new IllegalArgumentException();
      }
      return new ReceiverMetadataSelector(podUid, serverLeafSpkiSha256);
    } catch (RuntimeException invalid) {
      throw new InvalidOwnerReadRequestException();
    }
  }

  public AccountJwtReadinessProbeOwnerSelector parse(GetCurrentReadinessProbeOwnerRequest request) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedCoordinates()
        || !request.hasProtectedLocalIdentity()
        || request.getCompactTokenSha256().isEmpty()) {
      throw new InvalidOwnerReadRequestException();
    }
    ReadinessProbeCoordinates coordinates = request.getExpectedCoordinates();
    var local = request.getProtectedLocalIdentity();
    if (!coordinates.getUnknownFields().asMap().isEmpty()
        || !local.getUnknownFields().asMap().isEmpty()
        || !local.hasAccountJwksSourceIdentity()
        || !local.getAccountJwksSourceIdentity().getUnknownFields().asMap().isEmpty()) {
      throw new InvalidOwnerReadRequestException();
    }
    try {
      ProbeKind probeKind =
          switch (coordinates.getProbeKind()) {
            case CANARY -> ProbeKind.CANARY;
            case REPRESENTATIVE -> ProbeKind.REPRESENTATIVE;
            case PROBE_KIND_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException();
          };
      ProbeExpectation expectedOutcome =
          switch (coordinates.getExpectedOutcome()) {
            case ACCEPT -> ProbeExpectation.ACCEPT;
            case INAPPLICABLE_REJECT -> ProbeExpectation.INAPPLICABLE_REJECT;
            case EXPECTED_OUTCOME_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException();
          };
      var activeFence =
          switch (coordinates.getExpectedActiveState()) {
            case ABSENT -> {
              if (!coordinates.getExpectedActiveGeneration().isEmpty()
                  || !coordinates.getExpectedActiveKid().isEmpty()) {
                throw new IllegalArgumentException();
              }
              yield java.util.Optional.<ActiveSigner>empty();
            }
            case PRESENT -> {
              if (coordinates.getExpectedActiveGeneration().isEmpty()
                  || coordinates.getExpectedActiveKid().isEmpty()) {
                throw new IllegalArgumentException();
              }
              yield java.util.Optional.of(
                  new ActiveSigner(
                      coordinates.getExpectedActiveGeneration(),
                      coordinates.getExpectedActiveKid()));
            }
            case EXPECTED_ACTIVE_STATE_UNSPECIFIED, UNKNOWN, UNRECOGNIZED ->
                throw new IllegalArgumentException();
          };
      var source = local.getAccountJwksSourceIdentity();
      // This is caller-local verifier-source context, not Account's Kubernetes inventory source.
      // Validate its closed bounded form, but never treat it as Account evidence or echo it.
      new SourceIdentity(
          source.getEnvironmentId(),
          source.getClusterId(),
          source.getClusterIncarnationUid(),
          source.getNamespace(),
          source.getNamespaceUid(),
          source.getConfigMapUid(),
          source.getBindingRevision(),
          source.getApiServerOrigin(),
          source.getServingCaSha256());
      if (local.getAccountJwksTrustBindingRevision().isEmpty()
          || !local.getAccountJwksTrustBindingRevision().equals(source.getBindingRevision())
          || !local.getAccountPublicJwksSha256().matches(SHA256)) {
        throw new IllegalArgumentException();
      }
      return new AccountJwtReadinessProbeOwnerSelector(
          canonicalUuid(coordinates.getRotationOperationId()),
          coordinates.getOperationDigest(),
          coordinates.getPlanDigest(),
          coordinates.getPlanVersion(),
          coordinates.getRegistryVersion(),
          coordinates.getValidatorId(),
          coordinates.getTokenProfile(),
          coordinates.getAudience(),
          probeKind,
          expectedOutcome,
          canonicalUuid(coordinates.getJti()),
          coordinates.getEntryVersion(),
          coordinates.getTargetGeneration(),
          coordinates.getTargetKid(),
          activeFence,
          coordinates.getIssuedAtEpochSeconds(),
          coordinates.getExpiresAtEpochSeconds(),
          coordinates.getPlanExpiresAtEpochSeconds(),
          request.getCompactTokenSha256(),
          new AccountJwtReadinessProbeOwnerSelector.LocalIdentity(
              local.getValidatorId(),
              local.getDeploymentUid(),
              local.getPodUid(),
              local.getPodIp(),
              local.getDirectPodEndpoint(),
              local.getCanonicalServiceUri(),
              local.getImage(),
              local.getVerifierConfigSha256(),
              local.getApplicabilityMatrixDigest(),
              local.getSourceInventoryRevision(),
              local.getSourceInventoryDigest(),
              local.getServerLeafSpkiSha256()));
    } catch (RuntimeException invalid) {
      throw new InvalidOwnerReadRequestException();
    }
  }

  public GetCurrentReadinessProbeOwnerResponse toResponse(OwnerProbeEvidence evidence) {
    ReadinessProbePlan plan = evidence.plan();
    ProbeEntry entry = evidence.entry();
    var target = evidence.expectedPod().target();
    ReadinessProbeCoordinates.Builder coordinates =
        ReadinessProbeCoordinates.newBuilder()
            .setRotationOperationId(plan.operationId().toString())
            .setOperationDigest(plan.operationDigest())
            .setPlanDigest(plan.planDigest())
            .setPlanVersion(plan.planVersion())
            .setRegistryVersion(entry.registryVersion())
            .setValidatorId(entry.validatorId())
            .setTokenProfile(entry.tokenProfile())
            .setAudience(entry.audience())
            .setProbeKind(
                entry.probeKind() == ProbeKind.CANARY
                    ? ReadinessProbeCoordinates.ProbeKind.CANARY
                    : ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
            .setExpectedOutcome(
                target.expectation() == ProbeExpectation.ACCEPT
                    ? ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
                    : ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
            .setJti(entry.jti().toString())
            .setEntryVersion(entry.entryVersion())
            .setTargetGeneration(entry.targetGeneration())
            .setTargetKid(entry.targetKid())
            .setIssuedAtEpochSeconds(entry.plannedIssuedAtEpochSecond())
            .setExpiresAtEpochSeconds(entry.expiresAtEpochSecond())
            .setPlanExpiresAtEpochSeconds(plan.expiresAtEpochSecond());
    entry
        .expectedActive()
        .ifPresentOrElse(
            active ->
                coordinates
                    .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.PRESENT)
                    .setExpectedActiveGeneration(active.generation())
                    .setExpectedActiveKid(active.kid()),
            () ->
                coordinates.setExpectedActiveState(
                    ReadinessProbeCoordinates.ExpectedActiveState.ABSENT));
    AccountPodTargetBinding accountTarget =
        AccountPodTargetBinding.newBuilder()
            .setInventorySnapshotDigest(target.inventorySnapshotDigest())
            .setEnvironmentId(target.environmentId())
            .setClusterId(target.clusterId())
            .setClusterIncarnationUid(target.clusterIncarnationUid())
            .setNamespace(target.namespace())
            .setNamespaceUid(target.namespaceUid())
            .setApiBindingRevision(target.apiBindingRevision())
            .setApiBindingDigest(target.apiBindingDigest())
            .setInventoryBindingRevision(target.inventoryBindingRevision())
            .setInventoryBindingDigest(target.inventoryBindingDigest())
            .setValidatorId(target.validatorId())
            .setDeploymentUid(target.deploymentUid())
            .setPodUid(target.podUid())
            .setPodIp(target.podIp())
            .setImage(target.image())
            .setVerifierConfigSha256(target.verifierConfigSha256())
            .setApplicabilityMatrixDigest(target.applicabilityMatrixDigest())
            .setExpectedOutcome(
                target.expectation() == ProbeExpectation.ACCEPT
                    ? ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
                    : ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
            .setDirectPodEndpoint(target.exactPodEndpoint().orElseThrow().toString())
            .setCanonicalServiceUri(target.canonicalServiceUri().orElseThrow())
            .setServerLeafSpkiSha256(target.podLeafSpkiSha256().orElseThrow())
            .build();
    return GetCurrentReadinessProbeOwnerResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setOwnerRecordFound(true)
        .setCurrentCoordinates(coordinates)
        .setCompactTokenSha256(entry.compactTokenSha256().orElseThrow())
        .setCurrentState(GetCurrentReadinessProbeOwnerResponse.ProbeState.ISSUED)
        .setCurrentPodTarget(accountTarget)
        .build();
  }

  public GetCurrentReadinessReceiverMetadataResponse toReceiverMetadataResponse(
      ReadinessProbePlan plan,
      InventorySnapshot inventory,
      ValidatorObservation validator,
      PodObservation pod,
      SourceIdentity jwksSourceIdentity,
      String publicJwksSha256) {
    AccountSourceIdentity sourceIdentity =
        AccountSourceIdentity.newBuilder()
            .setEnvironmentId(jwksSourceIdentity.environmentId())
            .setClusterId(jwksSourceIdentity.clusterId())
            .setClusterIncarnationUid(jwksSourceIdentity.clusterIncarnationUid())
            .setNamespace(jwksSourceIdentity.namespace())
            .setNamespaceUid(jwksSourceIdentity.namespaceUid())
            .setConfigMapUid(jwksSourceIdentity.configMapUid())
            .setBindingRevision(jwksSourceIdentity.bindingRevision())
            .setApiServerOrigin(jwksSourceIdentity.apiServerOrigin())
            .setServingCaSha256(jwksSourceIdentity.servingCaSha256())
            .build();
    ReadinessReceiverLocalIdentity identity =
        ReadinessReceiverLocalIdentity.newBuilder()
            .setValidatorId(validator.validatorId())
            .setDeploymentUid(validator.deploymentUid())
            .setPodUid(pod.uid())
            .setPodIp(pod.podIp())
            .setDirectPodEndpoint(pod.endpoint().toString())
            .setCanonicalServiceUri(pod.receiverServiceUri())
            .setImage(pod.image())
            .setVerifierConfigSha256(pod.verifierConfigSha256())
            .setApplicabilityMatrixDigest(plan.applicabilityMatrixDigest())
            .setSourceInventoryRevision(inventory.inventoryBindingRevision())
            .setSourceInventoryDigest(inventory.digest())
            .setServerLeafSpkiSha256(pod.leafSpkiSha256())
            .setAccountJwksSourceIdentity(sourceIdentity)
            .setAccountJwksTrustBindingRevision(jwksSourceIdentity.bindingRevision())
            .setAccountPublicJwksSha256(publicJwksSha256)
            .build();
    return GetCurrentReadinessReceiverMetadataResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setRotationOperationId(plan.operationId().toString())
        .setOperationDigest(plan.operationDigest())
        .setPlanDigest(plan.planDigest())
        .setCurrentIdentity(identity)
        .build();
  }

  private static UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException();
    }
    return parsed;
  }

  public record ReceiverMetadataSelector(String projectedPodUid, String serverLeafSpkiSha256) {}

  public static final class InvalidOwnerReadRequestException extends RuntimeException {
    public InvalidOwnerReadRequestException() {
      super("Readiness probe owner request is invalid");
    }
  }
}
