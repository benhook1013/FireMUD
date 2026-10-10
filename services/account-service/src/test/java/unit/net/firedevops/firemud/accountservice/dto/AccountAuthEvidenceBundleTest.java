package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AccountIdentitySource;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AuthoritySourceVersions;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.BundleReference;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OperationIdentity;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OutboxCheckpoint;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OwnerEvaluation;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.TokenIdentity;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.Binding;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;

class AccountAuthEvidenceBundleTest {
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";

  @Test
  void encodesAndReadsOnlyTheClosedCanonicalNonTenantProfile() {
    AccountAuthEvidenceBundle value = AccountAuthEvidenceBundle.fromOwnerEvaluation(evaluation());

    assertThat(value).isEqualTo(AccountAuthEvidenceBundle.parseCanonical(value.canonicalBytes()));
    assertThat(AccountAuthEvidenceBundle.MAX_CANONICAL_BYTES).isEqualTo(8 * 1024);
    assertThat(value.canonicalBytes()).hasSizeLessThanOrEqualTo(8 * 1024);
    assertThat(value.canonicalSha256()).matches("[0-9a-f]{64}");
    assertThat(value.toString()).doesNotContain("account-auth-evidence");
    assertThat(value.fields()).containsEntry("schema", AccountAuthEvidenceBundle.SCHEMA);
    assertThat(value.fields()).containsEntry("issuanceFence", "1");
    assertThat(((Map<?, ?>) value.fields().get("tokenIdentity")).get("tokenGeneration"))
        .isEqualTo("1");
    Map<?, ?> authorityTuple = (Map<?, ?>) value.fields().get("authorityTuple");
    assertThat(authorityTuple.get("issuerAuthGeneration")).isEqualTo("1");
    assertThat(authorityTuple.get("accountAuthorityGeneration")).isEqualTo("1");
    assertThat((Map<?, ?>) value.fields().get("membershipVersion")).isEmpty();
    Map<?, ?> scope = (Map<?, ?>) value.fields().get("scope");
    assertThat(scope.get("kind")).isEqualTo("account");
    assertThat(scope.get("tenantIds")).isEqualTo(List.of());
  }

