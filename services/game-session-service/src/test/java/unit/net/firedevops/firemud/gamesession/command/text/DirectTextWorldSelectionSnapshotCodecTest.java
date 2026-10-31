package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.Choice;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectTextWorldSelectionSnapshotCodecTest {
  private static final Instant CREATED = Instant.parse("2026-10-31T12:00:00Z");

  @Test
  void roundTripsImmutableEvidenceWithoutCreatingAUsableHandle() {
    ArrayList<Choice> choices = new ArrayList<>();
    var original = new Snapshot(choices, "original-fingerprint", CREATED, CREATED.plusSeconds(300));
    byte[] encoded = DirectTextWorldSelectionSnapshotCodec.encode(original);
    var decoded = DirectTextWorldSelectionSnapshotCodec.decode(encoded).orElseThrow();

    assertThat(decoded).isEqualTo(original);
    assertThat(decoded.choices()).isEmpty();
    assertThatThrownBy(() -> decoded.choices().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    Arrays.fill(encoded, (byte) 0);
    assertThat(decoded).isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "{}",
        "[]",
        "{",
        "{\"schema\":\"direct-text-world-selection/v2\",\"snapshot\":{}}",
        "{\"schema\":\"direct-text-world-selection/v1\",\"snapshot\":null}"
      })
  void rejectsUnsupportedAndMalformedPayloads(String payload) {
    assertThat(
            DirectTextWorldSelectionSnapshotCodec.decode(payload.getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "version",
        "unknown",
        "nested-unknown",
        "duplicate",
        "nested-duplicate",
        "trailing",
        "missing",
        "null",
        "coercion",
        "whitespace"
      })
  void rejectsSchemaDriftDuplicatesTrailingTokensAndRepairs(String change) {
    String encoded =
        new String(
            DirectTextWorldSelectionSnapshotCodec.encode(snapshot("evidence")),
            StandardCharsets.UTF_8);
    String altered =
        switch (change) {
          case "version" ->
              encoded.replace("direct-text-world-selection/v1", "direct-text-world-selection/v2");
          case "unknown" -> encoded.replaceFirst("\\{", "{\"unexpected\":true,");
          case "nested-unknown" ->
              encoded.replace("\"choices\":[]", "\"choices\":[],\"unexpected\":true");
          case "duplicate" ->
              encoded.replace(
                  "\"schema\":", "\"schema\":\"direct-text-world-selection/v1\",\"schema\":");
          case "nested-duplicate" ->
              encoded.replace("\"choices\":[]", "\"choices\":[],\"choices\":[]");
          case "trailing" -> encoded + "{}";
          case "missing" -> encoded.replace("\"choices\":[],", "");
          case "null" -> encoded.replace("\"choices\":[]", "\"choices\":null");
          case "coercion" -> encoded.replace("\"fingerprint\":\"evidence\"", "\"fingerprint\":42");
          case "whitespace" -> " " + encoded;
          default -> throw new AssertionError(change);
        };

    assertThat(altered).isNotEqualTo(encoded);
    assertThat(
            DirectTextWorldSelectionSnapshotCodec.decode(altered.getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }

  @Test
  void encodedAndDecodedTotalPayloadHaveAnInclusiveOneMiBBound() {
    int overhead = DirectTextWorldSelectionSnapshotCodec.encode(snapshot("")).length;
    String fingerprint =
        "x".repeat(DirectTextWorldSelectionSnapshotCodec.MAX_PAYLOAD_BYTES - overhead);
    byte[] atLimit = DirectTextWorldSelectionSnapshotCodec.encode(snapshot(fingerprint));

    assertThat(atLimit).hasSize(1024 * 1024);
    assertThat(DirectTextWorldSelectionSnapshotCodec.decode(atLimit))
        .contains(snapshot(fingerprint));
    assertThatThrownBy(
            () -> DirectTextWorldSelectionSnapshotCodec.encode(snapshot(fingerprint + "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            DirectTextWorldSelectionSnapshotCodec.decode(
                Arrays.copyOf(atLimit, atLimit.length + 1)))
        .isEmpty();
    assertThat(DirectTextWorldSelectionSnapshotCodec.decode(new byte[0])).isEmpty();
    assertThat(DirectTextWorldSelectionSnapshotCodec.decode(null)).isEmpty();
  }

  @Test
  void rejectsInvalidUtf8AndAlternateCharacterEncoding() {
    byte[] invalid = DirectTextWorldSelectionSnapshotCodec.encode(snapshot("evidence"));
    for (int index = 0; index < invalid.length; index++) {
      if (invalid[index] == 'e') {
        invalid[index] = (byte) 0xff;
        break;
      }
    }
    assertThat(DirectTextWorldSelectionSnapshotCodec.decode(invalid)).isEmpty();
    String encoded =
        new String(
            DirectTextWorldSelectionSnapshotCodec.encode(snapshot("evidence")),
            StandardCharsets.UTF_8);
    assertThat(
            DirectTextWorldSelectionSnapshotCodec.decode(encoded.getBytes(StandardCharsets.UTF_16)))
        .isEmpty();
  }

  private static Snapshot snapshot(String fingerprint) {
    return new Snapshot(List.of(), fingerprint, CREATED, CREATED.plusSeconds(300));
  }
}
