package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.junit.jupiter.api.Test;

class IssuerAuthorityProjectionV1CodecTest {
  private static final String ISSUER_ID = "https://accounts.example.test/issuer";
  private static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void verifiesOriginalZeroCheckpointAndOmitsEventEvidence() {
    IssuerAuthorityProjectionV1Codec.Projection projection =
        IssuerAuthorityProjectionV1Codec.verify(zeroProjection());

    assertThat(projection.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(projection.generation()).isEqualTo(BigInteger.ONE);
    assertThat(projection.sourceVersion()).isEqualTo(BigInteger.ONE);
    assertThat(projection.sequence()).isEqualTo(BigInteger.ZERO);
    assertThat(projection.streamKey())
        .isEqualTo(
            IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER_ID);
    assertThat(projection.appliedAt()).isEqualTo("stable-time");
    assertThat(projection.latestEvent()).isEmpty();
  }

  @Test
  void verifiesPositiveUnicodeCanonicalEventAndArbitraryPrecisionCounters() throws Exception {
    String unicodeIssuer = "https://accounts.example.test/issüer";
    String sequence = "18446744073709551616000000000000000001";
    String generation = "92233720368547758081234567890123456789";
    String sourceVersion = "90071992547409931234567890123456789";
    IssuerGenerationAuthorityEvent event =
        event(unicodeIssuer, sequence, generation, sourceVersion);
    Map<String, Object> supplied = positiveProjection(unicodeIssuer, event);

    IssuerAuthorityProjectionV1Codec.Projection fromMap =
        IssuerAuthorityProjectionV1Codec.verify(supplied);
    IssuerAuthorityProjectionV1Codec.Projection fromJson =
        IssuerAuthorityProjectionV1Codec.verify(JSON.writeValueAsString(supplied));

    assertThat(event.canonicalJson()).contains("issüer");
    assertThat(fromMap.generation()).isEqualTo(new BigInteger(generation));
    assertThat(fromMap.sourceVersion()).isEqualTo(new BigInteger(sourceVersion));
    assertThat(fromMap.sequence()).isEqualTo(new BigInteger(sequence));
    assertThat(fromMap.latestEvent()).isPresent();
    assertThat(fromMap.latestEvent().orElseThrow().canonicalJson())
        .isEqualTo(event.canonicalJson());
    assertThat(fromJson.latestEvent().orElseThrow().canonicalJson())
        .isEqualTo(event.canonicalJson());
  }

  @Test
  void rejectsUnknownNullAliasAndNoncanonicalProjectionValues() {
    Map<String, Object> unknown = zeroProjection();
    unknown.put("unexpected", "value");
    assertRejected(unknown);

    Map<String, Object> nullAppliedAt = zeroProjection();
    nullAppliedAt.put("appliedAt", null);
    assertRejected(nullAppliedAt);

    Map<String, Object> nullEvidence = zeroProjection();
    nullEvidence.put("appliedSourceEvidence", null);
    assertRejected(nullEvidence);

    Map<String, Object> oldAlias = zeroProjection();
    oldAlias.put("issuerAuthGeneration", "1");
    assertRejected(oldAlias);

    Map<String, Object> oldSourceAliases = zeroProjection();
    oldSourceAliases.put("sourceOutboxStreamKey", "legacy-stream");
    oldSourceAliases.put("sourceEventId", "legacy-event");
    oldSourceAliases.put("sourceEventDigest", "legacy-digest");
    assertRejected(oldSourceAliases);

    Map<String, Object> leadingZeroGeneration = zeroProjection();
    leadingZeroGeneration.put("lastAppliedIssuerGeneration", "01");
    assertRejected(leadingZeroGeneration);

    Map<String, Object> leadingZeroSequence = zeroProjection();
    leadingZeroSequence.put("lastAppliedSourceOutboxSequence", "00");
    assertRejected(leadingZeroSequence);

    assertThatThrownBy(() -> IssuerAuthorityProjectionV1Codec.verify((Map<String, ?>) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsContradictoryZeroBaselineAndNoncurrentOrMismatchedEventEvidence() {
    Map<String, Object> zeroWithEvent = zeroProjection();
    zeroWithEvent.put("appliedSourceEvidence", Map.of("1", "event"));
    assertRejected(zeroWithEvent);

    IssuerGenerationAuthorityEvent event = event(ISSUER_ID, "2", "3", "4");
    Map<String, Object> mismatchedIssuer = positiveProjection("https://other.test/issuer", event);
    assertRejected(mismatchedIssuer);

    Map<String, Object> mismatchedEventId = positiveProjection(ISSUER_ID, event);
    mismatchedEventId.put("lastAppliedSourceEventId", "wrong-event");
    assertRejected(mismatchedEventId);

    Map<String, Object> mismatchedDigest = positiveProjection(ISSUER_ID, event);
    mismatchedDigest.put("lastAppliedSourceEventDigest", "sha256:" + "0".repeat(64));
    assertRejected(mismatchedDigest);

    Map<String, Object> wrongEvidenceSequence = positiveProjection(ISSUER_ID, event);
    wrongEvidenceSequence.put("appliedSourceEvidence", Map.of("1", event.canonicalJson()));
    assertRejected(wrongEvidenceSequence);

    Map<String, Object> multipleEvidenceEntries = positiveProjection(ISSUER_ID, event);
    multipleEvidenceEntries.put(
        "appliedSourceEvidence", Map.of("2", event.canonicalJson(), "1", event.canonicalJson()));
    assertRejected(multipleEvidenceEntries);
  }

  @Test
  void jsonDecoderRejectsDuplicatePropertiesTrailingContentAndUnknownFields() throws Exception {
    String json = JSON.writeValueAsString(zeroProjection());
    String duplicate =
        "{\"schemaVersion\":\""
            + IssuerAuthorityProjectionV1Codec.SCHEMA_VERSION
            + "\","
            + json.substring(1);

    assertThatThrownBy(() -> IssuerAuthorityProjectionV1Codec.verify(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IssuerAuthorityProjectionV1Codec.verify(json + " {}"))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> unknown = zeroProjection();
    unknown.put("schemaAlias", "legacy");
    assertThatThrownBy(
            () -> IssuerAuthorityProjectionV1Codec.verify(JSON.writeValueAsString(unknown)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> zeroProjection() {
    Map<String, Object> projection = new LinkedHashMap<>();
    projection.put("schemaVersion", IssuerAuthorityProjectionV1Codec.SCHEMA_VERSION);
    projection.put("issuerId", ISSUER_ID);
    projection.put("lastAppliedIssuerGeneration", "1");
    projection.put("lastAppliedSourceOutboxSequence", "0");
    projection.put(
        "outboxStreamKey",
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER_ID);
    projection.put("appliedAt", "stable-time");
    projection.put("appliedSourceEvidence", Map.of());
    return projection;
  }

  private static Map<String, Object> positiveProjection(
      String issuerId, IssuerGenerationAuthorityEvent event) {
    Map<String, Object> projection = new LinkedHashMap<>();
    projection.put("schemaVersion", IssuerAuthorityProjectionV1Codec.SCHEMA_VERSION);
    projection.put("issuerId", issuerId);
    projection.put("lastAppliedIssuerGeneration", event.issuerAuthGeneration());
    projection.put("lastAppliedSourceOutboxSequence", event.outboxSequence());
    projection.put(
        "outboxStreamKey",
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuerId);
    projection.put("lastAppliedSourceEventId", event.eventId());
    projection.put("lastAppliedSourceEventDigest", event.eventDigest());
    projection.put("appliedAt", "stable-time");
    projection.put("appliedSourceEvidence", Map.of(event.outboxSequence(), event.canonicalJson()));
    return projection;
  }

  private static IssuerGenerationAuthorityEvent event(
      String issuerId, String sequence, String generation, String sourceVersion) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION);
    preimage.put("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE);
    preimage.put("eventId", "account-issuer-authority-event-v1:" + REQUEST_ID);
    preimage.put("requestId", REQUEST_ID);
    preimage.put("issuerId", issuerId);
    preimage.put("sourceScope", "issuer/" + issuerId);
    preimage.put(
        "outboxStreamKey",
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuerId);
    preimage.put("outboxSequence", sequence);
    preimage.put("issuerAuthGeneration", generation);
    preimage.put("sourceVersion", sourceVersion);
    return IssuerGenerationAuthorityEventV1Codec.seal(preimage);
  }

  private static void assertRejected(Map<String, ?> supplied) {
    assertThatThrownBy(() -> IssuerAuthorityProjectionV1Codec.verify(supplied))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
