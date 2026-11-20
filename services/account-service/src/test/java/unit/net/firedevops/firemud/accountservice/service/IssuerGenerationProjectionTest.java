package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;

class IssuerGenerationProjectionTest {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String STREAM = "account:auth-authority:v1:issuer/" + ISSUER;

  @Test
  void baselineOmitsAllEventFieldsAndUsesOnlyCanonicalAccountIssuerKey() {
    var projection = IssuerGenerationProjection.fromSource(source(1L, Optional.empty()));
    assertThat(IssuerGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
    assertThat(projection.key()).isEqualTo("session:auth:generation:issuer:" + ISSUER);
    assertThat(projection.toJson())
        .doesNotContain("sourceEvent", "lastAppliedSourceEventId", "lastAppliedSourceEventDigest");
    assertThatThrownBy(() -> IssuerGenerationProjection.keyForIssuer("other-issuer"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactPositiveOwnerEventRetainsIdentityDigestScopeAndCheckpoint() {
    var event = AccountAuthoritySourceEventV1Codec.sealIssuer(eventFields("2", "2", "1"));
    var projection = IssuerGenerationProjection.fromSource(source(2L, Optional.of(event)));
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
  void largeCanonicalCountersRemainExactWithoutFloatingPointNarrowing() {
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            eventFields("9007199254740993", "9007199254740993", "9007199254740992"));
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
    assertThat(parsed.sourceVersionValue()).isEqualTo(new BigInteger("9007199254740993"));
    assertThat(parsed.outboxSequenceValue()).isEqualTo(new BigInteger("9007199254740992"));
  }

  @Test
  void closedParserRejectsDuplicateUnknownNullTrailingAndNoncanonicalForms() {
    String json = IssuerGenerationProjection.fromSource(source(1L, Optional.empty())).toJson();
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

  private static AccountAuthoritySourceEventV1Codec.IssuerPreimage eventFields(
      String generation, String version, String sequence) {
    String request = UUID.randomUUID().toString();
    return new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
        request, request, STREAM, sequence, ISSUER, generation, version, "ISSUER_SECURITY_CHANGE");
  }

  private static CanonicalIssuerSourceSnapshot source(
      long generation, Optional<AccountAuthoritySourceEventV1Codec.IssuerEvent> event) {
    var source =
        new CurrentSourceEvidence(
            new AuthorityScope(ScopeKind.ISSUER, ISSUER, null, null),
            generation,
            generation,
            null,
            new SourceCheckpoint(
                STREAM,
                generation - 1L,
                event.map(e -> e.eventId()),
                event.map(e -> e.eventDigest())),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);
    return new CanonicalIssuerSourceSnapshot(
        source,
        event.map(
            e ->
                new Event(
                    STREAM,
                    e.requestId(),
                    generation - 1L,
                    e.eventId(),
                    e.eventDigest(),
                    e.canonicalJsonUtf8())));
  }
}
