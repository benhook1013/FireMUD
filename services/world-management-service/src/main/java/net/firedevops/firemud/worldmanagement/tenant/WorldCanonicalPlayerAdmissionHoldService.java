package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/**
 * Unregistered internal boundary. Authenticating the exact Account workload establishes only the
 * producer of the original lease; World never interprets the carrier as current Account authority.
 */
public final class WorldCanonicalPlayerAdmissionHoldService {
  private final WorldCanonicalPlayerAdmissionHoldRepository repository;
  private final String namespace;

  public WorldCanonicalPlayerAdmissionHoldService(
      WorldCanonicalPlayerAdmissionHoldRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.namespace = requireNamespace(namespace);
  }

  public WorldCanonicalPlayerAdmissionHold acquire(
      String originalLeaseJson,
      String originalLeaseSha256,
      long expectedEpoch,
      long expectedVersion) {
    return repository.acquire(
        request(originalLeaseJson, originalLeaseSha256, expectedEpoch, expectedVersion));
  }

  public Optional<WorldCanonicalPlayerAdmissionHold> read(
      String originalLeaseJson,
      String originalLeaseSha256,
      long expectedEpoch,
      long expectedVersion) {
    return repository.read(
        request(originalLeaseJson, originalLeaseSha256, expectedEpoch, expectedVersion));
  }

  /** No caller Boolean, COMMITTED label, expiry, or generic opaque proof can release this hold. */
  public void release() {
    requireAccountPeer(namespace);
    WorldCanonicalPlayerAdmissionHoldRepository.requireNoAmbientTransaction();
    throw new IllegalStateException(
        "Hold release is unavailable without authenticated exact Account terminal AND Game Session installation/cleanup evidence");
  }

  private WorldCanonicalPlayerAdmissionHold.Request request(
      String originalLeaseJson,
      String originalLeaseSha256,
      long expectedEpoch,
      long expectedVersion) {
    requireAccountPeer(namespace);
    WorldCanonicalPlayerAdmissionHoldRepository.requireNoAmbientTransaction();
    var lease = AccountGameplayAdmissionLeaseEvidence.parseCanonical(originalLeaseJson);
    if (!lease.sha256().equals(originalLeaseSha256)
        || !namespace.equals(lease.carrier().get("targetNamespace"))) {
      throw new IllegalArgumentException("Original lease digest or namespace differs");
    }
    return new WorldCanonicalPlayerAdmissionHold.Request(lease, expectedEpoch, expectedVersion);
  }

  static String requireNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace is required");
    }
    return namespace;
  }

  static void requireAccountPeer(String namespace) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("account-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "Exact authenticated same-namespace Account workload is required");
    }
  }
}
