package unit.net.firedevops.firemud.accountservice.tenantrole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.tenantrole.TransferCaptureBounds;
import org.junit.jupiter.api.Test;

class TransferCaptureBoundsTest {
  @Test
  void reverseRequestsUseTheSameUuidStringOrderRatherThanSignedUuidOrder() {
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    assertThat(TransferCaptureBounds.orderedAccounts(high, low)).containsExactly(low, high);
    assertThat(TransferCaptureBounds.orderedAccounts(low, high)).containsExactly(low, high);
    assertThatThrownBy(() -> TransferCaptureBounds.orderedAccounts(low, low))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TransferCaptureBounds.orderedAccounts(low, new UUID(0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void envelopeIsBoundedBeforeCopyAndOwnsItsBytes() {
    byte[] source = {1};
    byte[] copy = TransferCaptureBounds.copyEnvelope(source);
    source[0] = 2;
    assertThat(copy).containsExactly((byte) 1);
    assertThat(
            TransferCaptureBounds.copyEnvelope(new byte[TransferCaptureBounds.MAX_CAPTURE_BYTES]))
        .hasSize(TransferCaptureBounds.MAX_CAPTURE_BYTES);
    assertThatThrownBy(() -> TransferCaptureBounds.copyEnvelope(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TransferCaptureBounds.copyEnvelope(
                    new byte[TransferCaptureBounds.MAX_CAPTURE_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hostileDeclaredLengthsCannotAllocateBeyondComponentOrRemainingEnvelope() {
    assertThat(TransferCaptureBounds.componentLength(16384, 16384)).isEqualTo(16384);
    for (int length : new int[] {-1, 0, 16385, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> TransferCaptureBounds.componentLength(length, 262144))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> TransferCaptureBounds.componentLength(2, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TransferCaptureBounds.componentLength(1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
