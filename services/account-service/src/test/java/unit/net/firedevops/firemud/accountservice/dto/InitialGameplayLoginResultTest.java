package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AccountIdentitySource;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AuthoritySourceVersions;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.BundleReference;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OperationIdentity;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OutboxCheckpoint;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OwnerEvaluation;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.TokenIdentity;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;

class InitialGameplayLoginResultTest {
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CONTEXT_ID = UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID TOKEN_JTI = UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308");
  private static final Instant ISSUED_AT = Instant.parse("2027-01-15T12:00:00Z");
  private static final byte[] EXACT_JWT =
      "header.payload.signature".getBytes(StandardCharsets.US_ASCII);

  @Test
  void projectsExactRecoveredCredentialAndDistinctCanonicalAuthorityEvidence() {
    AccountAuthEvidenceBundle bundle = evidenceBundle();
    InitialGameplayLoginResult result =
        InitialGameplayLoginResult.fromRecoveredCredential(credential(bundle));

    assertThat(result.compactJwtBytes()).containsExactly(EXACT_JWT);
    assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(result.tokenJti()).isEqualTo(TOKEN_JTI);
    assertThat(result.tokenSha256()).isEqualTo("a".repeat(64));
    assertThat(result.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertThat(result.issuedAtEpochSecond()).isEqualTo(ISSUED_AT.getEpochSecond());
    assertThat(result.notBeforeEpochSecond()).isEqualTo(ISSUED_AT.getEpochSecond());
    assertThat(result.expiresAtEpochSecond())
        .isEqualTo(
            ISSUED_AT.getEpochSecond()
                + GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS);
    assertThat(result.tokenGeneration()).isEqualTo("1");
    assertThat(result.issuanceFence()).isEqualTo("7");
    assertThat(result.requestId()).isEqualTo(REQUEST_ID);
    assertThat(result.callerContextId()).isEqualTo(CONTEXT_ID);

    String checkpoints =
        new String(result.outboxCheckpointsCanonicalJson(), StandardCharsets.UTF_8);
    assertThat(checkpoints)
        .contains("account:auth-authority:v1:account/" + ACCOUNT_ID)
        .contains("\"outboxSequence\":\"0\"")
        .contains("account:auth-authority:v1:issuer/firemud-account-service")
        .contains("\"outboxSequence\":\"1\"");
    String sourceEvents =
        new String(result.outboxSourceEventEvidenceCanonicalJson(), StandardCharsets.UTF_8);
    assertThat(sourceEvents)
        .contains("issuer-event-1")
        .contains("sha256:" + "b".repeat(64))
        .doesNotContain("\"outboxSequence\":\"0\"")
        .doesNotContain("account:auth-authority:v1:account/" + ACCOUNT_ID);
    assertThat(new String(result.authorityTupleCanonicalJson(), StandardCharsets.UTF_8))
        .contains("accountAuthorityGeneration");
    assertThat(result.toString()).doesNotContain(new String(EXACT_JWT, StandardCharsets.US_ASCII));
  }

  @Test
  void credentialAndEvidenceBytesAreDefensiveCopies() {
    InitialGameplayLoginResult result =
        InitialGameplayLoginResult.fromRecoveredCredential(credential(evidenceBundle()));
    byte[] jwt = result.compactJwtBytes();
    byte[] checkpoints = result.outboxCheckpointsCanonicalJson();
    byte[] authority = result.authorityTupleCanonicalJson();
    jwt[0] = 'x';
    checkpoints[0] = 'x';
    authority[0] = 'x';

    assertThat(result.compactJwtBytes()).containsExactly(EXACT_JWT);
    assertThat(result.outboxCheckpointsCanonicalJson()[0]).isNotEqualTo((byte) 'x');
    assertThat(result.authorityTupleCanonicalJson()[0]).isNotEqualTo((byte) 'x');
  }

  @Test
  void rejectsRecoveredCredentialThatContradictsStoredEvidence() {
    AccountAuthEvidenceBundle bundle = evidenceBundle();
    assertThatThrownBy(
            () ->
                InitialGameplayLoginResult.fromRecoveredCredential(
                    credential(
                        bundle, UUID.randomUUID(), TOKEN_JTI, "a".repeat(64), expectedExpiry())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Recovered gameplay LOGIN metadata is inconsistent");
    assertThatThrownBy(
            () ->
                InitialGameplayLoginResult.fromRecoveredCredential(
                    credential(
                        bundle, ACCOUNT_ID, UUID.randomUUID(), "a".repeat(64), expectedExpiry())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Recovered gameplay LOGIN metadata is inconsistent");
    assertThatThrownBy(
            () ->
                InitialGameplayLoginResult.fromRecoveredCredential(
                    credential(
                        bundle, ACCOUNT_ID, TOKEN_JTI, "a".repeat(64), expectedExpiry() + 1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Recovered gameplay LOGIN metadata is inconsistent");
    assertThatThrownBy(
            () ->
                InitialGameplayLoginResult.fromRecoveredCredential(
                    credential(bundle, ACCOUNT_ID, TOKEN_JTI, "not-a-hash", expectedExpiry())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Recovered gameplay LOGIN metadata is inconsistent");
  }

  private static RecoveredCredential credential(AccountAuthEvidenceBundle bundle) {
    return credential(bundle, ACCOUNT_ID, TOKEN_JTI, "a".repeat(64), expectedExpiry());
  }

  private static RecoveredCredential credential(
      AccountAuthEvidenceBundle bundle,
      UUID accountId,
      UUID tokenJti,
      String tokenHash,
      long expiresAt) {
    RecoveredCredential credential = mock(RecoveredCredential.class);
    when(credential.compactJwtBytes()).thenAnswer(invocation -> EXACT_JWT.clone());
    when(credential.accountId()).thenReturn(accountId);
    when(credential.tokenJti()).thenReturn(tokenJti);
    when(credential.tokenSha256()).thenReturn(tokenHash);
    when(credential.profile()).thenReturn(GameSessionAccountDelegationProfile.PROFILE);
    when(credential.expiresAtEpochSecond()).thenReturn(expiresAt);
    when(credential.authEvidenceBundle()).thenReturn(bundle);
    return credential;
  }

  private static long expectedExpiry() {
    return ISSUED_AT.getEpochSecond()
        + GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS;
  }

  private static AccountAuthEvidenceBundle evidenceBundle() {
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
    return AccountAuthEvidenceBundle.fromOwnerEvaluation(
        new OwnerEvaluation(
            new BundleReference("1", "1", "7", "12345678"),
            "1".repeat(64),
            "2".repeat(64),
            ACCOUNT_ID,
            new OperationIdentity(
                UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0"),
                REQUEST_ID,
                "3".repeat(64),
                WORKLOAD,
                CONTEXT_ID,
                ACCOUNT_ID),
            new TokenIdentity(
                TOKEN_JTI,
                1L,
                ISSUED_AT.getEpochSecond(),
                ISSUED_AT.getEpochSecond(),
                ISSUED_AT.getEpochSecond()
                    + GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS),
            GameSessionAccountDelegationProfile.authorityTuple(1L, 1L),
            7L,
            new AuthoritySourceVersions(1L, 1L, 1L),
            new AccountIdentitySource(42L, AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT, 42L),
            List.of(
                new OutboxCheckpoint(accountStream, 0L, null, null),
                new OutboxCheckpoint(
                    issuerStream, 1L, "issuer-event-1", "sha256:" + "b".repeat(64)))));
  }
}
