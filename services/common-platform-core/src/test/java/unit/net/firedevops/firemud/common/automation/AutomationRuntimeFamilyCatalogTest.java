package unit.net.firedevops.firemud.common.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import org.junit.jupiter.api.Test;

/**
 * Controlled catalog bytes prove only the value/framing boundary, not installed SQL completeness.
 */
class AutomationRuntimeFamilyCatalogTest {
  private static final String FAMILY =
      """
      {"familyId":"npc_memory","relation":"npc_memory","component":"","role":"RUNTIME_PAYLOAD",
       "stateClass":"UNKNOWN","freshDisposition":"STATE_CLASS_UNKNOWN_BLOCKING",
       "retentionDisposition":"UNRESOLVED_RETAINED","ownerConsequence":"Namespace and actor class unresolved; no clone."}
      """;
  private static final String FACT =
      """
      {"kind":"TABLE","identity":"npc_memory",
       "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
      """;
  private static final String GAP =
      """
      {"id":"DURABLE_CHARGE_MARKERS","disposition":"UNSUPPORTED_BLOCKING",
       "ownerConsequence":"No durable charge rows or admission/execution markers exist."}
      """;
  private static final String PREFIX =
      """
      {"schema":"automation-runtime-family-catalog/v1","owner":"automation-scripting-service","catalogEpoch":"1",
      """;
  private static final String CATALOG =
      PREFIX
          + "\"families\":["
          + FAMILY
          + "],\"schemaFacts\":["
          + FACT
          + "],\"nonSqlGaps\":["
          + GAP
          + "]}";

