package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import org.junit.jupiter.api.Test;

class IssuerGenerationProjectionTest {
  private static final String ISSUER = ControlUiJwtProfileValidator.ISSUER;
  private static final String STREAM = "account:auth-authority:v1:issuer/" + ISSUER;

  @Test
  void baselineOmitsAllEventFieldsAndUsesOnlyCanonicalAccountIssuerKey() {
    var projection =
        IssuerGenerationProjection.fromSource(
            new IssuerAuthoritySnapshot(ISSUER, 1L, 1L, STREAM, 0L, Optional.empty()));
    assertThat(IssuerGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
    assertThat(projection.key()).isEqualTo("session:auth:generation:issuer:" + ISSUER);
    assertThat(projection.toJson())
        .doesNotContain("sourceEvent", "lastAppliedSourceEventId", "lastAppliedSourceEventDigest");
    assertThatThrownBy(() -> IssuerGenerationProjection.keyForIssuer("other-issuer"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactPositiveOwnerEventRetainsIdentityDigestScopeAndCheckpoint() {
    var event = IssuerGenerationAuthorityEventV1Codec.seal(eventFields("2", "2", "1"));
    var projection =
        IssuerGenerationProjection.fromSource(
            new IssuerAuthoritySnapshot(ISSUER, 2L, 2L, STREAM, 1L, Optional.of(event)));
    assertThat(IssuerGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
    assertThat(projection.lastAppliedSourceEventId()).contains(event.eventId());
    assertThat(projection.lastAppliedSourceEventDigest()).contains(event.eventDigest());
    assertThat(projection.sourceEvent()).contains(event.canonicalJson());
    assertThatThrownBy(
            () ->
                new IssuerGenerationProjection(
                    ISSUER,
                    "3",
                    "2",
                    STREAM,
                    "1",
                    Optional.of(event.eventId()),
                    Optional.of(event.eventDigest()),
                    Optional.of(event.canonicalJson())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuerGenerationProjection(
                    ISSUER,
                    "2",
                    "2",
                    STREAM,
                    "1",
                    Optional.of("different-event"),
                    Optional.of(event.eventDigest()),
                    Optional.of(event.canonicalJson())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuerGenerationProjection(
                    ISSUER,
                    "2",
                    "2",
                    STREAM,
                    "1",
                    Optional.of(event.eventId()),
                    Optional.of("sha256:" + "f".repeat(64)),
                    Optional.of(event.canonicalJson())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuerGenerationProjection(
                    ISSUER,
                    "2",
                    "2",
                    STREAM,
                    "1",
                    Optional.of(event.eventId()),
                    Optional.of(event.eventDigest()),
                    Optional.of(
                        event
                            .canonicalJson()
                            .replace("\"sourceVersion\":\"2\"", "\"sourceVersion\":\"3\""))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void independentLargeDecimalCountersRemainExactWithoutFloatingPointOrLongNarrowing() {
    var event =
        IssuerGenerationAuthorityEventV1Codec.seal(
            eventFields("9007199254740993", "9223372036854775809", "9007199254740992"));
    var projection =
        new IssuerGenerationProjection(
            ISSUER,
            event.issuerAuthGeneration(),
            event.sourceVersion(),
            STREAM,
            event.outboxSequence(),
            Optional.of(event.eventId()),
            Optional.of(event.eventDigest()),
            Optional.of(event.canonicalJson()));
    var parsed = IssuerGenerationProjection.parse(projection.toJson());
    assertThat(parsed.generationValue()).isEqualTo(new BigInteger("9007199254740993"));
    assertThat(parsed.sourceVersionValue()).isEqualTo(new BigInteger("9223372036854775809"));
    assertThat(parsed.outboxSequenceValue()).isEqualTo(new BigInteger("9007199254740992"));
  }

  @Test
  void closedParserRejectsDuplicateUnknownNullTrailingAndNoncanonicalForms() {
    String json =
        IssuerGenerationProjection.fromSource(
                new IssuerAuthoritySnapshot(ISSUER, 1L, 1L, STREAM, 0L, Optional.empty()))
            .toJson();
    for (String invalid :
        List.of(
            json + " {}",
            " " + json,
            json.replace("\"issuerAuthGeneration\":\"1\"", "\"issuerAuthGeneration\":1"),
            json.replace("\"issuerAuthGeneration\":\"1\"", "\"issuerAuthGeneration\":null"),
            json.replace(
                "\"issuerAuthGeneration\":\"1\"",
                "\"issuerAuthGeneration\":\"1\",\"issuerAuthGeneration\":\"1\""),
            json.substring(0, json.length() - 1) + ",\"unknown\":\"1\"}",
            json.substring(0, json.length() - 1) + ",\"sourceEvent\":null}")) {
      assertThatThrownBy(() -> IssuerGenerationProjection.parse(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String counter : List.of("0", "-1", "+1", "01", " 1", "1 ", "1.0", "1e3", "")) {
      assertThatThrownBy(
              () ->
                  IssuerGenerationProjection.parse(
                      json.replace(
                          "\"issuerAuthGeneration\":\"1\"",
                          "\"issuerAuthGeneration\":\"" + counter + "\"")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void sequenceZeroCannotInventAdvancedStateAndPositiveSequenceCannotOmitEvidence() {
    assertThatThrownBy(
            () ->
                new IssuerGenerationProjection(
                    ISSUER,
                    "2",
                    "2",
                    STREAM,
                    "0",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IssuerGenerationProjection(
                    ISSUER,
                    "2",
                    "2",
                    STREAM,
                    "1",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> eventFields(
      String generation, String version, String sequence) {
    UUID request = UUID.randomUUID();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION);
    fields.put("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE);
    fields.put("eventId", "account-issuer-authority-event-v1:" + request);
    fields.put("requestId", request.toString());
    fields.put("issuerId", ISSUER);
    fields.put("sourceScope", "issuer/" + ISSUER);
    fields.put("outboxStreamKey", STREAM);
    fields.put("outboxSequence", sequence);
    fields.put("issuerAuthGeneration", generation);
    fields.put("sourceVersion", version);
    return fields;
  }
}
