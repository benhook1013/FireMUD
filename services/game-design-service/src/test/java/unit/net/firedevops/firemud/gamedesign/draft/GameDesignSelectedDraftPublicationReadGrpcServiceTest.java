package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationResponse;
import net.firedevops.firemud.gamedesign.v1.SelectedDraftPublicationStatus;
import org.junit.jupiter.api.Test;

/** Receiver unit proof uses stipulated verified peer context and mocked selection storage. */
class GameDesignSelectedDraftPublicationReadGrpcServiceTest {
  private static final String NAMESPACE = "test";

  @Test
  void rejectsMissingWrongServiceAndWrongNamespacePeersBeforeDecodingAndStorage() {
    var repository = mock(AuthoredDraftPublishSelectionRepository.class);
    var service = new GameDesignSelectedDraftPublicationReadGrpcService(repository, NAMESPACE);
    var malformed = ReadSelectedDraftPublicationRequest.getDefaultInstance();
    var absent = new Collector();
    service.readSelectedDraftPublication(malformed, absent);
    assertFailure(absent, Status.Code.UNAUTHENTICATED);
    for (var peer :
        List.of(
            peer("game-design-service", NAMESPACE),
            peer("account-service", "other"),
            peer("world-management-service", "other"))) {
      var denied = invokeAs(service, malformed, peer);
      assertFailure(denied, Status.Code.PERMISSION_DENIED);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMalformedUnknownAndCrossNamespaceRequestsBeforeStorage() {
    var repository = mock(AuthoredDraftPublishSelectionRepository.class);
    var service = new GameDesignSelectedDraftPublicationReadGrpcService(repository, NAMESPACE);
    var request = wireRequest();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var invalid :
        List.of(
            ReadSelectedDraftPublicationRequest.getDefaultInstance(),
            request.toBuilder().setUnknownFields(unknown).build(),
            request.toBuilder().setSelectionDigest("sha256:" + "0".repeat(64)).build())) {
      assertFailure(
          invokeAs(service, invalid, peer("account-service", NAMESPACE)),
          Status.Code.INVALID_ARGUMENT);
    }
    assertFailure(
        invokeAs(
            service,
            request.toBuilder().setTargetNamespace("other").build(),
            peer("account-service", NAMESPACE)),
        Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void readsExactCanonicalIdentityAndReturnsOnlyExactRetainedSelectionForBothCallers() {
    for (String caller : List.of("account-service", "world-management-service")) {
      var repository = mock(AuthoredDraftPublishSelectionRepository.class);
      var binding = binding();
      when(repository.read(any(), any(), any())).thenReturn(Optional.of(snapshot(binding)));
      var service = new GameDesignSelectedDraftPublicationReadGrpcService(repository, NAMESPACE);
      var request = wireRequest();
      var response = invokeAs(service, request, peer(caller, NAMESPACE));
      var intent = binding.intent();
      verify(repository)
          .read(intent.canonicalTenantId(), intent.canonicalVersionId(), intent.publishRequestId());
      assertThat(response.errorCode).isNull();
      assertThat(response.completed).isTrue();
      assertThat(response.value)
          .isEqualTo(
              AuthoredDraftPublishSelectionReadGrpcCodec.toSelectedResponse(
                  AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(request)));
      assertThat(response.value.getStatus())
          .isEqualTo(SelectedDraftPublicationStatus.SELECTED_DRAFT_PUBLICATION_STATUS_SELECTED);
    }
  }

  @Test
  void missingSelectionDoesNotInventSuccessfulOrTerminalEvidence() {
    var repository = mock(AuthoredDraftPublishSelectionRepository.class);
    when(repository.read(any(), any(), any())).thenReturn(Optional.empty());
    var service = new GameDesignSelectedDraftPublicationReadGrpcService(repository, NAMESPACE);
    assertFailure(
        invokeAs(service, wireRequest(), peer("account-service", NAMESPACE)),
        Status.Code.NOT_FOUND);
  }

  @Test
  void changedStoredSelectionFailsWithoutPartialBytes() {
    var repository = mock(AuthoredDraftPublishSelectionRepository.class);
    var original = binding();
    var intent = original.intent();
    var changed =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                intent.canonicalTenantId(),
                intent.canonicalVersionId(),
                intent.publishRequestId(),
                intent.expectedVersionStateEpoch(),
                "changed notes",
                intent.selectedCommitRequestId(),
                intent.selectedCommitId(),
                intent.selectedCommitDigest()),
            original.target(),
            original.selectedCommit(),
            new VisibilityFence(
                original.target(),
                original.fenceRequestId(),
                original.fenceCommitId(),
                original.fenceInputDigest(),
                original.fenceResultVectorJson(),
                OffsetDateTime.parse(original.fenceCreatedAt())));
    when(repository.read(any(), any(), any())).thenReturn(Optional.of(snapshot(changed)));
    var service = new GameDesignSelectedDraftPublicationReadGrpcService(repository, NAMESPACE);
    assertFailure(
        invokeAs(service, wireRequest(), peer("world-management-service", NAMESPACE)),
        Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void corruptOrUnavailableStorageReturnsNoEvidence() {
    for (RuntimeException failure :
        List.of(
            new IllegalStateException("corrupt retained selection"),
            new org.jooq.exception.DataAccessException("storage unavailable"))) {
      var repository = mock(AuthoredDraftPublishSelectionRepository.class);
      when(repository.read(any(), any(), any())).thenThrow(failure);
      var service = new GameDesignSelectedDraftPublicationReadGrpcService(repository, NAMESPACE);
      assertFailure(
          invokeAs(service, wireRequest(), peer("account-service", NAMESPACE)),
          Status.Code.UNAVAILABLE);
    }
  }

  private static AuthoredDraftPublishSelectionRepository.SelectionSnapshot snapshot(
      AuthoredDraftPublishSelectionBinding binding) {
    return new AuthoredDraftPublishSelectionRepository.SelectionSnapshot(
        AuthoredDraftPublishSelection.fromStored(binding.canonicalJson(), binding.digest()),
        OffsetDateTime.parse("2026-10-01T00:00:00Z"));
  }

  private static ReadSelectedDraftPublicationRequest wireRequest() {
    return AuthoredDraftPublishSelectionReadGrpcCodec.toRequest(
        AuthoredDraftPublishSelectionReadEvidence.Request.create(NAMESPACE, binding()));
  }

  private static void assertFailure(Collector collector, Status.Code expected) {
    assertThat(collector.errorCode).isEqualTo(expected);
    assertThat(collector.value).isNull();
    assertThat(collector.completed).isFalse();
  }

  private static Collector invokeAs(
      GameDesignSelectedDraftPublicationReadGrpcService service,
      ReadSelectedDraftPublicationRequest request,
      GrpcPeerIdentity peer) {
    var result = new Collector();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readSelectedDraftPublication(request, result);
      return result;
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static AuthoredDraftPublishSelectionBinding binding() {
    UUID tenant = uuid("22222222-2222-4222-8222-222222222222");
    UUID version = uuid("33333333-3333-4333-8333-333333333333");
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            uuid("44444444-4444-4444-8444-444444444444"),
            uuid("55555555-5555-4555-8555-555555555555"),
            "base-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                tenant,
                version,
                "publication-request",
                "5",
                "test selection",
                commit.requestId(),
                commit.commitId(),
                commit.digest()),
            target,
            commit,
            new VisibilityFence(
                target,
                commit.requestId(),
                commit.commitId(),
                commit.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    return selected;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Collector
      implements StreamObserver<ReadSelectedDraftPublicationResponse> {
    private ReadSelectedDraftPublicationResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ReadSelectedDraftPublicationResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
