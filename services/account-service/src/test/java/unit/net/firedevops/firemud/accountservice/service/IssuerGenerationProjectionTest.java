package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;

class IssuerGenerationProjectionTest {
  @Test
  void ownedAdvancedSourceKeepsCanonicalOriginalBytesAndRejectsCheckpointContradiction() {
    String issuer = "firemud-account-service";
    String request = java.util.UUID.randomUUID().toString();
    String stream = "account:auth-authority:v1:issuer/" + issuer;
    var event =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "account-issuer-authority-event-v1:" + request),
                Map.entry("requestId", request),
                Map.entry("issuerId", issuer),
                Map.entry("sourceScope", "issuer/" + issuer),
                Map.entry("outboxStreamKey", stream),
                Map.entry("outboxSequence", "1"),
                Map.entry("issuerAuthGeneration", "2"),
                Map.entry("sourceVersion", "2")));
    var source =
        new net.firedevops.firemud.accountservice.repository
            .AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                .AuthorityScope.issuer(issuer),
            2L,
            2L,
            null,
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                stream, 1L, Optional.of(event.eventId()), Optional.of(event.eventDigest())),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            7L,
            null);
    var stored =
        new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event(
            stream, request, 1L, event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
    var snapshot =
        new net.firedevops.firemud.accountservice.repository
            .AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot(
            source, Optional.of(stored));
    assertThat(IssuerGenerationProjection.fromSource(snapshot).sourceEvent())
        .contains(event.canonicalJson());
    assertThatThrownBy(() -> IssuerGenerationProjection.fromSource(source))
        .isInstanceOf(IllegalArgumentException.class);
    var contradictory =
        new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event(
            stream,
            request,
            1L,
            event.eventId(),
            "sha256:" + "0".repeat(64),
            event.canonicalJsonUtf8());
    assertThatThrownBy(
            () ->
                IssuerGenerationProjection.fromSource(
                    new net.firedevops.firemud.accountservice.repository
                        .AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot(
                        source, Optional.of(contradictory))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactCanonicalEventAndArbitraryPrecisionCountersSurviveOwnerRoundTrip() {
    String issuer = "firemud-account-service";
    String generation = "92233720368547758081234567890";
    String sequence = "92233720368547758081234567889";
    String stream = "account:auth-authority:v1:issuer/" + issuer;
    var event =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "canonical-issuer-event"),
                Map.entry("requestId", "original-request"),
                Map.entry("issuerId", issuer),
                Map.entry("sourceScope", "issuer/" + issuer),
                Map.entry("outboxStreamKey", stream),
                Map.entry("outboxSequence", sequence),
                Map.entry("issuerAuthGeneration", generation),
                Map.entry("sourceVersion", generation)));
    var projection =
        new IssuerGenerationProjection(
            issuer,
            generation,
            generation,
            stream,
            sequence,
            Optional.of(event.eventId()),
            Optional.of(event.eventDigest()),
            Optional.of(event.canonicalJson()));
    var parsed = IssuerGenerationProjection.parse(projection.toJson());
    assertThat(parsed).isEqualTo(projection);
    assertThat(new BigInteger(parsed.issuerAuthGeneration())).isEqualTo(new BigInteger(generation));
    assertThatThrownBy(
            () ->
                IssuerGenerationProjection.parse(
                    projection.toJson().replace(event.eventDigest(), "sha256:" + "0".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                IssuerGenerationProjection.parse(
                    projection
                        .toJson()
                        .replace("lastAppliedSourceOutboxSequence", "outboxSequence")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
