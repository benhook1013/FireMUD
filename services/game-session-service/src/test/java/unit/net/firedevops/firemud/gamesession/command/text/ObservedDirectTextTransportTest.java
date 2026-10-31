package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservedDirectTextTransportTest {
  private static final String PROXY_CONNECTION_ID = "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814";

  @Test
  void acceptsOnlyCanonicalNonNilUuidV4ObservedInTrustedProxyMode() {
    assertThat(
            ObservedDirectTextTransport.fromHandshake(
                "trusted_tcp_proxy", "41", PROXY_CONNECTION_ID))
        .contains(new ObservedDirectTextTransport("41", UUID.fromString(PROXY_CONNECTION_ID)));
    assertThat(
            ObservedDirectTextTransport.fromHandshake(
                "trusted_tcp_proxy", "41", "00000000-0000-0000-0000-000000000000"))
        .isEmpty();
    assertThat(
            ObservedDirectTextTransport.fromHandshake(
                "trusted_tcp_proxy", "41", "9fb38c88-2f1d-12aa-8e0b-7eb69caf8814"))
        .isEmpty();
    assertThat(
            ObservedDirectTextTransport.fromHandshake("first_party_web", "41", PROXY_CONNECTION_ID))
        .isEmpty();
  }

  @Test
  void constructorRejectsNonNumericTransportIdAndNonV4ProxyId() {
    assertThatThrownBy(
            () ->
                new ObservedDirectTextTransport(
                    "transport-hash", UUID.fromString(PROXY_CONNECTION_ID)))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalidSession : List.of("0", "-1", "9223372036854775808")) {
      assertThat(
              ObservedDirectTextTransport.fromHandshake(
                  "trusted_tcp_proxy", invalidSession, PROXY_CONNECTION_ID))
          .isEmpty();
    }
    assertThatThrownBy(
            () ->
                new ObservedDirectTextTransport(
                    "41", UUID.fromString("00000000-0000-0000-0000-000000000000")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dispatchRequestRejectsObservationBoundToDifferentSessionId() {
    ObservedDirectTextTransport observed =
        new ObservedDirectTextTransport("41", UUID.fromString(PROXY_CONNECTION_ID));

    assertThatThrownBy(
            () ->
                new TextCommandDispatchRequest(
                    "42",
                    new TextCommand(TextCommandType.WORLDS, List.of(), "WORLDS"),
                    false,
                    Optional.empty(),
                    Optional.of(observed)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
