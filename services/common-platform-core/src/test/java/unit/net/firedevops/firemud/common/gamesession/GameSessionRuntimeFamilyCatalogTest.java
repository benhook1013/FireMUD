package unit.net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.GapId;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalogCodec;
import org.junit.jupiter.api.Test;

/** Controlled local bytes prove shape/framing only, not a complete installed PostgreSQL source. */
class GameSessionRuntimeFamilyCatalogTest {
  private static final String FAMILY =
      """
      {"familyId":"gameplay_command","relation":"gameplay_command","component":"","role":"RUNTIME_PAYLOAD",
       "stateClass":"UNKNOWN","freshDisposition":"STATE_CLASS_UNKNOWN_BLOCKING",
       "retentionDisposition":"UNRESOLVED_RETAINED","ownerConsequence":"Namespace and replacement class unresolved; no clone."}
      """;
  private static final String FACT =
      """
      {"kind":"TABLE","identity":"gameplay_command",
       "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
      """;
  private static final String GAPS =
      Arrays.stream(GapId.values())
          .map(
              id ->
                  "{\"id\":\""
                      + id.name()
                      + "\",\"disposition\":\"UNRESOLVED_BLOCKING\","
                      + "\"ownerConsequence\":\"Controlled known non-SQL gap, not a complete Redis census.\"}")
          .collect(Collectors.joining(","));
  private static final String PREFIX =
      """
      {"schema":"game-session-runtime-family-catalog/v1","owner":"game-session-service","catalogEpoch":"1",
      """;
  private static final String CATALOG =
      PREFIX
          + "\"families\":["
          + FAMILY
          + "],\"schemaFacts\":["
          + FACT
          + "],\"nonSqlGaps\":["
          + GAPS
          + "]}";

