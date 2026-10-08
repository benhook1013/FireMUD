package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import org.springframework.dao.DataAccessException;

/** Standalone protected read adapter. Deliberately has no runtime registration. */
public final class RealmPolicyPublicationGrpcService
    extends PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceImplBase {
  private final RealmPolicyPublicationService owner;
  private final String namespace;

  public RealmPolicyPublicationGrpcService(RealmPolicyPublicationService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void resolvePublishedRealmEntryPolicy(
      ResolvePublishedRealmEntryPolicyRequest wire,
      StreamObserver<ResolvePublishedRealmEntryPolicyResponse> observer) {
    if (!requireGameSessionPeer(observer)) return;

    final PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest request;
    try {
      request = PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(wire);
    } catch (RuntimeException malformed) {
      fail(observer, Status.INVALID_ARGUMENT, "Canonical schema-1 realm policy read required");
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED, "Realm policy target namespace differs from peer");
      return;
    }

    final PublishedRealmEntryPolicySetEvidence evidence;
    try {
      Optional<RealmPolicyPublishedEvidence.PublishedSet> stored =
          owner.readPublishedSet(request.canonicalTenantId(), request.canonicalVersionId());
      if (stored.isEmpty()) {
        fail(observer, Status.NOT_FOUND, "Published realm policy set not found");
        return;
      }
      RealmPolicyPublishedEvidence.PublishedSet ownerSet = stored.orElseThrow();
      requireExactTarget(
          request.canonicalTenantId(), request.canonicalVersionId(), ownerSet.target());
      evidence = RealmPolicyPublishedEvidenceMapper.toShared(ownerSet);
      if (!containsSelector(evidence, request.worldSlug(), request.realmSlug())) {
        fail(observer, Status.NOT_FOUND, "Published realm policy selector not found");
        return;
      }
    } catch (DataAccessException | org.jooq.exception.DataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE, "Published realm policy owner storage unavailable");
      return;
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      fail(observer, Status.FAILED_PRECONDITION, "Published realm policy evidence is inconsistent");
      return;
    } catch (RuntimeException unexpected) {
      fail(observer, Status.INTERNAL, "Published realm policy owner read failed");
      return;
    }

    final ResolvePublishedRealmEntryPolicyResponse response;
    try {
      response = PublishedRealmEntryPolicyReadGrpcCodec.toResponse(request, evidence);
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      fail(observer, Status.FAILED_PRECONDITION, "Published realm policy evidence is inconsistent");
      return;
    } catch (RuntimeException unexpected) {
      fail(observer, Status.INTERNAL, "Published realm policy response encoding failed");
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  @Override
  public void listPublishedRealmEntryPolicies(
      ListPublishedRealmEntryPoliciesRequest wire,
      StreamObserver<ListPublishedRealmEntryPoliciesResponse> observer) {
    if (!requireGameSessionPeer(observer)) return;

    final PublishedRealmEntryPolicyReadGrpcCodec.ListRequest request;
    try {
      request = PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(wire);
    } catch (RuntimeException malformed) {
      fail(observer, Status.INVALID_ARGUMENT, "Canonical schema-1 realm policy read required");
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED, "Realm policy target namespace differs from peer");
      return;
    }

    final PublishedRealmEntryPolicySetEvidence evidence;
    try {
      Optional<RealmPolicyPublishedEvidence.PublishedSet> stored =
          owner.readPublishedSet(request.canonicalTenantId(), request.canonicalVersionId());
      if (stored.isEmpty()) {
        fail(observer, Status.NOT_FOUND, "Published realm policy set not found");
        return;
      }
      RealmPolicyPublishedEvidence.PublishedSet ownerSet = stored.orElseThrow();
      requireExactTarget(
          request.canonicalTenantId(), request.canonicalVersionId(), ownerSet.target());
      evidence = RealmPolicyPublishedEvidenceMapper.toShared(ownerSet);
    } catch (DataAccessException | org.jooq.exception.DataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE, "Published realm policy owner storage unavailable");
      return;
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      fail(observer, Status.FAILED_PRECONDITION, "Published realm policy evidence is inconsistent");
      return;
    } catch (RuntimeException unexpected) {
      fail(observer, Status.INTERNAL, "Published realm policy owner read failed");
      return;
    }

    final ListPublishedRealmEntryPoliciesResponse response;
    try {
      response = PublishedRealmEntryPolicyReadGrpcCodec.toResponse(request, evidence);
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      fail(observer, Status.FAILED_PRECONDITION, "Published realm policy evidence is inconsistent");
      return;
    } catch (RuntimeException unexpected) {
      fail(observer, Status.INTERNAL, "Published realm policy response encoding failed");
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private boolean requireGameSessionPeer(StreamObserver<?> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    if (peer == null
        || !peer.uri().equals(expectedPeer)
        || !peer.namespace().equals(namespace)
        || !peer.service().equals("game-session-service")
        || SessionContext.hasAuthenticatedCallerContext()) {
      fail(
          observer,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Game Session workload without end-user context required");
      return false;
    }
    return true;
  }

  private static boolean containsSelector(
      PublishedRealmEntryPolicySetEvidence evidence, String worldSlug, String realmSlug) {
    return evidence.policies().stream()
        .anyMatch(
            policy ->
                policy.policy().worldSlug().equals(worldSlug)
                    && policy.policy().realmSlug().equals(realmSlug));
  }

  private static void requireExactTarget(
      java.util.UUID tenantId,
      java.util.UUID versionId,
      net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof target) {
    if (!target.canonicalTenantId().equals(tenantId)
        || !target.canonicalVersionId().equals(versionId)) {
      throw new IllegalStateException("Published policy owner returned a different target");
    }
  }

  private static void fail(StreamObserver<?> observer, Status status, String message) {
    observer.onError(status.withDescription(message).asRuntimeException());
  }
}
