package net.firedevops.firemud.gamesession.data;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceFixtureActivationRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalFixtureInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import org.junit.jupiter.api.Test;

class RunOwnedCanonicalInitialAdmissionOwnerTest {
  @Test
  void defaultOffFixedSourceDeniesBeforeAnyOwnerOrWorldRead() {
    var launch = mock(GameSessionCanonicalInstanceLaunchRepository.class);
    var fixed =
        new RunOwnedCanonicalLaunchPreparationOwner(
            false, "", "", "", "", new CommonGrpcClientProperties(), launch, "fixture");
    var repository = mock(CanonicalFixtureInitialAdmissionRepository.class);
    var world = mock(RunOwnedCanonicalInitialAdmissionOwner.WorldTransport.class);
    var owner = new RunOwnedCanonicalInitialAdmissionOwner(fixed, repository, world);
    assertThatThrownBy(() -> owner.open(request(InitialAdmissionOrigin.NO_PRIOR_POINTER)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(launch, repository, world);
  }

  @Test
  void closedOriginIsRejectedBeforeFixedSourceIntentOrHold() {
    var fixed = mock(RunOwnedCanonicalLaunchPreparationOwner.class);
    var repository = mock(CanonicalFixtureInitialAdmissionRepository.class);
    var world = mock(RunOwnedCanonicalInitialAdmissionOwner.WorldTransport.class);
    var owner = new RunOwnedCanonicalInitialAdmissionOwner(fixed, repository, world);
    assertThatThrownBy(() -> owner.open(request(InitialAdmissionOrigin.EXPECT_CLOSED)))
        .hasMessageContaining("NO_PRIOR_POINTER");
    verifyNoInteractions(fixed, repository, world);
  }

  private static WorldFixtureInitialAdmissionHoldRequest request(InitialAdmissionOrigin origin) {
    UUID tenant = UUID.randomUUID();
    UUID realm = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    String id = UUID.randomUUID().toString();
    Long prior = origin == InitialAdmissionOrigin.NO_PRIOR_POINTER ? null : 1L;
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            "fixture",
            tenant,
            "fixture-world",
            realm,
            namespace,
            "SHARED",
            instance,
            version,
            2L,
            1L,
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(origin.name()),
            prior,
            id);
    return new WorldFixtureInitialAdmissionHoldRequest(
        new Request(
            "fixture",
            tenant,
            "fixture-world",
            realm,
            namespace,
            "SHARED",
            instance,
            version,
            2L,
            id,
            digest,
            origin,
            1L,
            prior),
        new WorldCanonicalInstanceFixtureActivationRequest(
            UUID.randomUUID(), tenant, instance, "sha256:" + "a".repeat(64), 1L, 0L));
  }
}