  @Test
  void purposeSpecificLocalRequestHasStableFramingAndDigestVector() {
    var request = new Request(UUID.fromString("11111111-1111-4111-8111-111111111111"), "test");
    assertThat(HexFormat.of().formatHex(GameSessionRuntimeFamilyCatalogCodec.requestBytes(request)))
        .isEqualTo("47435231111111111111411181111111111111110000000474657374");
    assertThat(GameSessionRuntimeFamilyCatalog.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void exactRoundTripPreservesOwnerEpochCorrelationBytesDigestsAndImmutableValues() {
    byte[] source = CATALOG.getBytes(StandardCharsets.UTF_8);
    var catalog = GameSessionRuntimeFamilyCatalog.fromStored(source);
    var request = new Request(UUID.randomUUID(), "test");
    var evidence = new Evidence(request, UUID.randomUUID(), catalog);
    byte[] requestBytes = GameSessionRuntimeFamilyCatalogCodec.requestBytes(request);
    byte[] evidenceBytes = GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(evidence);
    assertThat(catalog.catalogEpoch()).isEqualTo(1);
    assertThat(catalog.digest()).isEqualTo(GameSessionRuntimeFamilyCatalog.sha256(source));
    assertThat(GameSessionRuntimeFamilyCatalogCodec.requestFromStored(requestBytes))
        .isEqualTo(request);
    assertThat(GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(evidenceBytes))
        .isEqualTo(evidence);
    assertThat(evidence.digest()).isEqualTo(GameSessionRuntimeFamilyCatalog.sha256(evidenceBytes));
    assertThat(GameSessionRuntimeFamilyCatalog.fromStored(source))
        .hasSameHashCodeAs(catalog)
        .isEqualTo(catalog);
    source[0] = 0;
    catalog.exactBytes()[0] = 0;
    assertThat(catalog.exactBytes()).isEqualTo(CATALOG.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> catalog.families().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> catalog.schemaFacts().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> catalog.nonSqlGaps().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    var other = new Evidence(new Request(UUID.randomUUID(), "other"), UUID.randomUUID(), catalog);
    assertThat(other.digest()).isNotEqualTo(evidence.digest());
  }

  @Test
  void closedSchemaRejectsUnknownDuplicateMissingWrongTypedInconsistentAndTrailingContent() {
    for (String invalid :
        new String[] {
          CATALOG.replace("\"owner\":", "\"extra\":true,\"owner\":"),
          CATALOG.replace(
              "\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"1\",\"catalogEpoch\":\"1\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"0\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":1"),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"01\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"9223372036854775808\""),
          CATALOG.replace(
              "game-session-runtime-family-catalog/v1", "game-session-runtime-family-catalog/v2"),
          CATALOG.replace("game-session-service", "world-management-service"),
          CATALOG.replace("\"UNKNOWN\"", "\"S3\""),
          CATALOG.replace("\"UNKNOWN\"", "\"NOT_PAYLOAD\""),
          CATALOG.replace("\"RUNTIME_PAYLOAD\"", "\"SOURCE\""),
          CATALOG.replace("UNRESOLVED_RETAINED", "DISPOSABLE"),
          CATALOG.replace("CURRENT_PIN_PROJECTION", "REDIS_CENSUS_COMPLETE"),
          CATALOG.replace("UNRESOLVED_BLOCKING", "COMPLETE"),
          CATALOG.replace(
              "\"identity\":\"gameplay_command\"", "\"identity\":\"unknown_empty_table\""),
          CATALOG.replace("\"component\":\"\"", "\"component\":\"command_text\""),
          CATALOG.replace("\"component\":\"\",", ""),
          CATALOG.replace("\"role\":", "\"unexpected\":false,\"role\":"),
          CATALOG.replace(FAMILY, FAMILY + "," + FAMILY),
          CATALOG.replace(FACT, FACT + "," + FACT),
          CATALOG.replace(GAPS, GAPS + "," + GAPS),
          CATALOG.replace("Namespace and replacement class unresolved; no clone.", ""),
          CATALOG.replace(
              "Namespace and replacement class unresolved; no clone.", "forbidden\\ncontrol"),
          CATALOG.replace("sha256:" + "a".repeat(64), "sha256:" + "A".repeat(64)),
          CATALOG + "{}"
        }) {
      assertThatThrownBy(
              () ->
                  GameSessionRuntimeFamilyCatalog.fromStored(
                      invalid.getBytes(StandardCharsets.UTF_8)))
          .as(invalid)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void completeFamilyFactAndKnownGapSetsAreBoundedWithoutPartialSuccess() {
    String manyFamilies =
        CATALOG.replace(FAMILY, String.join(",", Collections.nCopies(513, FAMILY)));
    String compactFact =
        "{\"kind\":\"TYPE\",\"identity\":\"x\",\"definitionDigest\":\"sha256:"
            + "a".repeat(64)
            + "\"}";
    String manyFacts =
        CATALOG.replace(FACT, String.join(",", Collections.nCopies(16385, compactFact)));
    String firstGap = GAPS.substring(0, GAPS.indexOf('}') + 1);
    for (String invalid :
        new String[] {
          manyFamilies,
          manyFacts,
          CATALOG.replace(GAPS, String.join(",", Collections.nCopies(33, firstGap))),
          CATALOG.replace(FAMILY, ""),
          CATALOG.replace(FACT, ""),
          CATALOG.replace(GAPS, ""),
          CATALOG.replace(GAPS, firstGap),
          CATALOG.replace("Namespace and replacement class unresolved; no clone.", "x".repeat(1025))
        }) {
      assertThatThrownBy(
              () ->
                  GameSessionRuntimeFamilyCatalog.fromStored(
                      invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                GameSessionRuntimeFamilyCatalog.fromStored(
                    new byte[GameSessionRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> GameSessionRuntimeFamilyCatalog.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void catalogAtTheExactByteBoundRoundTripsWithoutTruncationOrReserialization() {
    byte[] original = CATALOG.getBytes(StandardCharsets.UTF_8);
    byte[] complete = Arrays.copyOf(original, GameSessionRuntimeFamilyCatalog.MAX_CATALOG_BYTES);
    Arrays.fill(complete, original.length, complete.length, (byte) ' ');
    var catalog = GameSessionRuntimeFamilyCatalog.fromStored(complete);
    var evidence = new Evidence(new Request(UUID.randomUUID(), "test"), UUID.randomUUID(), catalog);
    assertThat(catalog.exactBytes()).isEqualTo(complete);
    assertThat(
            GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(
                GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(evidence)))
        .isEqualTo(evidence);
    assertThatThrownBy(
            () ->
                GameSessionRuntimeFamilyCatalog.fromStored(
                    Arrays.copyOf(complete, complete.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactOrderedTableAndComponentCoverageCannotOmitOrReorderAFamily() {
    String laterFamily = FAMILY.replace("gameplay_command", "remote_followup");
    String laterFact = FACT.replace("gameplay_command", "remote_followup");
    String complete =
        CATALOG.replace(FAMILY, FAMILY + "," + laterFamily).replace(FACT, FACT + "," + laterFact);
    assertThat(
            GameSessionRuntimeFamilyCatalog.fromStored(complete.getBytes(StandardCharsets.UTF_8))
                .families())
        .hasSize(2);
    for (String invalid :
        new String[] {
          complete.replace(FAMILY + "," + laterFamily, laterFamily + "," + FAMILY),
          complete.replace(FACT + "," + laterFact, laterFact + "," + FACT),
          complete.replace(FAMILY + "," + laterFamily, FAMILY),
          complete.replace(FACT + "," + laterFact, FACT),
          complete.replace("\"familyId\":\"gameplay_command\"", "\"familyId\":\"other\"")
        }) {
      assertThatThrownBy(
              () ->
                  GameSessionRuntimeFamilyCatalog.fromStored(
                      invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void localFramesRejectWrongMagicTruncationTrailingLengthsDigestAndIdentity() {
    var evidence =
        new Evidence(
            new Request(UUID.randomUUID(), "test"),
            UUID.randomUUID(),
            GameSessionRuntimeFamilyCatalog.fromStored(CATALOG.getBytes(StandardCharsets.UTF_8)));
    byte[] frame = GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(evidence);
    for (byte[] invalid :
        new byte[][] {
          Arrays.copyOf(frame, frame.length - 1),
          Arrays.copyOf(frame, frame.length + 1),
          new byte[GameSessionRuntimeFamilyCatalogCodec.MAX_EVIDENCE_BYTES + 1],
          new byte[0]
        }) {
      assertThatThrownBy(() -> GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    byte[] request = GameSessionRuntimeFamilyCatalogCodec.requestBytes(evidence.request());
    for (int length : new int[] {-1, 64, Integer.MAX_VALUE}) {
      byte[] invalid = request.clone();
      ByteBuffer.wrap(invalid).putInt(20, length);
      assertThatThrownBy(() -> GameSessionRuntimeFamilyCatalogCodec.requestFromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (byte[] invalid :
        new byte[][] {
          Arrays.copyOf(request, request.length - 1),
          Arrays.copyOf(request, request.length + 1),
          new byte[1025]
        }) {
      assertThatThrownBy(() -> GameSessionRuntimeFamilyCatalogCodec.requestFromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    int digestOffset = 4 + 4 + request.length + 16 + 4;
    frame[digestOffset + 7] = frame[digestOffset + 7] == 'a' ? (byte) 'b' : (byte) 'a';
    assertThatThrownBy(() -> GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(frame))
        .isInstanceOf(IllegalArgumentException.class);
    frame[0] = 'W';
    request[0] = 'W';
    assertThatThrownBy(() -> GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(frame))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GameSessionRuntimeFamilyCatalogCodec.requestFromStored(request))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(new UUID(0, 0), "test"))
        .isInstanceOf(IllegalArgumentException.class);
    for (String namespace :
        new String[] {"", "UPPER", "-test", "test-", "1/sa/game-session-service", "a".repeat(64)}) {
      assertThatThrownBy(() -> new Request(UUID.randomUUID(), namespace))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new Evidence(evidence.request(), new UUID(0, 0), evidence.catalog()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