  @Test
  void canonicalEvidenceCarrierRejectsBytesBeyondTheExistingEnvelopeLimit() {
    assertThatCode(
            () ->
                new Binding(
                    "game-session-account-delegation",
                    "request-1",
                    new byte[32],
                    WORKLOAD,
                    "operation-1",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    1L,
                    new byte[] {'{', '}'},
                    new byte[] {'{', '}'},
                    new byte[AccountAuthEvidenceBundle.MAX_CANONICAL_BYTES]))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> AccountAuthEvidenceBundle.parseCanonical(new byte[8 * 1024 + 1]))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class)
        .hasMessageContaining("byte bound");
    assertThatThrownBy(
            () ->
                new Binding(
                    "game-session-account-delegation",
                    "request-1",
                    new byte[32],
                    WORKLOAD,
                    "operation-1",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    1L,
                    new byte[] {'{', '}'},
                    new byte[] {'{', '}'},
                    new byte[AccountAuthEvidenceBundle.MAX_CANONICAL_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority evidence bundle bytes");
  }

  @Test
  void strictParserRejectsDuplicateTrailingUnknownNullAndNonCanonicalInput() {
    byte[] valid = AccountAuthEvidenceBundle.fromOwnerEvaluation(evaluation()).canonicalBytes();
    String text = new String(valid, StandardCharsets.UTF_8);

    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    "{\"schema\":\"first\",\"schema\":\"second\"}"
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    (text + "{}\n").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    text.replace("\"schema\":", "\"unknown\":null,\"schema\":")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    text.replace(
                            "\"audience\":\"account-service\"",
                            "\"audience\":\"account-service\",\"privateKey\":\"fixture\"")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    (" " + text).getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    text.replace(",\"sourceFence\":\"7\"", "").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
  }

  @Test
  void profileRejectsTenantAuthorityAndNonCanonicalCounterRepresentations() {
    String text =
        new String(
            AccountAuthEvidenceBundle.fromOwnerEvaluation(evaluation()).canonicalBytes(),
            StandardCharsets.UTF_8);
    String withTenantScope =
        text.replace("\"tenantIds\":[]", "\"tenantIds\":[\"" + UUID.randomUUID() + "\"]");
    String numericFence = text.replace("\"issuanceFence\":\"1\"", "\"issuanceFence\":1");
    String numericSourceFence = text.replace("\"sourceFence\":\"7\"", "\"sourceFence\":7");
    String numericTokenGeneration =
        text.replace("\"tokenGeneration\":\"1\"", "\"tokenGeneration\":1");
    String numericIssuerGeneration =
        text.replace("\"issuerAuthGeneration\":\"1\"", "\"issuerAuthGeneration\":1");

    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    withTenantScope.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    numericSourceFence.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    numericFence.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    numericTokenGeneration.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    numericIssuerGeneration.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
  }

  @Test
  void bundleReferenceUsesCanonicalCaptureTransactionAndFenceRanges() {
    assertThatCode(
            () ->
                new BundleReference("1", "18446744073709551615", "9223372036854775807", "12345678"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new BundleReference("1", "18446744073709551616", "1", "12345678"))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(() -> new BundleReference("1", "1", "9223372036854775808", "12345678"))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(() -> new BundleReference("1", "01", "1", "12345678"))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
  }

  @Test
  void checkpointsMustBeExactAndSortedOwnerEvidenceShapes() {
    OwnerEvaluation value = evaluation();
    OwnerEvaluation reversed =
        new OwnerEvaluation(
            value.bundleReference(),
            value.snapshotIdentity(),
            value.evaluationIdentity(),
            value.accountId(),
            value.operation(),
            value.token(),
            value.authorityTuple(),
            value.issuanceFence(),
            value.authoritySourceVersions(),
            value.accountIdentitySource(),
            List.of(value.outboxCheckpoints().get(1), value.outboxCheckpoints().get(0)));

    assertThatThrownBy(() -> AccountAuthEvidenceBundle.fromOwnerEvaluation(reversed))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    assertThatThrownBy(
            () ->
                new OutboxCheckpoint(
                    "account:auth-authority:v1:account/" + value.accountId(),
                    0L,
                    "event",
                    "sha256:" + "a".repeat(64)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
  }

  @Test
  void explicitZeroCheckpointIsEncodedWithoutEventIdentityButIsNotSourceProof() {
    OwnerEvaluation positive = evaluation();
    String accountStream = "account:auth-authority:v1:account/" + positive.accountId();
    OutboxCheckpoint zeroBaseline = new OutboxCheckpoint(accountStream, 0L, null, null);
    OwnerEvaluation zeroShape =
        new OwnerEvaluation(
            positive.bundleReference(),
            positive.snapshotIdentity(),
            positive.evaluationIdentity(),
            positive.accountId(),
            positive.operation(),
            positive.token(),
            positive.authorityTuple(),
            positive.issuanceFence(),
            positive.authoritySourceVersions(),
            positive.accountIdentitySource(),
            List.of(zeroBaseline, positive.outboxCheckpoints().get(1)));

    // These fixtures exercise only closed wire shape; they are not owner rows, event history, or
    // proof that Account initialized and exhaustively retained this stream.
    AccountAuthEvidenceBundle encoded = AccountAuthEvidenceBundle.fromOwnerEvaluation(zeroShape);
    AccountAuthEvidenceBundle decoded =
        AccountAuthEvidenceBundle.parseCanonical(encoded.canonicalBytes());
    List<?> checkpoints = (List<?>) decoded.fields().get("outboxCheckpoints");
    Map<?, ?> encodedZero = (Map<?, ?>) checkpoints.get(0);
    assertThat(encodedZero.size()).isEqualTo(2);
    assertThat(encodedZero.containsKey("sourceEventId")).isFalse();
    assertThat(encodedZero.containsKey("sourceEventDigest")).isFalse();
    assertThat(encodedZero.get("outboxStreamKey")).isEqualTo(accountStream);
    assertThat(encodedZero.get("outboxSequence")).isEqualTo("0");

    String claimedEventAtZero =
        new String(encoded.canonicalBytes(), StandardCharsets.UTF_8)
            .replace(
                "\"outboxSequence\":\"0\"",
                "\"outboxSequence\":\"0\",\"sourceEventId\":\"fabricated\","
                    + "\"sourceEventDigest\":\"sha256:"
                    + "a".repeat(64)
                    + "\"");
    assertThatThrownBy(
            () ->
                AccountAuthEvidenceBundle.parseCanonical(
                    claimedEventAtZero.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
  }

  @Test
  void cutoffIsOptionalShapeOnlyAndMustMatchAccountScopeWhenPresent() {
    OwnerEvaluation base = evaluation();
    Map<String, Object> tupleWithCutoff = new LinkedHashMap<>(base.authorityTuple());
    tupleWithCutoff.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", "1",
            "outboxStreamKey", "account:auth-authority:v1:account/" + base.accountId(),
            "outboxSequence", "1"));
    OwnerEvaluation withCutoff =
        new OwnerEvaluation(
            base.bundleReference(),
            base.snapshotIdentity(),
            base.evaluationIdentity(),
            base.accountId(),
            base.operation(),
            base.token(),
            tupleWithCutoff,
            base.issuanceFence(),
            base.authoritySourceVersions(),
            base.accountIdentitySource(),
            base.outboxCheckpoints());

    assertThat(
            ((Map<?, ?>)
                    AccountAuthEvidenceBundle.fromOwnerEvaluation(withCutoff)
                        .fields()
                        .get("authorityTuple"))
                .containsKey("accountSecurityCutoff"))
        .isTrue();
    Map<String, Object> mismatchedTuple = new LinkedHashMap<>(base.authorityTuple());
    mismatchedTuple.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", "1",
            "outboxStreamKey", "account:auth-authority:v1:account/other",
            "outboxSequence", "1"));
    OwnerEvaluation mismatchedCutoff =
        new OwnerEvaluation(
            base.bundleReference(),
            base.snapshotIdentity(),
            base.evaluationIdentity(),
            base.accountId(),
            base.operation(),
            base.token(),
            mismatchedTuple,
            base.issuanceFence(),
            base.authoritySourceVersions(),
            base.accountIdentitySource(),
            base.outboxCheckpoints());
    assertThatThrownBy(() -> AccountAuthEvidenceBundle.fromOwnerEvaluation(mismatchedCutoff))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);

    Map<String, Object> uncoveredTuple = new LinkedHashMap<>(base.authorityTuple());
    uncoveredTuple.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", "1",
            "outboxStreamKey", "account:auth-authority:v1:account/" + base.accountId(),
            "outboxSequence", "2"));
    OwnerEvaluation uncoveredCutoff =
        new OwnerEvaluation(
            base.bundleReference(),
            base.snapshotIdentity(),
            base.evaluationIdentity(),
            base.accountId(),
            base.operation(),
            base.token(),
            uncoveredTuple,
            base.issuanceFence(),
            base.authoritySourceVersions(),
            base.accountIdentitySource(),
            base.outboxCheckpoints());
    assertThatThrownBy(() -> AccountAuthEvidenceBundle.fromOwnerEvaluation(uncoveredCutoff))
        .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
  }

  private static OwnerEvaluation evaluation() {
    UUID accountId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    String accountStream = "account:auth-authority:v1:account/" + accountId;
    String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
    List<OutboxCheckpoint> checkpoints =
        List.of(
            new OutboxCheckpoint(accountStream, 1L, "account-event-1", "sha256:" + "a".repeat(64)),
            new OutboxCheckpoint(issuerStream, 1L, "issuer-event-1", "sha256:" + "b".repeat(64)));
    return new OwnerEvaluation(
        new BundleReference("1", "18446744073709551615", "7", "12345678"),
        "1".repeat(64),
        "2".repeat(64),
        accountId,
        new OperationIdentity(
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            "3".repeat(64),
            WORKLOAD,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            accountId),
        new TokenIdentity(
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), 1L, 100L, 100L, 220L),
        GameSessionAccountDelegationProfile.authorityTuple(1L, 1L),
        1L,
        new AuthoritySourceVersions(1L, 1L, 1L),
        new AccountIdentitySource(42L, AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT, 42L),
        checkpoints);
  }
}
