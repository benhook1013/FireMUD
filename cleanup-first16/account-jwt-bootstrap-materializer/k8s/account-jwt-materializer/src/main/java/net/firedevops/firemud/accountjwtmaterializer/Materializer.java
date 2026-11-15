package net.firedevops.firemud.accountjwtmaterializer;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.EnrollBootstrapResponse;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;

/** One bounded reconciliation pass. Account remains the lifecycle and promotion authority. */
final class Materializer {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern GENERATION = Pattern.compile("[1-9][0-9]{0,63}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern RESOURCE_VERSION = Pattern.compile("[!-~]{1,256}");
  private static final Pattern CONFIG_REVISION =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private final ProtectedBinding.Snapshot snapshot;
  private final AccountOperations account;
  private final KubernetesOperations kubernetes;

  Materializer(
      ProtectedBinding.Snapshot snapshot,
      AccountOperations account,
      KubernetesOperations kubernetes) {
    this.snapshot = snapshot;
    this.account = account;
    this.kubernetes = kubernetes;
  }

  Outcome reconcileOnce() {
    kubernetes.verifyClusterIdentity();
    validateEnrollment(account.enrollBootstrap());
    GetCurrentPromotionRequestResponse promotion = currentPromotionOrNull();
    if (promotion != null) {
      return promote(promotion);
    }

    Outcome generationOutcome = generateOrObserve();
    promotion = currentPromotionOrNull();
    if (promotion != null) {
      return promote(promotion);
    }
    return generationOutcome;
  }

  Outcome observeSecretOnce() {
    kubernetes.verifyClusterIdentity();
    validateEnrollment(account.enrollBootstrap());
    if (currentPromotionOrNull() != null) {
      throw denied();
    }

    GetCurrentGenerationRequestResponse request = account.getCurrentGenerationRequest();
    validateGenerationRequest(request, false);
    if (request.getPhase() != GetCurrentGenerationRequestResponse.Phase.OBSERVE_PRIVATE_SECRET
        || request.hasExpectedActive()
        || request.hasExpectedPublishedActive()) {
      throw denied();
    }
    SecretState observed = kubernetes.getFixedSecret();
    validateObservedActiveSigner(request, observed);
    if (!observed.signerSlots().isEmpty()
        || observed.hasAnnotation(SecretState.GENERATION_OPERATION)
        || observed.hasAnnotation(SecretState.GENERATION_REQUEST)
        || observed.hasAnnotation(SecretState.GENERATION_PRIOR_RV)
        || observed.hasAnnotation(SecretState.PROMOTION_OPERATION)
        || observed.hasAnnotation(SecretState.PROMOTION_REQUEST)
        || observed.hasAnnotation(SecretState.PROMOTION_PRIOR_RV)) {
      throw denied();
    }

    GetCurrentGenerationRequestResponse next =
        account.recordSecretObservation(
            request.getOperationId(),
            request.getOperationDigest(),
            observed.uid(),
            observed.resourceVersion());
    validateGenerationRequest(next, true);
    if (next.getPhase() != GetCurrentGenerationRequestResponse.Phase.GENERATE_PENDING
        || !request.getOperationId().equals(next.getOperationId())
        || !request.getOperationDigest().equals(next.getOperationDigest())
        || !observed.uid().equals(next.getSecretUid())
        || !observed.resourceVersion().equals(next.getExpectedSecretResourceVersion())) {
      throw denied();
    }
    return Outcome.SECRET_OBSERVATION_RECORDED;
  }

  private void validateEnrollment(EnrollBootstrapResponse acknowledgement) {
    ProtectedBinding.Binding binding = snapshot.binding();
    if (acknowledgement == null
        || !acknowledgement.getUnknownFields().asMap().isEmpty()
        || acknowledgement.getSchemaVersion() != 1
        || !binding.environmentId().equals(acknowledgement.getEnvironmentId())
        || !binding.clusterId().equals(acknowledgement.getClusterId())
        || !binding.namespace().equals(acknowledgement.getNamespace())
        || !binding
            .expectedClusterIncarnationUid()
            .equals(acknowledgement.getExpectedClusterIncarnationUid())
        || !binding.expectedNamespaceUid().equals(acknowledgement.getExpectedNamespaceUid())
        || !binding
            .accountTrustBindingDigest()
            .equals(acknowledgement.getMaterializerTrustBindingDigest())
        || !binding.configRevision().equals(acknowledgement.getMaterializerTrustConfigRevision())
        || !sha256(acknowledgement.getApiBindingDigest())
        || !CONFIG_REVISION.matcher(acknowledgement.getApiConfigRevision()).matches()
        || !validApiIdentity(acknowledgement)
        || !SecretState.NAME.equals(acknowledgement.getPrivateSecretName())
        || !"jwt-jwks".equals(acknowledgement.getPublicConfigMapName())
        || !canonicalUuid(acknowledgement.getPublicConfigMapUid())
        || !resourceVersion(acknowledgement.getPublicConfigMapResourceVersion())
        || !sha256(acknowledgement.getPublicConfigMapSnapshotDigest())) {
      throw denied();
    }
  }

  private boolean validApiIdentity(EnrollBootstrapResponse acknowledgement) {
    try {
      String apiServerUrl = acknowledgement.getApiServerUrl();
      String tlsServerName = acknowledgement.getApiTlsServerName();
      if (apiServerUrl.length() > 512 || tlsServerName.length() > 253) {
        return false;
      }
      java.net.URI api = java.net.URI.create(apiServerUrl);
      return "https".equals(api.getScheme())
          && api.getHost() != null
          && api.getRawUserInfo() == null
          && api.getRawQuery() == null
          && api.getRawFragment() == null
          && (api.getRawPath() == null
              || api.getRawPath().isEmpty()
              || "/".equals(api.getRawPath()))
          && api.getHost().equals(tlsServerName)
          && sha256(acknowledgement.getApiServingCaSha256())
          && ("system:serviceaccount:" + snapshot.binding().namespace() + ":account-service")
              .equals(acknowledgement.getApiExpectedUsername());
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      return (parsed.getLeastSignificantBits() != 0L || parsed.getMostSignificantBits() != 0L)
          && parsed.toString().equals(value);
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private Outcome generateOrObserve() {
    GetCurrentGenerationRequestResponse request = account.getCurrentGenerationRequest();
    validateGenerationRequest(request, false);
    if (request.getPhase() == GetCurrentGenerationRequestResponse.Phase.OBSERVE_PRIVATE_SECRET) {
      SecretState observed = kubernetes.getFixedSecret();
      validateObservedActiveSigner(request, observed);
      GetCurrentGenerationRequestResponse next =
          account.recordSecretObservation(
              request.getOperationId(),
              request.getOperationDigest(),
              observed.uid(),
              observed.resourceVersion());
      validateGenerationRequest(next, true);
      if (next.getPhase() != GetCurrentGenerationRequestResponse.Phase.GENERATE_PENDING) {
        throw denied();
      }
      request = next;
    }
    if (request.getPhase() == GetCurrentGenerationRequestResponse.Phase.GENERATION_RECORDED) {
      return Outcome.GENERATION_ALREADY_RECORDED;
    }
    if (request.getPhase() != GetCurrentGenerationRequestResponse.Phase.GENERATE_PENDING) {
      throw denied();
    }
    return generatePending(request);
  }

  private Outcome generatePending(GetCurrentGenerationRequestResponse request) {
    ProtectedBinding.Binding binding = snapshot.binding();
    SecretState before = kubernetes.getFixedSecret();
    requireSecretIdentity(before.uid(), request.getSecretUid());
    requireResourceVersion(request.getExpectedSecretResourceVersion());
    String operationId = request.getOperationId();
    String requestDigest = request.getGenerationRequestDigest();
    String priorResourceVersion = request.getExpectedSecretResourceVersion();
    boolean exactMarker =
        before.operationMarkerMatches(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV,
            operationId,
            requestDigest,
            priorResourceVersion);

    SignerKeyBundle.PublicIdentity identity;
    String publicJwk;
    if (exactMarker) {
      if (before.resourceVersion().equals(priorResourceVersion)) {
        throw denied();
      }
      byte[] storedPending = before.slotBytes(SecretState.PENDING);
      try {
        identity =
            SignerKeyBundle.verify(
                storedPending,
                binding,
                operationId,
                request.getTargetGeneration(),
                request.getTargetKid(),
                null);
        publicJwk =
            SignerKeyBundle.publicJwkForBundle(
                storedPending,
                binding,
                operationId,
                request.getTargetGeneration(),
                request.getTargetKid(),
                identity.fingerprint());
      } finally {
        wipe(storedPending);
      }
      validateObservedActiveSigner(request, before);
    } else {
      boolean sameOperationMarker =
          operationId.equals(before.annotation(SecretState.GENERATION_OPERATION))
              || requestDigest.equals(before.annotation(SecretState.GENERATION_REQUEST))
              || priorResourceVersion.equals(before.annotation(SecretState.GENERATION_PRIOR_RV));
      if (sameOperationMarker
          || before.containsSlot(SecretState.PENDING)
          || !before.resourceVersion().equals(priorResourceVersion)) {
        throw denied();
      }
      validateObservedActiveSigner(request, before);
      try (SignerKeyBundle.Generated generated =
          SignerKeyBundle.generate(
              binding, operationId, request.getTargetGeneration(), request.getTargetKid())) {
        byte[] bundle = generated.bundleBytes().clone();
        try {
          byte[] patch =
              before.generationPatch(bundle, operationId, requestDigest, priorResourceVersion);
          try {
            kubernetes.patchFixedSecret(before, patch);
          } catch (RuntimeException ambiguousWrite) {
            // Read-only recovery is permitted only if the exact non-secret marker and pending
            // bundle now read back. This path never retries or replaces an ambiguous write.
          }
          SecretState after = kubernetes.getFixedSecret();
          requireSecretIdentity(after.uid(), request.getSecretUid());
          requireChangedResourceVersion(priorResourceVersion, after.resourceVersion());
          requireGenerationMarker(after, request);
          before.requireUnchangedExcept(
              Set.of(SecretState.PENDING),
              Set.of(
                  SecretState.GENERATION_OPERATION,
                  SecretState.GENERATION_REQUEST,
                  SecretState.GENERATION_PRIOR_RV),
              after);
          byte[] readback = after.slotBytes(SecretState.PENDING);
          try {
            identity =
                SignerKeyBundle.verify(
                    readback,
                    binding,
                    operationId,
                    request.getTargetGeneration(),
                    request.getTargetKid(),
                    generated.identity().fingerprint());
          } finally {
            wipe(readback);
          }
          publicJwk = generated.publicJwkJson();
          validateObservedActiveSigner(request, after);
          return recordGeneration(request, after, identity, publicJwk);
        } finally {
          wipe(bundle);
        }
      }
    }
    SecretState readback = kubernetes.getFixedSecret();
    requireSecretIdentity(readback.uid(), request.getSecretUid());
    requireChangedResourceVersion(priorResourceVersion, readback.resourceVersion());
    requireGenerationMarker(readback, request);
    byte[] persisted = readback.slotBytes(SecretState.PENDING);
    try {
      SignerKeyBundle.verify(
          persisted,
          binding,
          operationId,
          request.getTargetGeneration(),
          request.getTargetKid(),
          identity.fingerprint());
    } finally {
      wipe(persisted);
    }
    validateObservedActiveSigner(request, readback);
    return recordGeneration(request, readback, identity, publicJwk);
  }

  private Outcome recordGeneration(
      GetCurrentGenerationRequestResponse request,
      SecretState secret,
      SignerKeyBundle.PublicIdentity identity,
      String publicJwk) {
    var receipt =
        account.recordGenerationResult(
            request.getOperationId(),
            request.getGenerationRequestDigest(),
            secret.uid(),
            request.getExpectedSecretResourceVersion(),
            secret.resourceVersion(),
            publicJwk);
    requireGenerationReceipt(request, secret, identity, publicJwk, receipt);
    return Outcome.GENERATION_RECORDED;
  }

  private Outcome promote(GetCurrentPromotionRequestResponse request) {
    validatePromotionRequest(request);
    ProtectedBinding.Binding binding = snapshot.binding();
    SecretState before = kubernetes.getFixedSecret();
    requireSecretIdentity(before.uid(), request.getExpectedSecretUid());
    requireResourceVersion(request.getExpectedSecretResourceVersion());
    String operationId = request.getPromotionOperationId();
    String requestDigest = request.getPromotionRequestDigest();
    String priorResourceVersion = request.getExpectedSecretResourceVersion();
    boolean exactMarker =
        before.operationMarkerMatches(
            SecretState.PROMOTION_OPERATION,
            SecretState.PROMOTION_REQUEST,
            SecretState.PROMOTION_PRIOR_RV,
            operationId,
            requestDigest,
            priorResourceVersion);
    if (exactMarker) {
      if (before.resourceVersion().equals(priorResourceVersion)) {
        throw denied();
      }
      VerifiedPromotion state = verifyPromotedState(before, request);
      return recordPromotion(request, before, state.current(), state.previous());
    }
    boolean sameOperationMarker =
        operationId.equals(before.annotation(SecretState.PROMOTION_OPERATION))
            || requestDigest.equals(before.annotation(SecretState.PROMOTION_REQUEST))
            || priorResourceVersion.equals(before.annotation(SecretState.PROMOTION_PRIOR_RV));
    if (sameOperationMarker || !before.resourceVersion().equals(priorResourceVersion)) {
      throw denied();
    }
    byte[] pending = before.slotBytes(SecretState.PENDING);
    byte[] oldCurrent = before.slotBytes(SecretState.CURRENT);
    try {
      SignerKeyBundle.PublicIdentity target =
          SignerKeyBundle.verify(
              pending,
              binding,
              request.getGenerationOperationId(),
              request.getTargetGeneration(),
              request.getTargetKid(),
              request.getTargetPublicKeyFingerprint());
      SignerKeyBundle.PublicIdentity previous = verifyPriorCurrent(oldCurrent, request, binding);
      byte[] patch =
          before.promotionPatch(
              pending,
              previous == null ? null : oldCurrent,
              operationId,
              requestDigest,
              priorResourceVersion);
      try {
        kubernetes.patchFixedSecret(before, patch);
      } catch (RuntimeException ambiguousWrite) {
        // The only retry path is exact, operation-bound readback; never issue another PATCH here.
      }
      SecretState after = kubernetes.getFixedSecret();
      requireSecretIdentity(after.uid(), request.getExpectedSecretUid());
      requireChangedResourceVersion(priorResourceVersion, after.resourceVersion());
      requirePromotionMarker(after, request);
      before.requireUnchangedExcept(
          Set.of(SecretState.CURRENT, SecretState.PENDING, SecretState.PREVIOUS),
          Set.of(
              SecretState.PROMOTION_OPERATION,
              SecretState.PROMOTION_REQUEST,
              SecretState.PROMOTION_PRIOR_RV),
          after);
      VerifiedPromotion readback = verifyPromotedState(after, request);
      if (!target.equals(readback.current())
          || !java.util.Objects.equals(previous, readback.previous())) {
        throw denied();
      }
      return recordPromotion(request, after, readback.current(), readback.previous());
    } finally {
      wipe(pending);
      wipe(oldCurrent);
    }
  }

  private Outcome recordPromotion(
      GetCurrentPromotionRequestResponse request,
      SecretState after,
      SignerKeyBundle.PublicIdentity current,
      SignerKeyBundle.PublicIdentity previous) {
    var receipt =
        account.recordPrivatePromotionResult(
            request,
            after.uid(),
            request.getExpectedSecretResourceVersion(),
            after.resourceVersion(),
            current,
            previous,
            previous == null ? List.of("current") : List.of("current", "previous"));
    if (receipt.getUnknownFields().asMap().isEmpty() == false
        || receipt.getSchemaVersion() != 1
        || !request.getPromotionOperationId().equals(receipt.getPromotionOperationId())
        || !request.getGenerationOperationId().equals(receipt.getGenerationOperationId())
        || !after.resourceVersion().equals(receipt.getObservedResourceVersion())
        || !SHA256.matcher(receipt.getPublicReceiptDigest()).matches()) {
      throw denied();
    }
    return Outcome.PRIVATE_PROMOTION_RECEIPT_RECORDED;
  }

  private VerifiedPromotion verifyPromotedState(
      SecretState secret, GetCurrentPromotionRequestResponse request) {
    if (!secret.operationMarkerMatches(
            SecretState.PROMOTION_OPERATION,
            SecretState.PROMOTION_REQUEST,
            SecretState.PROMOTION_PRIOR_RV,
            request.getPromotionOperationId(),
            request.getPromotionRequestDigest(),
            request.getExpectedSecretResourceVersion())
        || secret.containsSlot(SecretState.PENDING)) {
      throw denied();
    }
    byte[] currentBytes = secret.slotBytes(SecretState.CURRENT);
    byte[] previousBytes = secret.slotBytes(SecretState.PREVIOUS);
    try {
      SignerKeyBundle.PublicIdentity current =
          SignerKeyBundle.verify(
              currentBytes,
              snapshot.binding(),
              request.getGenerationOperationId(),
              request.getTargetGeneration(),
              request.getTargetKid(),
              request.getTargetPublicKeyFingerprint());
      SignerKeyBundle.PublicIdentity previous = verifyPreviousBundle(previousBytes, request);
      Set<String> expectedSlots =
          previous == null
              ? Set.of(SecretState.CURRENT)
              : Set.of(SecretState.CURRENT, SecretState.PREVIOUS);
      if (!secret.signerSlots().equals(expectedSlots)) {
        throw denied();
      }
      return new VerifiedPromotion(current, previous);
    } finally {
      wipe(currentBytes);
      wipe(previousBytes);
    }
  }

  private SignerKeyBundle.PublicIdentity verifyPreviousBundle(
      byte[] previousBytes, GetCurrentPromotionRequestResponse request) {
    if (request.getExpectedPreviousGeneration().isEmpty()
        && request.getExpectedPreviousKid().isEmpty()
        && request.getExpectedPreviousPublicKeyFingerprint().isEmpty()) {
      if (previousBytes != null) throw denied();
      return null;
    }
    return SignerKeyBundle.verify(
        previousBytes,
        snapshot.binding(),
        null,
        request.getExpectedPreviousGeneration(),
        request.getExpectedPreviousKid(),
        request.getExpectedPreviousPublicKeyFingerprint());
  }

  private SignerKeyBundle.PublicIdentity verifyPriorCurrent(
      byte[] currentBytes,
      GetCurrentPromotionRequestResponse request,
      ProtectedBinding.Binding binding) {
    boolean expectedPresent =
        !request.getExpectedPreviousGeneration().isEmpty()
            || !request.getExpectedPreviousKid().isEmpty()
            || !request.getExpectedPreviousPublicKeyFingerprint().isEmpty();
    if (!expectedPresent) {
      if (currentBytes != null) throw denied();
      return null;
    }
    return SignerKeyBundle.verify(
        currentBytes,
        binding,
        null,
        request.getExpectedPreviousGeneration(),
        request.getExpectedPreviousKid(),
        request.getExpectedPreviousPublicKeyFingerprint());
  }

  private GetCurrentPromotionRequestResponse currentPromotionOrNull() {
    try {
      GetCurrentPromotionRequestResponse request = account.getCurrentPromotionRequest();
      validatePromotionRequest(request);
      return request;
    } catch (StatusRuntimeException ex) {
      if (Status.fromThrowable(ex).getCode() == Status.Code.NOT_FOUND) {
        return null;
      }
      throw denied();
    }
  }

  private void validateGenerationRequest(
      GetCurrentGenerationRequestResponse request, boolean requiresObservedSecret) {
    ProtectedBinding.Binding binding = snapshot.binding();
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || !validUuidV4(request.getOperationId())
        || !sha256(request.getOperationDigest())
        || !binding.environmentId().equals(request.getEnvironmentId())
        || !binding.clusterId().equals(request.getClusterId())
        || !binding.namespace().equals(request.getNamespace())
        || !binding
            .expectedClusterIncarnationUid()
            .equals(request.getExpectedClusterIncarnationUid())
        || !binding.expectedNamespaceUid().equals(request.getExpectedNamespaceUid())
        || !binding.accountTrustBindingDigest().equals(request.getTrustBindingDigest())
        || !binding.configRevision().equals(request.getTrustConfigRevision())
        || !SecretState.NAME.equals(request.getPrivateSecretName())
        || !"RS256".equals(request.getTargetAlgorithm())
        || !"MATERIALIZE_PENDING".equals(request.getOperationAction())
        || !request.getAllowedPrivateSlotsList().equals(List.of("pending"))
        || !GENERATION.matcher(request.getTargetGeneration()).matches()
        || !KID.matcher(request.getTargetKid()).matches()
        || !positiveDecimal(request.getDesiredStateVersion())) {
      throw denied();
    }
    if (request.hasExpectedActive()) {
      validateActive(
          request.getExpectedActive().getGeneration(), request.getExpectedActive().getKid());
      if (!request.getExpectedActive().getUnknownFields().asMap().isEmpty()) throw denied();
    }
    if (request.hasExpectedPublishedActive()) {
      validateActive(
          request.getExpectedPublishedActive().getGeneration(),
          request.getExpectedPublishedActive().getKid());
      if (!request.getExpectedPublishedActive().getUnknownFields().asMap().isEmpty())
        throw denied();
    }
    switch (request.getPhase()) {
      case OBSERVE_PRIVATE_SECRET -> {
        if (requiresObservedSecret
            || !request.getGenerationRequestDigest().isEmpty()
            || !request.getSecretUid().isEmpty()
            || !request.getExpectedSecretResourceVersion().isEmpty()
            || !request.getGenerationReceiptDigest().isEmpty()
            || !request.getPublicKeyFingerprint().isEmpty()
            || !request.getPublicJwkJson().isEmpty()
            || !request.getObservedSecretResourceVersion().isEmpty()) {
          throw denied();
        }
      }
      case GENERATE_PENDING -> {
        if (!sha256(request.getGenerationRequestDigest())
            || !uuid(request.getSecretUid())
            || !resourceVersion(request.getExpectedSecretResourceVersion())
            || !request.getGenerationReceiptDigest().isEmpty()
            || !request.getPublicKeyFingerprint().isEmpty()
            || !request.getPublicJwkJson().isEmpty()
            || !request.getObservedSecretResourceVersion().isEmpty()) {
          throw denied();
        }
      }
      case GENERATION_RECORDED -> {
        if (!sha256(request.getGenerationRequestDigest())
            || !uuid(request.getSecretUid())
            || !resourceVersion(request.getExpectedSecretResourceVersion())
            || !sha256(request.getGenerationReceiptDigest())
            || !sha256(request.getPublicKeyFingerprint())
            || !resourceVersion(request.getObservedSecretResourceVersion())
            || request.getPublicJwkJson().isBlank()) {
          throw denied();
        }
      }
      default -> throw denied();
    }
  }

  private void validatePromotionRequest(GetCurrentPromotionRequestResponse request) {
    ProtectedBinding.Binding binding = snapshot.binding();
    boolean expectedPrevious =
        !request.getExpectedPreviousGeneration().isEmpty()
            || !request.getExpectedPreviousKid().isEmpty()
            || !request.getExpectedPreviousPublicKeyFingerprint().isEmpty();
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || !validUuidV4(request.getPromotionOperationId())
        || !sha256(request.getPromotionRequestDigest())
        || !validUuidV4(request.getGenerationOperationId())
        || !sha256(request.getGenerationOperationDigest())
        || !sha256(request.getGenerationReceiptDigest())
        || !binding.environmentId().equals(request.getEnvironmentId())
        || !binding.clusterId().equals(request.getClusterId())
        || !binding.namespace().equals(request.getNamespace())
        || !binding
            .expectedClusterIncarnationUid()
            .equals(request.getExpectedClusterIncarnationUid())
        || !binding.expectedNamespaceUid().equals(request.getExpectedNamespaceUid())
        || !binding.accountTrustBindingDigest().equals(request.getTrustBindingDigest())
        || !binding.configRevision().equals(request.getTrustConfigRevision())
        || !SecretState.NAME.equals(request.getPrivateSecretName())
        || !sha256(request.getTargetPublicKeyFingerprint())
        || !GENERATION.matcher(request.getTargetGeneration()).matches()
        || !KID.matcher(request.getTargetKid()).matches()
        || !"RS256".equals(request.getTargetAlgorithm())
        || !uuid(request.getExpectedSecretUid())
        || !resourceVersion(request.getExpectedSecretResourceVersion())
        || !"PROMOTE_PENDING".equals(request.getOperationAction())
        || !request
            .getAllowedPrivateSlotsList()
            .equals(List.of(SecretState.CURRENT, SecretState.PENDING, SecretState.PREVIOUS))) {
      throw denied();
    }
    if (expectedPrevious) {
      if (!GENERATION.matcher(request.getExpectedPreviousGeneration()).matches()
          || !KID.matcher(request.getExpectedPreviousKid()).matches()
          || !sha256(request.getExpectedPreviousPublicKeyFingerprint())) {
        throw denied();
      }
    } else if (!request.getExpectedPreviousGeneration().isEmpty()
        || !request.getExpectedPreviousKid().isEmpty()
        || !request.getExpectedPreviousPublicKeyFingerprint().isEmpty()) {
      throw denied();
    }
  }

  private void validateObservedActiveSigner(
      GetCurrentGenerationRequestResponse request, SecretState secret) {
    byte[] current = secret.slotBytes(SecretState.CURRENT);
    try {
      if (!request.hasExpectedActive()) {
        if (current != null) throw denied();
        return;
      }
      SignerKeyBundle.verify(
          current,
          snapshot.binding(),
          null,
          request.getExpectedActive().getGeneration(),
          request.getExpectedActive().getKid(),
          null);
    } finally {
      wipe(current);
    }
  }

  private void validateGenerationReceipt(
      GetCurrentGenerationRequestResponse request,
      SecretState secret,
      SignerKeyBundle.PublicIdentity identity,
      String publicJwk,
      net.firedevops.firemud.account.v1.RecordGenerationResultResponse receipt) {
    ProtectedBinding.Binding binding = snapshot.binding();
    if (!receipt.getUnknownFields().asMap().isEmpty()
        || receipt.getSchemaVersion() != 1
        || !request.getOperationId().equals(receipt.getOperationId())
        || !request.getOperationDigest().equals(receipt.getOperationDigest())
        || !request.getGenerationRequestDigest().equals(receipt.getGenerationRequestDigest())
        || !sha256(receipt.getGenerationReceiptDigest())
        || !positiveDecimal(receipt.getDesiredStateVersion())
        || !binding.environmentId().equals(receipt.getEnvironmentId())
        || !binding.clusterId().equals(receipt.getClusterId())
        || !binding.namespace().equals(receipt.getNamespace())
        || !binding
            .expectedClusterIncarnationUid()
            .equals(receipt.getExpectedClusterIncarnationUid())
        || !binding.expectedNamespaceUid().equals(receipt.getExpectedNamespaceUid())
        || !binding.accountTrustBindingDigest().equals(receipt.getTrustBindingDigest())
        || !binding.configRevision().equals(receipt.getTrustConfigRevision())
        || !SecretState.NAME.equals(receipt.getPrivateSecretName())
        || !secret.uid().equals(receipt.getSecretUid())
        || !request
            .getExpectedSecretResourceVersion()
            .equals(receipt.getExpectedPriorResourceVersion())
        || !secret.resourceVersion().equals(receipt.getObservedResourceVersion())
        || !request.getTargetGeneration().equals(receipt.getTargetGeneration())
        || !request.getTargetKid().equals(receipt.getTargetKid())
        || !"RS256".equals(receipt.getTargetAlgorithm())
        || !identity.fingerprint().equals(receipt.getPublicKeyFingerprint())
        || !publicJwk.equals(receipt.getPublicJwkJson())) {
      throw denied();
    }
  }

  private void requireGenerationReceipt(
      GetCurrentGenerationRequestResponse request,
      SecretState secret,
      SignerKeyBundle.PublicIdentity identity,
      String publicJwk,
      net.firedevops.firemud.account.v1.RecordGenerationResultResponse receipt) {
    validateGenerationReceipt(request, secret, identity, publicJwk, receipt);
  }

  private void requireGenerationMarker(
      SecretState secret, GetCurrentGenerationRequestResponse request) {
    if (!secret.operationMarkerMatches(
        SecretState.GENERATION_OPERATION,
        SecretState.GENERATION_REQUEST,
        SecretState.GENERATION_PRIOR_RV,
        request.getOperationId(),
        request.getGenerationRequestDigest(),
        request.getExpectedSecretResourceVersion())) {
      throw denied();
    }
  }

  private void requirePromotionMarker(
      SecretState secret, GetCurrentPromotionRequestResponse request) {
    if (!secret.operationMarkerMatches(
        SecretState.PROMOTION_OPERATION,
        SecretState.PROMOTION_REQUEST,
        SecretState.PROMOTION_PRIOR_RV,
        request.getPromotionOperationId(),
        request.getPromotionRequestDigest(),
        request.getExpectedSecretResourceVersion())) {
      throw denied();
    }
  }

  private static void validateActive(String generation, String kid) {
    if (!GENERATION.matcher(generation).matches() || !KID.matcher(kid).matches()) {
      throw denied();
    }
  }

  private static boolean validUuidV4(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.version() == 4 && parsed.variant() == 2 && parsed.toString().equals(value);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static boolean uuid(String value) {
    try {
      return java.util.UUID.fromString(value).toString().equals(value);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static boolean sha256(String value) {
    return SHA256.matcher(value).matches();
  }

  private static boolean resourceVersion(String value) {
    return RESOURCE_VERSION.matcher(value).matches();
  }

  private static boolean positiveDecimal(String value) {
    return value.matches("[1-9][0-9]{0,18}");
  }

  private static void requireSecretIdentity(String observedUid, String expectedUid) {
    if (!observedUid.equals(expectedUid)) throw denied();
  }

  private static void requireChangedResourceVersion(String before, String after) {
    if (!resourceVersion(after) || before.equals(after)) throw denied();
  }

  private static void requireResourceVersion(String value) {
    if (!resourceVersion(value)) throw denied();
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
  }

  private static IllegalStateException denied() {
    return new IllegalStateException(
        "Account-authorized JWT materialization could not be reconciled");
  }

  enum Outcome {
    SECRET_OBSERVATION_RECORDED("secret_observation_recorded"),
    GENERATION_RECORDED("generation_recorded"),
    GENERATION_ALREADY_RECORDED("generation_already_recorded"),
    PRIVATE_PROMOTION_RECEIPT_RECORDED("private_promotion_receipt_recorded");

    private final String publicName;

    Outcome(String publicName) {
      this.publicName = publicName;
    }

    String publicName() {
      return publicName;
    }
  }

  private record VerifiedPromotion(
      SignerKeyBundle.PublicIdentity current, SignerKeyBundle.PublicIdentity previous) {}

  interface AccountOperations {
    EnrollBootstrapResponse enrollBootstrap();

    GetCurrentGenerationRequestResponse getCurrentGenerationRequest();

    GetCurrentGenerationRequestResponse recordSecretObservation(
        String operationId,
        String operationDigest,
        String secretUid,
        String observedResourceVersion);

    RecordGenerationResultResponse recordGenerationResult(
        String operationId,
        String generationRequestDigest,
        String secretUid,
        String expectedPriorResourceVersion,
        String observedResourceVersion,
        String publicJwkJson);

    GetCurrentPromotionRequestResponse getCurrentPromotionRequest();

    RecordPrivatePromotionResultResponse recordPrivatePromotionResult(
        GetCurrentPromotionRequestResponse request,
        String secretUid,
        String expectedPriorResourceVersion,
        String observedResourceVersion,
        SignerKeyBundle.PublicIdentity current,
        SignerKeyBundle.PublicIdentity previous,
        List<String> resultingSlots);
  }

  interface KubernetesOperations {
    void verifyClusterIdentity();

    SecretState getFixedSecret();

    void patchFixedSecret(SecretState before, byte[] mergePatch);
  }
}
