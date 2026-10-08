package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Composition ordering units with stipulated peer context and mocked owner evidence/producer. These
 * fixture values are not genuine cross-owner mTLS, creator or PostgreSQL proof.
 */
class AccountSelectedDraftPublicationOrderServiceTest {
  private static final String COMPACT_JWT = "original-compact-creator-jwt";
  private final AuthoredDraftPublishSelectionReadClient client =
      mock(AuthoredDraftPublishSelectionReadClient.class);
  private final AccountPublicationAuthorizationService owner =
      mock(AccountPublicationAuthorizationService.class);
  private final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
  private final AccountSelectedDraftPublicationOrderService service =
      new AccountSelectedDraftPublicationOrderService(client, owner, "test");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void deniesMissingWrongServiceAndCrossNamespacePeerBeforeTransportOrOwner() {
    // Null lookup also proves authentication precedes carrier inspection.
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.authorize(null, null, null));
    for (String workload :
        List.of("account-service", "world-management-service", "game-session-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () -> asPeer("test", workload, () -> service.authorize(null, null, null)));
    }
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> asPeer("other", "game-design-service", () -> service.authorize(null, null, null)));
    verifyNoInteractions(client, owner);
  }

  @Test
  void rejectsActualTransactionAndSynchronizationBeforeRemoteRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> service.authorize(COMPACT_JWT, selection(), environment)));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> service.authorize(COMPACT_JWT, selection(), environment)));
    verifyNoInteractions(client, owner);
  }

  @Test
  void exactReadCompletesBeforeOwnerReceivesUnchangedCreatorJwtEnvironmentAndSelection() {
    var lookup = selection();
    var result = mock(AccountPublicationAuthorizationBinding.class);
    when(client.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              var request =
                  invocation.getArgument(
                      0, AuthoredDraftPublishSelectionReadEvidence.Request.class);
              assertThat(request.targetNamespace()).isEqualTo("test");
              assertThat(request.originalSelection()).containsExactly(lookup.canonicalBytes());
              assertThat(request.selectionDigest()).isEqualTo(lookup.digest());
              return fixtureEvidence(request);
            });
    when(owner.authorize(eq(COMPACT_JWT), eq(lookup), same(environment)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return result;
            });
    assertThat(authorized(() -> service.authorize(COMPACT_JWT, lookup, environment)))
        .isSameAs(result);
    var request = ArgumentCaptor.forClass(AuthoredDraftPublishSelectionReadEvidence.Request.class);
    var order = inOrder(client, owner);
    order.verify(client).read(request.capture());
    order.verify(owner).authorize(eq(COMPACT_JWT), eq(lookup), same(environment));
    order.verifyNoMoreInteractions();
    assertThat(request.getValue().binding()).isEqualTo(lookup);
  }

  @Test
  void missingOrSubstitutedReadbackNeverInvokesAccountOwner() {
    when(client.read(any())).thenReturn(null);
    assertThatThrownBy(
            () -> authorized(() -> service.authorize(COMPACT_JWT, selection(), environment)))
        .isInstanceOf(IllegalStateException.class);
    for (String changed : List.of("namespace", "readIdentity", "selection")) {
      doAnswer(
              invocation -> {
                var request =
                    invocation.getArgument(
                        0, AuthoredDraftPublishSelectionReadEvidence.Request.class);
                if ("selection".equals(changed)) {
                  var original = request.binding();
                  var intent = original.intent();
                  var replaced =
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
                  return fixtureEvidence(
                      new AuthoredDraftPublishSelectionReadEvidence.Request(
                          request.schemaVersion(),
                          request.targetNamespace(),
                          request.readRequestId(),
                          replaced.canonicalBytes(),
                          replaced.digest()));
                }
                return fixtureEvidence(
                    new AuthoredDraftPublishSelectionReadEvidence.Request(
                        request.schemaVersion(),
                        "namespace".equals(changed) ? "other" : request.targetNamespace(),
                        "readIdentity".equals(changed)
                            ? UUID.randomUUID()
                            : request.readRequestId(),
                        request.originalSelection(),
                        request.selectionDigest()));
              })
          .when(client)
          .read(any());
      assertThatThrownBy(
              () -> authorized(() -> service.authorize(COMPACT_JWT, selection(), environment)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("differs from the exact publication order request");
    }
    verifyNoInteractions(owner);
  }

  @Test
  void unavailableOrDeniedSelectionReadNeverInvokesOwner() {
    for (Status failure : List.of(Status.UNAVAILABLE, Status.NOT_FOUND, Status.PERMISSION_DENIED)) {
      doThrow(failure.asRuntimeException()).when(client).read(any());
      assertCode(
          failure.getCode(),
          () -> authorized(() -> service.authorize(COMPACT_JWT, selection(), environment)));
    }
    verifyNoInteractions(owner);
  }

  @Test
  void lowerLevelCreatorDenialPropagatesAfterExactOwnerRead() {
    when(client.read(any())).thenAnswer(invocation -> fixtureEvidence(invocation.getArgument(0)));
    when(owner.authorize(any(), any(), any()))
        .thenThrow(Status.PERMISSION_DENIED.asRuntimeException());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> authorized(() -> service.authorize(COMPACT_JWT, selection(), environment)));
    var order = inOrder(client, owner);
    order.verify(client).read(any());
    order.verify(owner).authorize(eq(COMPACT_JWT), eq(selection()), same(environment));
  }

  private static AuthoredDraftPublishSelectionReadEvidence fixtureEvidence(
      AuthoredDraftPublishSelectionReadEvidence.Request request) {
    var evidence = mock(AuthoredDraftPublishSelectionReadEvidence.class);
    when(evidence.request()).thenReturn(request);
    return evidence;
  }

  private static <T> T authorized(Supplier<T> action) {
    return asPeer("test", "game-design-service", action);
  }

  private static <T> T asPeer(String namespace, String workload, Supplier<T> action) {
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
            .orElseThrow();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static AuthoredDraftPublishSelectionBinding selection() {
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
}
