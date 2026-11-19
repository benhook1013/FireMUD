package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;

/** Retained pre-Account custody identity. This is never an execution outcome or Account grant. */
record CanonicalGameplayCapacityOrigin(
    TenantCapacityAdmission.Request request,
    CanonicalGameplayOriginalNewCandidate candidate,
    String tenantGameplayTag,
    String selectedOwnerId,
    long selectedOwnerFence) {
  CanonicalGameplayCapacityOrigin {
    Objects.requireNonNull(request).requireStructurallyValid();
    Objects.requireNonNull(candidate);
    if (!request.identity().admissionId().equals(candidate.bindingDecisionId())
        || !request.identity().tenantId().equals(candidate.bindingIdentity().tenantId())
        || selectedOwnerId == null
        || !selectedOwnerId.matches("[A-Za-z0-9._:-]{1,120}")
        || selectedOwnerFence <= 0
        || tenantGameplayTag == null
        || !tenantGameplayTag.matches("g[1-9][0-9]*-[0-9a-f]+")
        || tenantGameplayTag.length() > 44) {
      throw new IllegalArgumentException("Exact original capacity custody required");
    }
    if (!(request.context() instanceof TenantCapacityAdmission.PlayerContext player)
        || !player.gameInstanceId().equals(candidate.bindingIdentity().gameInstanceId())
        || !player.sessionId().toString().equals(candidate.bindingIdentity().sessionId().toString())
        || !player.accountId().equals(candidate.bindingIdentity().accountId())
        || !player.characterId().equals(candidate.bindingIdentity().characterId())
        || !player
            .playableStateNamespaceId()
            .equals(candidate.bindingIdentity().playableStateNamespaceId())) {
      throw new IllegalArgumentException(
          "Capacity origin must retain the exact original candidate");
    }
  }

  UUID transitionId() {
    return candidate.bindingDecisionId();
  }

  String candidateDigest() {
    return "sha256:" + OriginalNewCapacityExecution.sha256(candidate.canonicalBytes());
  }

  byte[] canonicalBytes() {
    var bytes = new ByteArrayOutputStream();
    for (byte[] field :
        new byte[][] {
          "gs-original-new-capacity-origin/v1".getBytes(StandardCharsets.US_ASCII),
          CapacityAdmissionDigest.preimage(request),
          candidate.canonicalBytes(),
          tenantGameplayTag.getBytes(StandardCharsets.US_ASCII),
          selectedOwnerId.getBytes(StandardCharsets.US_ASCII),
          Long.toString(selectedOwnerFence).getBytes(StandardCharsets.US_ASCII)
        }) {
      bytes.writeBytes(Integer.toString(field.length).getBytes(StandardCharsets.US_ASCII));
      bytes.write(':');
      bytes.writeBytes(field);
    }
    return bytes.toByteArray();
  }

  String digest() {
    return "sha256:" + OriginalNewCapacityExecution.sha256(canonicalBytes());
  }

  String redisKey() {
    return "session:game:capacity-origin:{"
        + tenantGameplayTag
        + "}:"
        + request.admissionAttemptId();
  }

  void requireSame(CanonicalGameplayCapacityOrigin other) {
    if (other == null || !Arrays.equals(canonicalBytes(), other.canonicalBytes())) {
      throw new IllegalArgumentException("Capacity origin identity changed");
    }
  }

  enum State {
    OPEN,
    DISPATCH_CLAIMED,
    CLOSURE_CLAIMED,
    DENIED
  }

  /** No transition here creates an execution result. Terminal proof remains separately owned. */
  static State claim(State current, State target) {
    Objects.requireNonNull(current);
    Objects.requireNonNull(target);
    if (target == State.OPEN || (current != State.OPEN && current != target)) {
      throw new IllegalStateException("Capacity origin custody is exclusive and irreversible");
    }
    return target;
  }
}