  @Test
  void stablePurposeSpecificRequestFramingAndDigestVector() {
    var read = new Request(UUID.fromString("11111111-1111-4111-8111-111111111111"), "test");
    assertThat(
            java.util.HexFormat.of()
                .formatHex(AutomationRuntimeFamilyCatalogCodec.requestBytes(read)))
        .isEqualTo("41435231111111111111411181111111111111110000000474657374");
    assertThat(AutomationRuntimeFamilyCatalog.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void exactRoundtripRetainsOriginalBytesDigestsRequestAndImmutableCollections() {
    byte[] source = CATALOG.getBytes(StandardCharsets.UTF_8);
    var catalog = AutomationRuntimeFamilyCatalog.fromStored(source);
    var read = new Request(UUID.randomUUID(), "test");
    var evidence = new Evidence(read, UUID.randomUUID(), catalog);
    assertThat(catalog.catalogEpoch()).isEqualTo(1);
    assertThat(catalog.digest()).isEqualTo(AutomationRuntimeFamilyCatalog.sha256(source));
    assertThat(
            AutomationRuntimeFamilyCatalogCodec.fromRequest(
                AutomationRuntimeFamilyCatalogCodec.toRequest(read)))
        .isEqualTo(read);
    assertThat(
            AutomationRuntimeFamilyCatalogCodec.fromResponse(
                read, AutomationRuntimeFamilyCatalogCodec.toResponse(read, evidence)))
        .isEqualTo(evidence);
    assertThat(
            AutomationRuntimeFamilyCatalogCodec.evidenceFromStored(
                    AutomationRuntimeFamilyCatalogCodec.evidenceBytes(evidence))
                .digest())
        .isEqualTo(evidence.digest());
    source[0] = 0;
    catalog.exactBytes()[0] = 0;
    assertThat(catalog.exactBytes()).isEqualTo(CATALOG.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> catalog.families().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> catalog.schemaFacts().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> catalog.nonSqlGaps().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void unknownDuplicateMissingInconsistentAndTrailingCatalogFieldsRefuse() {
    for (String invalid :
        new String[] {
          CATALOG.replace("\"owner\":", "\"extra\":true,\"owner\":"),
          CATALOG.replace(
              "\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"1\",\"catalogEpoch\":\"1\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"0\""),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":1"),
          CATALOG.replace("\"catalogEpoch\":\"1\"", "\"catalogEpoch\":\"9223372036854775808\""),
          CATALOG.replace(
              "automation-runtime-family-catalog/v1", "automation-runtime-family-catalog/v2"),
          CATALOG.replace("automation-scripting-service", "social-groups-service"),
          CATALOG.replace("\"UNKNOWN\"", "\"S3\""),
          CATALOG.replace("\"UNKNOWN\"", "\"NOT_PAYLOAD\""),
          CATALOG.replace("\"RUNTIME_PAYLOAD\"", "\"SOURCE\""),
          CATALOG.replace("STATE_CLASS_UNKNOWN_BLOCKING", "OWNERSHIP_DRIFT_BLOCKING"),
          CATALOG.replace("\"identity\":\"npc_memory\"", "\"identity\":\"future_family\""),
          CATALOG.replace("\"component\":\"\"", "\"component\":\"key\""),
          CATALOG.replace("UNRESOLVED_RETAINED", "DISPOSABLE"),
          CATALOG.replace("DURABLE_CHARGE_MARKERS", "REDIS_CENSUS_COMPLETE"),
          CATALOG.replace("UNSUPPORTED_BLOCKING", "COMPLETE"),
          CATALOG.replace(FAMILY, FAMILY + "," + FAMILY),
          CATALOG.replace(FACT, FACT + "," + FACT),
          CATALOG.replace(GAP, GAP + "," + GAP),
          CATALOG.replace("Namespace and actor class unresolved; no clone.", ""),
          CATALOG + "{}"
        }) {
      assertThatThrownBy(
              () ->
                  AutomationRuntimeFamilyCatalog.fromStored(
                      invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void familyFactGapAndSourceBoundsAreIndependentOfNoPayloadRows() {
    String manyFamilies =
        PREFIX
            + "\"families\":["
            + String.join(",", Collections.nCopies(513, FAMILY))
            + "],\"schemaFacts\":["
            + FACT
            + "],\"nonSqlGaps\":["
            + GAP
            + "]}";
    String compactFact =
        "{\"kind\":\"TYPE\",\"identity\":\"x\",\"definitionDigest\":\"sha256:"
            + "a".repeat(64)
            + "\"}";
    String manyFacts =
        PREFIX
            + "\"families\":["
            + FAMILY
            + "],\"schemaFacts\":["
            + String.join(",", Collections.nCopies(16385, compactFact))
            + "],\"nonSqlGaps\":["
            + GAP
            + "]}";
    String manyGaps = CATALOG.replace(GAP, String.join(",", Collections.nCopies(33, GAP)));
    for (String invalid :
        new String[] {
          manyFamilies,
          manyFacts,
          manyGaps,
          CATALOG.replace(FAMILY, ""),
          CATALOG.replace(FACT, ""),
          CATALOG.replace(GAP, "")
        }) {
      assertThatThrownBy(
              () ->
                  AutomationRuntimeFamilyCatalog.fromStored(
                      invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalog.fromStored(
                    new byte[AutomationRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateUnknownOversizedAndChangedCorrelationWireValuesRefuse() {
    var read = new Request(UUID.randomUUID(), "test");
    var request = AutomationRuntimeFamilyCatalogCodec.toRequest(read);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(9, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromRequest(
                    request.toBuilder()
                        .addCanonicalRequestBytes(request.getCanonicalRequestBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromRequest(
                    request.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromRequest(
                    request.toBuilder()
                        .setCanonicalRequestBytes(0, ByteString.copyFrom(new byte[1025]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var evidence = new Evidence(read, UUID.randomUUID(), catalog());
    var response = AutomationRuntimeFamilyCatalogCodec.toResponse(read, evidence);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(UUID.randomUUID(), "test"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromResponse(
                    new Request(read.readRequestId(), "other"), response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromResponse(
                    read,
                    response.toBuilder()
                        .addCanonicalResponseBytes(response.getCanonicalResponseBytes(0))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromResponse(
                    read, response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationRuntimeFamilyCatalogCodec.fromResponse(
                    read,
                    response.toBuilder()
                        .setCanonicalResponseBytes(
                            0,
                            ByteString.copyFrom(
                                new byte
                                    [AutomationRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void truncationTrailingInvalidLengthsMagicDigestUtf8AndIdentityRefuse() {
    var evidence =
        new Evidence(new Request(UUID.randomUUID(), "test"), UUID.randomUUID(), catalog());
    byte[] frame = AutomationRuntimeFamilyCatalogCodec.evidenceBytes(evidence);
    for (byte[] invalid :
        new byte[][] {
          Arrays.copyOf(frame, frame.length - 1), Arrays.copyOf(frame, frame.length + 1)
        }) {
      assertThatThrownBy(() -> AutomationRuntimeFamilyCatalogCodec.evidenceFromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    int digestOffset =
        4
            + 4
            + AutomationRuntimeFamilyCatalogCodec.requestBytes(evidence.request()).length
            + 16
            + 4;
    frame[digestOffset + 7] = frame[digestOffset + 7] == 'a' ? (byte) 'b' : (byte) 'a';
    assertThatThrownBy(() -> AutomationRuntimeFamilyCatalogCodec.evidenceFromStored(frame))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] request = AutomationRuntimeFamilyCatalogCodec.requestBytes(evidence.request());
    request[20] = (byte) 0x7f;
    assertThatThrownBy(() -> AutomationRuntimeFamilyCatalogCodec.requestFromStored(request))
        .isInstanceOf(IllegalArgumentException.class);
    request[0] = 'W';
    assertThatThrownBy(() -> AutomationRuntimeFamilyCatalogCodec.requestFromStored(request))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AutomationRuntimeFamilyCatalog.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(new UUID(0, 0), "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(UUID.randomUUID(), "1/sa/automation-scripting-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AutomationRuntimeFamilyCatalog catalog() {
    return AutomationRuntimeFamilyCatalog.fromStored(CATALOG.getBytes(StandardCharsets.UTF_8));
  }
}
