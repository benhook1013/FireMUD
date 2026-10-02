package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldRequest;
import org.junit.jupiter.api.Test;

class WorldManagementClientTest {

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AtomicInteger customizeCalls = new AtomicInteger();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customizeCalls.incrementAndGet();
            return candidate;
          }
        };
    WorldManagementClient client =
        new WorldManagementClient(endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer);

    invokeBuildStub(client, mock(ManagedChannel.class));

    assertThat(customizeCalls.get()).isEqualTo(1);
  }

  @Test
  void initialAdmissionHoldRequestCarriesExactTypedNoPriorPointerTuple() {
    UUID realmId = UUID.fromString("23d39978-9d44-4e1a-8659-998ff9239b01");
    UUID namespaceId = UUID.fromString("ed1b4d88-81f8-4404-af7c-9a5dc91d3043");
    AcquireInitialAdmissionBindHoldRequest request =
        WorldManagementClient.buildAcquireInitialAdmissionBindHoldRequest(
            7L,
            42L,
            11L,
            4L,
            "d2db8478-9c56-42ab-99c2-9fbead6841ba",
            "a".repeat(64),
            realmId,
            namespaceId,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            3L);

    assertThat(request.getTenantId()).isEqualTo("7");
    assertThat(request.getGameInstanceId()).isEqualTo("42");
    assertThat(request.getVersionId()).isEqualTo("11");
    assertThat(request.getExpectedActiveLifecycleEpoch()).isEqualTo(4L);
    assertThat(request.getInitialAdmissionRequestId())
        .isEqualTo("d2db8478-9c56-42ab-99c2-9fbead6841ba");
    assertThat(request.getRequestDigest()).isEqualTo("a".repeat(64));
    assertThat(request.getRealmUuid()).isEqualTo(realmId.toString());
    assertThat(request.getPlayableStateNamespaceUuid()).isEqualTo(namespaceId.toString());
    assertThat(request.getPlayableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(request.getExpectedNoPriorPointer()).isTrue();
    assertThat(request.getExpectedCatalogRevision()).isEqualTo(3L);
  }

  @Test
  void initialAdmissionHoldRequestRejectsUnspecifiedScopeAndIncompleteTuple() {
    assertThatThrownBy(
            () ->
                WorldManagementClient.buildAcquireInitialAdmissionBindHoldRequest(
                    7L,
                    42L,
                    11L,
                    4L,
                    "request-id",
                    "a".repeat(64),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED,
                    3L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldManagementClient.buildAcquireInitialAdmissionBindHoldRequest(
                    7L,
                    42L,
                    11L,
                    4L,
                    "request-id",
                    "a".repeat(64),
                    null,
                    UUID.randomUUID(),
                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                    3L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void invokeBuildStub(WorldManagementClient client, ManagedChannel channel) {
    try {
      Method method =
          WorldManagementClient.class.getDeclaredMethod("buildStub", ManagedChannel.class);
      method.setAccessible(true);
      method.invoke(client, channel);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }
}
