package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled response validation only; no mTLS, persistence or production authority claim. */
class CanonicalPreseededActorRuntimeReferenceClientTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String CHARACTER_UUID = "22222222-2222-4222-8222-222222222222";
  private static final String INTENT_DIGEST = "a".repeat(64);
  @TempDir Path directory;

  @Test
  void privateReferenceConstructionAndActualRpcHaveFiniteDeadlineAndBounds() throws Exception {
    for (var constructor :
        CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference.class
            .getDeclaredConstructors()) {
      assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }
    var stub = stub();
    when(stub.readPreseededActorRuntimeReference(request()))
        .thenReturn(assignedResponse(request()));
    try (var client = client(stub)) {
      var ref = client.read(request());
      assertThat(ref.runtimeEntityId().value()).isEqualTo(7301);
      assertThat(ref.characterUuid().toString()).isEqualTo(CHARACTER_UUID);
      assertThat(ref.workloadNamespace()).isEqualTo(NAMESPACE);
      assertThat(ref.playableStateNamespaceId().toString())
          .isEqualTo(request().getExpectedTarget().getPlayableStateNamespaceUuid());
      verify(stub).withDeadlineAfter(20, TimeUnit.SECONDS);
      verify(stub).withMaxInboundMessageSize(8192);
      verify(stub).withMaxOutboundMessageSize(8192);
      verify(stub).readPreseededActorRuntimeReference(request());
      verify(stub, never()).readPreseededActorAssignmentReceipt(any());
    }
  }

  @Test
  void rejectsChangedTupleDigestGenerationOpenCoreAndUnboundedResponses() throws Exception {
    var base = assignedResponse(request());
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var malformed =
        List.of(
            base.toBuilder().setSchemaVersion(2).build(),
            base.toBuilder().setUnknownFields(unknown).build(),
            base.toBuilder().setAssignmentUuid("99999999-9999-4999-8999-999999999999").build(),
            base.toBuilder()
                .setCanonicalAccountUuid("99999999-9999-4999-8999-999999999999")
                .build(),
            base.toBuilder().setCharacterUuid("99999999-9999-4999-8999-999999999999").build(),
            base.toBuilder().setIntentDigest("d".repeat(64)).build(),
            base.toBuilder()
                .setExpectedTarget(
                    base.getExpectedTarget().toBuilder().setGameInstanceId("replacement-instance"))
                .build(),
            base.toBuilder()
                .setExpectedTarget(
                    base.getExpectedTarget().toBuilder()
                        .setPlayableStateNamespaceUuid("99999999-9999-4999-8999-999999999999"))
                .build(),
            base.toBuilder()
                .setExpectedTarget(base.getExpectedTarget().toBuilder().setUnknownFields(unknown))
                .build(),
            base.toBuilder().setRuntimeEntityId(0).build(),
            base.toBuilder().setRuntimeEntityId(-1).build(),
            base.toBuilder().setProjectionGeneration(0).build(),
            base.toBuilder().setProjectionFence(0).build(),
            base.toBuilder().setPublishedOwnerProofDigest("sha256:" + "b".repeat(64)).build(),
            base.toBuilder().clearCorePayload().build(),
            base.toBuilder()
                .setCorePayload(base.getCorePayload().toBuilder().setUnknownFields(unknown))
                .build(),
            base.toBuilder()
                .setCorePayload(base.getCorePayload().toBuilder().setActorKindValue(999))
                .build(),
            base.toBuilder()
                .setCorePayload(base.getCorePayload().toBuilder().setDisplayName("x".repeat(9000)))
                .build());
    var stub = stub();
    try (var client = client(stub)) {
      for (var response : malformed) {
        when(stub.readPreseededActorRuntimeReference(request())).thenReturn(response);
        assertThatThrownBy(() -> client.read(request())).isInstanceOf(IllegalStateException.class);
      }
    }
  }

  @Test
  void rejectsInvalidSelectorsAndAmbientSqlBeforeActualRead() throws Exception {
    var stub = stub();
    try (var client = client(stub)) {
      for (var invalid :
          List.of(
              request().toBuilder().setSchemaVersion(2).build(),
              request().toBuilder().clearExpectedTarget().build(),
              request().toBuilder()
                  .setCharacterUuid("00000000-0000-0000-0000-000000000000")
                  .build(),
              request().toBuilder().setIntentDigest("no").build(),
              request().toBuilder()
                  .setExpectedTarget(
                      request().getExpectedTarget().toBuilder().setGameInstanceId("x".repeat(9000)))
                  .build())) {
        assertThatThrownBy(() -> client.read(invalid)).isInstanceOf(IllegalArgumentException.class);
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(request())).isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clear();
      }
      verify(stub, never()).readPreseededActorRuntimeReference(any());
    }
  }

  private CanonicalPreseededActorRuntimeReferenceClient client(
      CanonicalPreseededActorAssignmentServiceGrpc
              .CanonicalPreseededActorAssignmentServiceBlockingStub
          stub)
      throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "test-cert").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "test-key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "test-ca").toString());
    var channels = mock(GrpcChannelFactory.class);
    when(channels.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    var client =
        new CanonicalPreseededActorRuntimeReferenceClient(
            new ServiceEndpointsProperties(), tls, channels, NAMESPACE);
    client.init();
    ReflectionTestUtils.setField(client, "stub", stub);
    return client;
  }

  private static CanonicalPreseededActorAssignmentServiceGrpc
          .CanonicalPreseededActorAssignmentServiceBlockingStub
      stub() {
    var stub =
        mock(
            CanonicalPreseededActorAssignmentServiceGrpc
                .CanonicalPreseededActorAssignmentServiceBlockingStub.class);
    when(stub.withDeadlineAfter(20, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.withMaxInboundMessageSize(8192)).thenReturn(stub);
    when(stub.withMaxOutboundMessageSize(8192)).thenReturn(stub);
    return stub;
  }

  private static ReadPreseededActorRuntimeReferenceRequest request() {
    return ReadPreseededActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid("11111111-1111-4111-8111-111111111111")
        .setCanonicalAccountUuid("33333333-3333-4333-8333-333333333333")
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid("44444444-4444-4444-8444-444444444444")
                .setRealmUuid("55555555-5555-4555-8555-555555555555")
                .setWorldSlug("outer-reach")
                .setRealmSlug("green-march")
                .setGameInstanceId("opaque-instance:preserved")
                .setCatalogRevision(7L)
                .setCanonicalVersionUuid("66666666-6666-4666-8666-666666666666")
                .setFrozenPolicyDigest("b".repeat(64))
                .setPlayableStateNamespaceUuid("77777777-7777-4777-8777-777777777777")
                .setPublishedReleaseBundleRef("release:bounded/v1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .setCharacterUuid(CHARACTER_UUID)
        .setIntentDigest(INTENT_DIGEST)
        .build();
  }

  private static ReadPreseededActorRuntimeReferenceResponse assignedResponse(
      ReadPreseededActorRuntimeReferenceRequest request) {
    return ReadPreseededActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setIntentDigest(request.getIntentDigest())
        .setExpectedTarget(request.getExpectedTarget())
        .setPublishedOwnerProofDigest("c".repeat(64))
        .setRuntimeEntityId(7301L)
        .setProjectionGeneration(1)
        .setProjectionFence(1)
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Pilot One"))
        .build();
  }
}
