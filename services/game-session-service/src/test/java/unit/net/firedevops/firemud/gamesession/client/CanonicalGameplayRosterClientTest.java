package unit.net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

class CanonicalGameplayRosterClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_UUID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_UUID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID SESSION_UUID = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID LIST_REQUEST_UUID = uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID SELECT_REQUEST_UUID = uuid("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID ACTOR_UUID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID SNAPSHOT_UUID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ASSIGNMENT_UUID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  // Golden v3 vector from Entity's CanonicalGameplayRosterSnapshotDigest.compute contract.
  private static final String ENTITY_OWNED_ROSTER_DIGEST_VECTOR =
      "a567381a308464fb6f6b5d18fcd7caa1eb29faf5c37903b130f98ef351a0060f";

  @Test
  void sendsExactPublishedTargetWithAccountAndValidatesSourceSnapshot() throws Exception {
    var route = publishedRoute();
    var stub = mockStub();
    when(stub.listPreseededRoster(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              var actor = actor(ACTOR_UUID, "Pilot One");
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest(
                      snapshotDigest(
                          ACCOUNT_UUID,
                          request.getExpectedTarget(),
                          List.of(ACTOR_UUID.toString(), "Pilot One")))
                  .addActors(actor)
                  .build();
            });
    var client = newClient(stub);

    var roster = client.listPreseededRoster(route, listContext());

    assertThat(roster.canonicalAccountUuid()).isEqualTo(ACCOUNT_UUID);
    assertThat(roster.snapshotUuid()).isEqualTo(SNAPSHOT_UUID);
    assertThat(roster.target().getTenantUuid()).isEqualTo(TENANT_UUID.toString());
    assertThat(roster.target().getPublishedPolicyDigest())
        .isEqualTo(route.selectedPolicyEvidence().policyDigest().substring("sha256:".length()));
    assertThat(roster.target().getPublishedPolicyDigest()).matches("[0-9a-f]{64}");
    assertThat(roster.target().getPublishedReleaseBundleRef())
        .isEqualTo(route.policySetEvidence().publishedReleaseBundleRef());
    assertThat(roster.target().getPublishedOwnerProofDigest()).isEqualTo("f".repeat(64));
    assertThat(roster.actors())
        .containsExactly(new CanonicalGameplayRosterClient.RosterActor(ACTOR_UUID, "Pilot One"));

    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    var requestCaptor = org.mockito.ArgumentCaptor.forClass(CanonicalGameplayRosterRequest.class);
    verify(stub).listPreseededRoster(requestCaptor.capture());
    var request = requestCaptor.getValue();
    assertThat(UUID.fromString(request.getRequestUuid()).toString())
        .isEqualTo(request.getRequestUuid());
    assertThat(request.getCanonicalAccountUuid()).isEqualTo(ACCOUNT_UUID.toString());
    assertThat(request.getExpectedTarget()).isEqualTo(roster.target());
    assertThat(request.getRequestUuid()).isEqualTo(listContext().getRequestId());
    assertThat(request.hasPlayerExecutionContext()).isTrue();
    assertThat(request.getPlayerExecutionContext()).isEqualTo(listContext());
    assertThat(request.getExpectedTarget().getPublishedPolicyDigest())
        .isEqualTo(route.selectedPolicyEvidence().policyDigest().substring("sha256:".length()));
  }

  @Test
  void rejectsMissingMalformedAndSubstitutedInitialExecutionContextBeforeRpc() {
    var stub = mockStub();
    var client = newClient(stub);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(101, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertThatThrownBy(() -> client.listPreseededRoster(publishedRoute(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("context is required");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(), listContext().toBuilder().setAccountId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(), listContext().toBuilder().setSessionId("").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("session_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(), listContext().toBuilder().setRequestId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext()
                        .toBuilder()
                        .setPlayableStateNamespaceId(SNAPSHOT_UUID.toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("playable_state_namespace_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder().setTenantId(ACCOUNT_UUID.toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder().setRealmId(ACCOUNT_UUID.toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realm_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder().setGameInstanceId(ACCOUNT_UUID.toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("game_instance_id");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder().setPlayableStateScope("2").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact SHARED or ISOLATED");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder().setPlayableStateScope("ISOLATED").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the expected target");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder().setCharacterId(ACTOR_UUID.toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not select an actor");
    assertThatThrownBy(
            () ->
                client.listPreseededRoster(
                    publishedRoute(), listContext().toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");

    verify(stub, never()).listPreseededRoster(any());
  }

  @Test
  void matchesEntityOwnedRosterDigestV3GoldenVector() throws Exception {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid("11111111-1111-4111-8111-111111111111")
            .setRealmUuid("22222222-2222-4222-8222-222222222222")
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setGameInstanceUuid("44444444-4444-4444-8444-444444444444")
            .setCatalogRevision(1L)
            .setPointerVersion(7L)
            .setActiveWorldEpoch(82L)
            .setCanonicalVersionUuid("33333333-3333-4333-8333-333333333333")
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedReleaseBundleRef("release/fixture-v1")
            .setAdmissionPointerSnapshotDigest("d".repeat(64))
            .setPublishedOwnerProofDigest("f".repeat(64))
            .setPlayableStateNamespaceUuid("55555555-5555-4555-8555-555555555555")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    var calculateDigest =
        CanonicalGameplayRosterClient.class.getDeclaredMethod(
            "calculateSnapshotDigest", UUID.class, CanonicalGameplayRosterTarget.class, List.class);
    calculateDigest.setAccessible(true);

    String digest =
        (String)
            calculateDigest.invoke(
                null,
                ACCOUNT_UUID,
                target,
                List.of(new CanonicalGameplayRosterClient.RosterActor(ACTOR_UUID, "Pilot One")));

    assertThat(digest).isEqualTo(ENTITY_OWNED_ROSTER_DIGEST_VECTOR);
  }

  @Test
  void rejectsChangedAccountAndCompleteTargetEcho() throws Exception {
    var stub = mockStub();
    when(stub.listPreseededRoster(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(TENANT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest("a".repeat(64))
                  .build();
            });

    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical account UUID");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget().toBuilder().setPointerVersion(99L).build())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest("a".repeat(64))
                  .build();
            })
        .when(stub)
        .listPreseededRoster(any());
    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("complete expected target");
  }

  @Test
  void rejectsDuplicateMalformedAndUnsupportedActors() throws Exception {
    var stub = mockStub();
    when(stub.listPreseededRoster(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              var duplicate = actor(ACTOR_UUID, "Pilot One");
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest(
                      snapshotDigest(
                          ACCOUNT_UUID,
                          request.getExpectedTarget(),
                          List.of(
                              ACTOR_UUID.toString(),
                              "Pilot One",
                              ACTOR_UUID.toString(),
                              "Pilot One")))
                  .addActors(duplicate)
                  .addActors(duplicate)
                  .build();
            });

    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate character UUID");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest("a".repeat(64))
                  .addActors(
                      actor(ACTOR_UUID, "Pilot One").toBuilder().setDisplayName(" Pilot ").build())
                  .build();
            })
        .when(stub)
        .listPreseededRoster(any());
    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("display name is malformed");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest("a".repeat(64))
                  .addActors(
                      actor(ACTOR_UUID, "Pilot One").toBuilder()
                          .setActorKind(
                              CanonicalGameplayRosterActorKind
                                  .CANONICAL_ROSTER_ACTOR_KIND_UNSPECIFIED)
                          .build())
                  .build();
            })
        .when(stub)
        .listPreseededRoster(any());
    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported actor kind");
  }

  @Test
  void rejectsBadSnapshotDigestAndPropagatesEntityTransportFailures() throws Exception {
    var stub = mockStub();
    when(stub.listPreseededRoster(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest("a".repeat(64))
                  .addActors(actor(ACTOR_UUID, "Pilot One"))
                  .build();
            });
    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("snapshot_digest does not bind");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
                  .setSnapshotDigest("a".repeat(64))
                  .build();
            })
        .when(stub)
        .listPreseededRoster(any());
    assertThatThrownBy(() -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("snapshot_uuid is not a canonical non-nil UUID");

    var transportFailure =
        Status.UNAVAILABLE.withDescription("owner unavailable").asRuntimeException();
    doThrow(transportFailure).when(stub).listPreseededRoster(any());
    assertThatThrownBy(
            () -> newClient(stub).listPreseededRoster(publishedRoute(), listContext()))
        .isSameAs(transportFailure);
  }

  @Test
  void readsExactSelectedAssignmentAndReturnsBoundNonAdmissionCapability() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);
    when(stub.readSelectedPreseededAssignment(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              return selectedAssignmentResponse(request);
            });

    var verified = client.readSelectedPreseededAssignment(roster, selectedContext());

    assertThat(verified.canonicalAccountUuid()).isEqualTo(ACCOUNT_UUID);
    assertThat(verified.selectedCharacterUuid()).isEqualTo(ACTOR_UUID);
    assertThat(verified.target()).isEqualTo(roster.target());
    assertThat(verified.rosterSnapshotUuid()).isEqualTo(SNAPSHOT_UUID);
    assertThat(verified.rosterSnapshotDigest()).isEqualTo(roster.snapshotDigest());
    assertThat(verified.assignmentOperationId()).isEqualTo(ASSIGNMENT_UUID);
    assertThat(verified.intentDigest()).isEqualTo("c".repeat(64));

    var requestCaptor =
        org.mockito.ArgumentCaptor.forClass(CanonicalGameplayRosterSelectedAssignmentRequest.class);
    verify(stub).readSelectedPreseededAssignment(requestCaptor.capture());
    var request = requestCaptor.getValue();
    UUID requestUuid = UUID.fromString(request.getRequestUuid());
    assertThat(requestUuid.toString()).isEqualTo(request.getRequestUuid());
    assertThat(requestUuid).isEqualTo(SELECT_REQUEST_UUID);
    assertThat(request.getCanonicalAccountUuid()).isEqualTo(ACCOUNT_UUID.toString());
    assertThat(request.getSelectedCharacterUuid()).isEqualTo(ACTOR_UUID.toString());
    assertThat(request.getExpectedTarget()).isEqualTo(roster.target());
    assertThat(request.getExpectedSnapshot())
        .isEqualTo(
            CanonicalGameplayRosterSnapshotReference.newBuilder()
                .setSnapshotUuid(roster.snapshotUuid().toString())
                .setSnapshotDigest(roster.snapshotDigest())
                .build());
    assertThat(request.getPlayerExecutionContext()).isEqualTo(selectedContext());
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsSelectedContextAccountNamespaceAndActorSubstitutionBeforeRpc() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);

    assertThatThrownBy(
            () ->
                client.readSelectedPreseededAssignment(
                    roster, selectedContext().toBuilder().setAccountId(TENANT_UUID.toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account does not match");
    assertThatThrownBy(
            () ->
                client.readSelectedPreseededAssignment(
                    roster,
                    selectedContext().toBuilder()
                        .setPlayableStateNamespaceId(SNAPSHOT_UUID.toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("playable_state_namespace_id");
    assertThatThrownBy(
            () ->
                client.readSelectedPreseededAssignment(
                    roster,
                    selectedContext().toBuilder()
                        .setCharacterId(SNAPSHOT_UUID.toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly once");
    assertThatThrownBy(
            () ->
                client.readSelectedPreseededAssignment(
                    roster, selectedContext().toBuilder().setRequestId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request_id");

    verify(stub, never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsSelectedReadbackSnapshotUuidOrDigestSubstitution() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);
    var responseNumber = new AtomicInteger();
    when(stub.readSelectedPreseededAssignment(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              var response = selectedAssignmentResponse(request).toBuilder();
              return switch (responseNumber.getAndIncrement()) {
                case 0 ->
                    response
                        .setSnapshot(
                            request.getExpectedSnapshot().toBuilder()
                                .setSnapshotUuid(ASSIGNMENT_UUID.toString()))
                        .build();
                default ->
                    response
                        .setSnapshot(
                            request.getExpectedSnapshot().toBuilder()
                                .setSnapshotDigest("a".repeat(64)))
                        .build();
              };
            });

    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact roster snapshot UUID or digest");
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact roster snapshot UUID or digest");
  }

  @Test
  void rejectsChangedAssignmentCorrelationAccountActorAndCompleteTarget() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);
    var responseNumber = new AtomicInteger();
    when(stub.readSelectedPreseededAssignment(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              var response = selectedAssignmentResponse(request).toBuilder();
              return switch (responseNumber.getAndIncrement()) {
                case 0 -> response.setRequestUuid(UUID.randomUUID().toString()).build();
                case 1 -> response.setCanonicalAccountUuid(TENANT_UUID.toString()).build();
                case 2 -> response.setSelectedCharacterUuid(SNAPSHOT_UUID.toString()).build();
                default ->
                    response
                        .setTarget(request.getExpectedTarget().toBuilder().setPointerVersion(99L))
                        .build();
              };
            });

    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request correlation");
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical account UUID");
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selected character UUID");
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("complete expected target");
  }

  @Test
  void rejectsUnknownFieldsAndMissingSelectedAssignmentFields() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(101, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    when(stub.readSelectedPreseededAssignment(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              return selectedAssignmentResponse(request).toBuilder()
                  .setUnknownFields(unknown)
                  .build();
            });
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown fields");

    var transportFailure =
        Status.FAILED_PRECONDITION.withDescription("roster snapshot changed").asRuntimeException();
    doThrow(transportFailure).when(stub).readSelectedPreseededAssignment(any());
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isSameAs(transportFailure);

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              return selectedAssignmentResponse(request).toBuilder().clearTarget().build();
            })
        .when(stub)
        .readSelectedPreseededAssignment(any());
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("complete expected target");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              return selectedAssignmentResponse(request).toBuilder().clearSnapshot().build();
            })
        .when(stub)
        .readSelectedPreseededAssignment(any());
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("response snapshot is absent");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              var unknownSnapshot =
                  CanonicalGameplayRosterSnapshotReference.newBuilder()
                      .setSnapshotUuid(roster.snapshotUuid().toString())
                      .setSnapshotDigest(roster.snapshotDigest())
                      .setUnknownFields(unknown)
                      .build();
              return selectedAssignmentResponse(request).toBuilder()
                  .setSnapshot(unknownSnapshot)
                  .build();
            })
        .when(stub)
        .readSelectedPreseededAssignment(any());
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("response snapshot is absent or malformed");

    doAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              return selectedAssignmentResponse(request).toBuilder()
                  .setTarget(request.getExpectedTarget().toBuilder().setUnknownFields(unknown))
                  .build();
            })
        .when(stub)
        .readSelectedPreseededAssignment(any());
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("complete expected target");
  }

  @Test
  void rejectsNilOrMalformedAssignmentReferenceAndIntentDigest() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);

    for (String assignmentUuid :
        List.of(
            "00000000-0000-0000-0000-000000000000",
            "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB",
            "not-an-assignment-uuid")) {
      doAnswer(
              invocation -> {
                CanonicalGameplayRosterSelectedAssignmentRequest request =
                    invocation.getArgument(0);
                return selectedAssignmentResponse(request).toBuilder()
                    .setAssignmentUuid(assignmentUuid)
                    .build();
              })
          .when(stub)
          .readSelectedPreseededAssignment(any());
      assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("assignment_uuid");
    }

    for (String intentDigest : List.of("", "C".repeat(64), "sha256:" + "c".repeat(64))) {
      doAnswer(
              invocation -> {
                CanonicalGameplayRosterSelectedAssignmentRequest request =
                    invocation.getArgument(0);
                return selectedAssignmentResponse(request).toBuilder()
                    .setIntentDigest(intentDigest)
                    .build();
              })
          .when(stub)
          .readSelectedPreseededAssignment(any());
      assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("intent_digest");
    }
  }

  @Test
  void rejectsAbsentOrDuplicateSelectedActorInRosterInput() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);
    var absentRoster =
        rosterWithActors(
            roster,
            List.of(
                new CanonicalGameplayRosterClient.RosterActor(
                    uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"), "Other Pilot")));
    var duplicateRoster =
        rosterWithActors(
            roster,
            List.of(
                new CanonicalGameplayRosterClient.RosterActor(ACTOR_UUID, "Pilot One"),
                new CanonicalGameplayRosterClient.RosterActor(ACTOR_UUID, "Pilot One")));

    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(absentRoster, selectedContext()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly once");
    assertThatThrownBy(() -> client.readSelectedPreseededAssignment(duplicateRoster, selectedContext()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate actor");
    verify(stub, never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void refusesSelectedAssignmentReadInsideTransactionOrSynchronization() throws Exception {
    var stub = mockStub();
    var client = newClient(stub);
    var roster = validatedRoster(client, stub);

    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("transaction or synchronization");
    } finally {
      TransactionSynchronizationManager.clear();
    }

    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.readSelectedPreseededAssignment(roster, selectedContext()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("transaction or synchronization");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verify(stub, never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsNilAccountBeforeClientUseAndRequiresWorkloadMtls() {
    var factory = mock(GrpcChannelFactory.class);
    var uninitialized = newClientWithoutStub(factory);
    assertThatThrownBy(
            () ->
                uninitialized.listPreseededRoster(
                    publishedRoute(),
                    listContext().toBuilder()
                        .setAccountId(new UUID(0L, 0L).toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-nil");
    verifyNoInteractions(factory);

    var plaintext = mtlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayRosterClient(
                    new ServiceEndpointsProperties(), plaintext, factory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new CanonicalGameplayRosterClient(
                    new ServiceEndpointsProperties(), mtlsProperties(), factory, "invalid.ns"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");
    verifyNoInteractions(factory);
  }

  @Test
  void rejectsResponsesFromOtherEntityWorkloadIdentity() throws Exception {
    ManagedChannel channel = mock(ManagedChannel.class);
    SSLSession sslSession = mock(SSLSession.class);
    X509Certificate peerCertificate = mock(X509Certificate.class);
    when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {peerCertificate});
    when(peerCertificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/other/sa/entity-management-service")));
    Attributes attributes =
        Attributes.newBuilder().set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    @SuppressWarnings("unchecked")
    ClientCall<CanonicalGameplayRosterRequest, CanonicalGameplayRosterResponse> call =
        mock(ClientCall.class);
    when(call.getAttributes()).thenReturn(attributes);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ClientCall.Listener<CanonicalGameplayRosterResponse> listener =
                  invocation.getArgument(0);
              listener.onMessage(CanonicalGameplayRosterResponse.getDefaultInstance());
              listener.onClose(Status.OK, new Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel.<CanonicalGameplayRosterRequest, CanonicalGameplayRosterResponse>newCall(
            any(), any()))
        .thenReturn(call);

    var client = newClientWithChannel(channel);
    assertThatThrownBy(() -> client.listPreseededRoster(publishedRoute(), listContext()))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
  }

  private static PlayerExecutionContext listContext() {
    return playerExecutionContext(LIST_REQUEST_UUID, "");
  }

  private static PlayerExecutionContext selectedContext() {
    return playerExecutionContext(SELECT_REQUEST_UUID, ACTOR_UUID.toString());
  }

  private static PlayerExecutionContext playerExecutionContext(
      UUID requestUuid, String characterId) {
    CanonicalPlayableTarget route = publishedRoute().route();
    return PlayerExecutionContext.newBuilder()
        .setAccountId(ACCOUNT_UUID.toString())
        .setTenantId(route.canonicalTenantId().toString())
        .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
        .setGameInstanceId(route.canonicalGameInstanceId().toString())
        .setCharacterId(characterId)
        .setSessionId(SESSION_UUID.toString())
        .setRealmId(route.realmId().toString())
        .setRequestId(requestUuid.toString())
        .setPlayableStateScope(route.playableStateScope())
        .build();
  }

  private static CanonicalPublishedPlayerRoute publishedRoute() {
    var fixture =
        PublishedRealmPolicyEvidenceFixture.fixture(
            PublishedRealmPolicyEvidenceFixture.policy("main", true, true, "SHARED"));
    var policySet = fixture.set();
    PublishedRealmEntryPolicyEvidence selected = policySet.policies().getFirst();
    var route =
        new CanonicalPlayableTarget(
            NAMESPACE,
            "tenant-key",
            TENANT_UUID,
            "earth",
            "Earth",
            uuid("22222222-2222-4222-8222-222222222222"),
            "main",
            "main",
            101L,
            202L,
            uuid("33333333-3333-4333-8333-333333333333"),
            "SHARED",
            uuid("44444444-4444-4444-8444-444444444444"),
            fixture.target().canonicalVersionId(),
            303L,
            1L,
            7L,
            "d".repeat(64),
            82L,
            "first-open-operation-17",
            "a".repeat(64),
            OriginKind.NO_PRIOR_POINTER,
            null,
            uuid("99999999-9999-4999-8999-999999999999"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "sha256:" + "c".repeat(64),
            27L,
            "sha256:" + "f".repeat(64),
            CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            false,
            Instant.parse("2026-10-07T00:00:00Z"),
            "ALLOW_NEW");
    return new CanonicalPublishedPlayerRoute(route, policySet, selected);
  }

  private static net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor actor(
      UUID characterUuid, String displayName) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
        .setCharacterUuid(characterUuid.toString())
        .setDisplayName(displayName)
        .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
        .build();
  }

  private static CanonicalGameplayRosterClient.PreseededRosterSnapshot validatedRoster(
      CanonicalGameplayRosterClient client,
      CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub stub) {
    when(stub.listPreseededRoster(any()))
        .thenAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              return CanonicalGameplayRosterResponse.newBuilder()
                  .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
                  .setTarget(request.getExpectedTarget())
                  .setSnapshotUuid(SNAPSHOT_UUID.toString())
                  .setSnapshotDigest(
                      snapshotDigest(
                          ACCOUNT_UUID,
                          request.getExpectedTarget(),
                          List.of(ACTOR_UUID.toString(), "Pilot One")))
                  .addActors(actor(ACTOR_UUID, "Pilot One"))
                  .build();
            });
    return client.listPreseededRoster(publishedRoute(), listContext());
  }

  private static CanonicalGameplayRosterSelectedAssignmentResponse selectedAssignmentResponse(
      CanonicalGameplayRosterSelectedAssignmentRequest request) {
    return CanonicalGameplayRosterSelectedAssignmentResponse.newBuilder()
        .setRequestUuid(request.getRequestUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setSelectedCharacterUuid(request.getSelectedCharacterUuid())
        .setTarget(request.getExpectedTarget())
        .setSnapshot(request.getExpectedSnapshot())
        .setAssignmentUuid(ASSIGNMENT_UUID.toString())
        .setIntentDigest("c".repeat(64))
        .build();
  }

  private static CanonicalGameplayRosterClient.PreseededRosterSnapshot rosterWithActors(
      CanonicalGameplayRosterClient.PreseededRosterSnapshot source,
      List<CanonicalGameplayRosterClient.RosterActor> actors)
      throws Exception {
    List<String> digestEntries = new ArrayList<>(actors.size() * 2);
    for (var actor : actors) {
      digestEntries.add(actor.characterUuid().toString());
      digestEntries.add(actor.displayName());
    }
    return new CanonicalGameplayRosterClient.PreseededRosterSnapshot(
        source.canonicalAccountUuid(),
        source.snapshotUuid(),
        snapshotDigest(source.canonicalAccountUuid(), source.target(), digestEntries),
        source.target(),
        actors);
  }

  private static String snapshotDigest(
      UUID accountUuid,
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget target,
      List<String> actors)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      for (String value :
          List.of(
              "firemud.entity.canonical-gameplay-roster.v3",
              accountUuid.toString(),
              target.getTenantUuid(),
              target.getRealmUuid(),
              target.getWorldSlug(),
              target.getRealmSlug(),
              target.getGameInstanceUuid(),
              Long.toString(target.getCatalogRevision()),
              Long.toString(target.getPointerVersion()),
              Long.toString(target.getActiveWorldEpoch()),
              target.getCanonicalVersionUuid(),
              target.getPublishedPolicyDigest(),
              target.getPublishedReleaseBundleRef(),
              target.getAdmissionPointerSnapshotDigest(),
              target.getPublishedOwnerProofDigest(),
              target.getPlayableStateNamespaceUuid(),
              target.getPlayableStateScope().name(),
              "PRESEEDED_ONLY",
              Integer.toString(actors.size() / 2))) {
        write(output, value);
      }
      for (String actorValue : actors) {
        write(output, actorValue);
      }
    }
    return java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
  }

  private static void write(DataOutputStream output, String value) throws Exception {
    byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static CanonicalGameplayRosterClient newClient(
      CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub stub)
      throws Exception {
    var client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static CanonicalGameplayRosterClient newClientWithChannel(ManagedChannel channel)
      throws Exception {
    var client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    var buildStub =
        CanonicalGameplayRosterClient.class.getDeclaredMethod("buildStub", ManagedChannel.class);
    buildStub.setAccessible(true);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, buildStub.invoke(client, channel));
    return client;
  }

  private static CanonicalGameplayRosterClient newClientWithoutStub(GrpcChannelFactory factory) {
    return new CanonicalGameplayRosterClient(
        new ServiceEndpointsProperties(), mtlsProperties(), factory, NAMESPACE);
  }

  private static CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub
      mockStub() {
    var stub =
        mock(CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/entity-management-ca.crt");
    return tls;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
