package unit.net.firedevops.firemud.accountservice.creatorparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

class CreatorPartyEncodingTest {
  @Test
  void exactEncodingBindsReferencesVersionsAndOriginalCreation() {
    UUID account = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    IndividualCreatorPartySource party = party(partyId, account, "verified-evidence", 1L);
    FreshTenantCreatorEvidence creator = creator(account);
    byte[] original = CreatorPartyEncoding.request(request, creator, party);
    assertThat(CreatorPartyEncoding.request(request, creator, party)).isEqualTo(original);
    assertThat(CreatorPartyEncoding.digest(original)).matches("sha256:[0-9a-f]{64}");
    assertThat(CreatorPartyEncoding.request(request, creator, party(partyId, account, "other", 1L)))
        .isNotEqualTo(original);
    assertThat(
            CreatorPartyEncoding.request(
                request, creator, party(partyId, account, "verified-evidence", 2L)))
        .isNotEqualTo(original);
    assertThat(CreatorPartyEncoding.request(request, creator(account), party))
        .isNotEqualTo(original);
  }

  @Test
  void missingPolicyUnsupportedVersionsAndForeignIndividualDoNotBecomeAuthority() {
    UUID account = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new IndividualCreatorPartySource(
                    partyId,
                    account,
                    VerificationStatus.VERIFIED,
                    1,
                    null,
                    null,
                    "evidence",
                    1L,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> party(partyId, account, "evidence", 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> party(new UUID(0, 0), account, "evidence", 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CreatorPartyEncoding.request(
                    UUID.randomUUID(),
                    creator(account),
                    party(partyId, UUID.randomUUID(), "evidence", 1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new IndividualCreatorPartySource(
                    partyId, account, VerificationStatus.UNSUPPORTED, 1, null, null, null, null, 1)
                .locallyVerified())
        .isFalse();
  }

  private static IndividualCreatorPartySource party(
      UUID party, UUID account, String evidence, long version) {
    return new IndividualCreatorPartySource(
        party,
        account,
        VerificationStatus.VERIFIED,
        1,
        "approved-policy",
        1L,
        evidence,
        version,
        1);
  }

  private static FreshTenantCreatorEvidence creator(UUID account) {
    UUID request = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    String digest = "sha256:" + "1".repeat(64);
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            "firemud-test",
            request,
            operation,
            digest,
            tenant,
            1,
            "game-key",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "firemud-test", request, operation, digest, tenant, 1, "game-key", "NEW_GAME_ROW"));
    UUID authorization = UUID.randomUUID();
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        account,
        authorization,
        digest,
        FreshTenantCreatorDigest.evidenceDigest(1, creation, account, authorization, digest));
  }
}
