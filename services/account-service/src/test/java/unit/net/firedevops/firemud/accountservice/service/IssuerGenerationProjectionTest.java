package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Optional;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerPreimage;
import org.junit.jupiter.api.Test;

class IssuerGenerationProjectionTest {
  @Test
  void ownedAdvancedSourceKeepsCanonicalOriginalBytesAndRejectsCheckpointContradiction() {
    String issuer = "firemud-account-service";
    String request = java.util.UUID.randomUUID().toString();
    String stream = "account:auth-authority:v1:issuer/" + issuer;
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new IssuerPreimage(
                request, request, stream, "1", issuer, "2", "2", "SIGNER_COMPROMISE"));
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

    var nonProducerEvent =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new IssuerPreimage(
                "account-issuer-authority-event-v1:" + request,
                request,
                stream,
                "1",
                issuer,
                "2",
                "2",
                "SIGNER_COMPROMISE"));
    var nonProducerStored =
        new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event(
            stream,
            request,
            1L,
            nonProducerEvent.eventId(),
            nonProducerEvent.eventDigest(),
            nonProducerEvent.canonicalJsonUtf8());
    var nonProducerSource =
        new net.firedevops.firemud.accountservice.repository
            .AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            source.scope(),
            source.generation(),
            source.sourceVersion(),
            source.issuanceFence(),
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                stream,
                1L,
                Optional.of(nonProducerEvent.eventId()),
                Optional.of(nonProducerEvent.eventDigest())),
            source.accountSecurityCutoff(),
            source.initializationProvenance(),
            source.accountSourceNumericId(),
            source.accountUuidProvenance(),
            source.initializationTransactionId(),
            source.accountRepositoryInsertTransactionId());
    assertThatThrownBy(
            () ->
                IssuerGenerationProjection.fromSource(
                    new net.firedevops.firemud.accountservice.repository
                        .AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot(
                        nonProducerSource, Optional.of(nonProducerStored))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactCanonicalEventAndLongBoundaryCountersSurviveOwnerRoundTrip() {
    String issuer = "firemud-account-service";
    String generation = Long.toString(Long.MAX_VALUE);
    String sequence = Long.toString(Long.MAX_VALUE - 1L);
    String stream = "account:auth-authority:v1:issuer/" + issuer;
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new IssuerPreimage(
                "original-request",
                "original-request",
                stream,
                sequence,
                issuer,
                generation,
                generation,
                "SIGNER_COMPROMISE"));
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
