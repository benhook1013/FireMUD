package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;

class AccountGameplayDelegationAuthorityProjectionTest {
  @Test
  void canonicalProjectionPairMatchesDonorSequenceZeroGoldenVector() {
    UUID accountId = UUID.fromString("b4ec84c2-50c8-4cec-8d92-a8603d1c53f0");
    IssuerAccountSourceSnapshot source = sequenceZeroSnapshot(accountId);

    byte[][] actual = AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);

    assertThat(new String(actual[0], StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-issuer-generation-projection/v1\","
                + "\"issuerId\":\""
                + GameSessionAccountDelegationProfile.ISSUER
                + "\",\"issuerAuthGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"outboxStreamKey\":\"account:auth-authority:v1:issuer/"
                + GameSessionAccountDelegationProfile.ISSUER
                + "\",\"lastAppliedSourceOutboxSequence\":\"0\"}");
    assertThat(new String(actual[1], StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-account-generation-projection/v1\","
                + "\"accountId\":\""
                + accountId
                + "\",\"accountAuthorityGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"outboxStreamKey\":\"account:auth-authority:v1:account/"
                + accountId
                + "\",\"outboxSequence\":\"0\"}");
  }

  @Test
  void canonicalProjectionPairRejectsMissingAccountProjection() {
    UUID accountId = UUID.fromString("b4ec84c2-50c8-4cec-8d92-a8603d1c53f0");
    IssuerAccountSourceSnapshot valid = sequenceZeroSnapshot(accountId);
    IssuerAccountSourceSnapshot missingAccountProjection =
        new IssuerAccountSourceSnapshot(
            valid.issuer(),
            valid.account(),
            valid.issuanceFence(),
            null,
            valid.canonicalIssuerProjection());

    assertThatThrownBy(
            () ->
                AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(
                    missingAccountProjection))
        .isInstanceOf(
            AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
  }

  private static IssuerAccountSourceSnapshot sequenceZeroSnapshot(UUID accountId) {
    String issuerId = GameSessionAccountDelegationProfile.ISSUER;
    CurrentSourceEvidence issuer =
        new CurrentSourceEvidence(
            AuthorityScope.issuer(issuerId),
            1L,
            1L,
            null,
            new SourceCheckpoint(
                "account:auth-authority:v1:issuer/" + issuerId,
                0L,
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            10L,
            null);
    var fence = new IssuanceFence(accountId, 1L, 1L);
    CurrentSourceEvidence account =
        new CurrentSourceEvidence(
            AuthorityScope.account(accountId),
            1L,
            1L,
            fence,
            new SourceCheckpoint(
                "account:auth-authority:v1:account/" + accountId,
                0L,
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            "ACCOUNT_REPOSITORY_INSERT",
            42L,
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name(),
            20L,
            20L);
    return new IssuerAccountSourceSnapshot(issuer, account, fence);
  }
}
