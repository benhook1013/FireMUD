package unit.net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstanceActivationTest {
  private static final UUID ACTIVATION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID CANONICAL_INSTANCE_ID =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void stableOperationIdentityOmitsOnlyLifecycleReadCorrelationAndRetainsOriginalProof() {
    var firstEvidence = evidence("11111111-1111-4111-8111-111111111111", "version-a");
    var retryEvidence = evidence("22222222-2222-4222-8222-222222222222", "version-a");
    var changedEvidence = evidence("33333333-3333-4333-8333-333333333333", "version-b");
    var first = new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, firstEvidence);
    var retry = new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, retryEvidence);
    var changed = new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, changedEvidence);

    assertThat(first.canonicalRequestBytes()).containsExactly(retry.canonicalRequestBytes());
    assertThat(first.requestDigest()).isEqualTo(retry.requestDigest());
    assertThat(first.preparingEvidenceBytes()).isNotEqualTo(retry.preparingEvidenceBytes());
    assertThat(changed.canonicalRequestBytes()).isNotEqualTo(first.canonicalRequestBytes());
  }

  @Test
  void requestRejectsNilOperationIdentityAndNonPreparingEvidence() {
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceActivation.Request(
                    new UUID(0L, 0L), evidence("11111111-1111-4111-8111-111111111111", "v")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil");
    var active = evidence("11111111-1111-4111-8111-111111111111", "v");
    when(active.lifecycleStatus()).thenReturn("ACTIVE");
    assertThatThrownBy(() -> new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, active))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PREPARING");
  }

  private static WorldCanonicalInstanceLifecycleEvidence evidence(String readId, String version) {
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.canonicalGameInstanceId()).thenReturn(CANONICAL_INSTANCE_ID);
    var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(evidence.request()).thenReturn(lifecycleRequest);
    when(evidence.lifecycleStatus()).thenReturn("PREPARING");
    when(evidence.lifecycleEpoch()).thenReturn(7L);
    when(evidence.rowVersion()).thenReturn(11L);
    when(evidence.canonicalBytes())
        .thenReturn(
            ("{\"request\":{\"readRequestId\":\""
                    + readId
                    + "\",\"canonicalVersionId\":\""
                    + version
                    + "\",\"canonicalGameInstanceId\":\""
                    + CANONICAL_INSTANCE_ID
                    + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
    return evidence;
  }
}
