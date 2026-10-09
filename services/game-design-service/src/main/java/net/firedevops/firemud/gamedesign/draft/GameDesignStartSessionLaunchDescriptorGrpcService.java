package net.firedevops.firemud.gamedesign.draft;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.publication.StartSessionLaunchDescriptorProducer;
import net.firedevops.firemud.gamedesign.v1.GameDesignStartSessionLaunchDescriptorServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;

/** Standalone authenticated StartSession descriptor adapter; deliberately not registered. */
public final class GameDesignStartSessionLaunchDescriptorGrpcService
    extends GameDesignStartSessionLaunchDescriptorServiceGrpc
        .GameDesignStartSessionLaunchDescriptorServiceImplBase {
  private final StartSessionLaunchDescriptorProducer producer;
  private final String workloadNamespace;

  public GameDesignStartSessionLaunchDescriptorGrpcService(
      StartSessionLaunchDescriptorProducer producer, String workloadNamespace) {
    this.producer = Objects.requireNonNull(producer, "producer");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveStartSessionLaunchDescriptor(
      ReadStartSessionTemplateAssociationRequest request,
      StreamObserver<ResolveStartSessionLaunchDescriptorResponse> observer) {
    try {
      requirePeer();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    final Request decoded;
    try {
      decoded = StartSessionLaunchDescriptorGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Exact StartSession template association replay required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("StartSession target namespace differs from the Game Design peer")
              .asRuntimeException());
      return;
    }

    try {
      StartSessionLaunchDescriptorProducer.ResolvedBinding binding = producer.resolve(decoded);
      if (!decoded.equals(binding.request())) {
        throw new IllegalArgumentException("Producer changed the exact StartSession request");
      }
      ResolvedLaunchDescriptorDto descriptor = binding.descriptor();
      AuthoredWorldLaunchDescriptorEvidence evidence = descriptor.authoredWorldBinding();
      requireDtoMatchesEvidence(descriptor, evidence);
      observer.onNext(
          StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
              decoded, binding.associationRead(), evidence));
      observer.onCompleted();
    } catch (StartSessionLaunchDescriptorProducer.StoredBusinessDenial denied) {
      try {
        observer.onNext(
            StartSessionLaunchDescriptorGrpcCodec.toFailureResponse(
                decoded, denied.associationRead(), denied.failureCode(), denied.getMessage()));
        observer.onCompleted();
      } catch (RuntimeException invalidDenial) {
        observer.onError(
            Status.INTERNAL
                .withDescription("Stored StartSession denial did not match its exact request")
                .asRuntimeException());
      }
    } catch (StatusRuntimeException failure) {
      Status status = Status.fromThrowable(failure);
      observer.onError(
          Status.fromCode(status.getCode())
              .withDescription("StartSession launch descriptor owner read denied or unavailable")
              .asRuntimeException());
    } catch (IllegalArgumentException invalidResult) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Game Design returned an invalid StartSession launch descriptor")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design StartSession launch descriptor is unavailable")
              .asRuntimeException());
    }
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
        .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }

  private static void requireDtoMatchesEvidence(
      ResolvedLaunchDescriptorDto descriptor, AuthoredWorldLaunchDescriptorEvidence evidence) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(evidence, "authoredWorldBinding");
    if (!Objects.equals(descriptor.launchDescriptorId(), evidence.launchDescriptorId())
        || !Objects.equals(descriptor.canonicalTenantId(), evidence.canonicalTenantId().toString())
        || descriptor.gameTemplateId() != evidence.gameTemplateId()
        || !Objects.equals(descriptor.controlPlaneRequestId(), evidence.controlPlaneRequestId())
        || descriptor.versionId() != evidence.versionId()
        || !Objects.equals(
            descriptor.scriptPatchVersion(),
            evidence.scriptPatchVersionPresent() ? evidence.scriptPatchVersion() : null)
        || !Objects.equals(descriptor.runtimeFlagsJson(), evidence.runtimeFlagsJson())
        || !Objects.equals(
            descriptor.generationConfigRevision(), evidence.generationConfigRevision())
        || descriptor.versionStateEpoch() != evidence.versionStateEpoch()
        || descriptor.releaseBundleId() != evidence.releaseBundleId()
        || !Objects.equals(
            descriptor.publishedReleaseBundleRef(), evidence.publishedReleaseBundleRef())
        || !Objects.equals(
            descriptor.remapSetId(), evidence.remapSetIdPresent() ? evidence.remapSetId() : null)) {
      throw new IllegalArgumentException("Resolved descriptor DTO differs from closed evidence");
    }
  }
}
