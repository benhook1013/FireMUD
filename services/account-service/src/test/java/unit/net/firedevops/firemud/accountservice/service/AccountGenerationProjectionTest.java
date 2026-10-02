package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;

class AccountGenerationProjectionTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");
  private static final String ACCOUNT_TEXT = ACCOUNT_ID.toString();
  private static final String STREAM_KEY = "account:auth-authority:v1:account/" + ACCOUNT_TEXT;

  @Test
  void emitsExactSequenceZeroBaselineWithoutAnEvent() {
    AccountGenerationProjection projection =
        projection(ACCOUNT_TEXT, "1", "1", "0", Optional.empty());

    assertThat(projection.key()).isEqualTo("session:auth:generation:account:" + ACCOUNT_TEXT);
    assertThat(projection.toJson())
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-account-generation-projection/v1\","
                + "\"accountId\":\""
                + ACCOUNT_TEXT
                + "\",\"accountAuthorityGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"outboxStreamKey\":\""
                + STREAM_KEY
                + "\",\"outboxSequence\":\"0\"}");
    assertThat(AccountGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
  }

  @Test
  void preservesCanonicalClosedPasswordResetAndLogoutAllEventsAsStrings() {
    AccountGenerationProjection resetProjection =
        projection(ACCOUNT_TEXT, "4", "5", "3", Optional.of(resetEvent("4", "5", "3")));
    AccountGenerationProjection logoutProjection =
        projection(ACCOUNT_TEXT, "6", "8", "5", Optional.of(logoutEvent("6", "8", "5")));

    assertThat(AccountGenerationProjection.parse(resetProjection.toJson()))
        .isEqualTo(resetProjection);
    assertThat(AccountGenerationProjection.parse(logoutProjection.toJson()))
        .isEqualTo(logoutProjection);
    assertThat(resetProjection.sourceEvent()).contains(resetEvent("4", "5", "3"));
    assertThat(logoutProjection.sourceEvent()).contains(logoutEvent("6", "8", "5"));
  }

  @Test
  void acceptsLargeCanonicalCountersWithoutLongConversion() {
    String large = "922337203685477580812345678901234567890";
    AccountGenerationProjection projection =
        projection(
            ACCOUNT_TEXT, large, large, large, Optional.of(logoutEvent(large, large, large)));

    assertThat(projection.generationValue()).isEqualTo(new BigInteger(large));
    assertThat(projection.sourceVersionValue()).isEqualTo(new BigInteger(large));
    assertThat(projection.outboxSequenceValue()).isEqualTo(new BigInteger(large));
    assertThat(AccountGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
  }

  @Test
  void closesTheProjectionShapeAndRejectsDuplicateUnknownNullAndNoncanonicalEvidence() {
    String baseline = projection(ACCOUNT_TEXT, "1", "1", "0", Optional.empty()).toJson();
    String duplicateAccountId =
        baseline.replace(
            "\"accountId\":\"" + ACCOUNT_TEXT + "\"",
            "\"accountId\":\"" + ACCOUNT_TEXT + "\",\"accountId\":\"" + ACCOUNT_TEXT + "\"");
    String unknownField =
        baseline.substring(0, baseline.length() - 1) + ",\"issuanceFence\":\"1\"}";
    String nullEvent = baseline.substring(0, baseline.length() - 1) + ",\"sourceEvent\":null}";
    String noncanonicalSequence =
        baseline.replace("\"outboxSequence\":\"0\"", "\"outboxSequence\":\"00\"");
    String reordered =
        baseline.replace(
            "\"schemaVersion\":\"account-auth-account-generation-projection/v1\","
                + "\"accountId\":\""
                + ACCOUNT_TEXT
                + "\",",
            "\"accountId\":\""
                + ACCOUNT_TEXT
                + "\",\"schemaVersion\":\"account-auth-account-generation-projection/v1\",");

    assertThatThrownBy(() -> AccountGenerationProjection.parse(duplicateAccountId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountGenerationProjection.parse(unknownField))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountGenerationProjection.parse(nullEvent))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountGenerationProjection.parse(noncanonicalSequence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountGenerationProjection.parse(reordered))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedEventCheckpointAndInvalidSequenceZeroRepresentations() {
    String event = logoutEvent("2", "2", "1");

    assertThatThrownBy(() -> projection(ACCOUNT_TEXT, "3", "2", "1", Optional.of(event)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("declared closed schemas")
        .cause()
        .hasMessageContaining("does not match");
    assertThatThrownBy(() -> projection(ACCOUNT_TEXT, "2", "2", "0", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence zero");
    assertThatThrownBy(() -> projection(ACCOUNT_TEXT, "2", "2", "1", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires advanced counters");
  }

  @Test
  void localIssuanceFenceIsNotSerializedOrComparedAsProjectionEvidence() {
    AccountGenerationProjection first =
        AccountGenerationProjection.fromSource(source(1L, 1L, 0L, Optional.empty(), 4L));
    AccountGenerationProjection withNewerLocalFence =
        AccountGenerationProjection.fromSource(source(1L, 1L, 0L, Optional.empty(), 99L));

    assertThat(withNewerLocalFence).isEqualTo(first);
    assertThat(first.toJson()).doesNotContain("issuanceFence");
  }

  private static AccountGenerationProjection projection(
      String accountId,
      String generation,
      String sourceVersion,
      String sequence,
      Optional<String> sourceEvent) {
    return new AccountGenerationProjection(
        accountId, generation, sourceVersion, STREAM_KEY, sequence, sourceEvent);
  }

  private static String resetEvent(String generation, String sourceVersion, String sequence) {
    var evidence =
        PasswordResetAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "account-password-reset-event-v1:sample"),
                Map.entry("requestId", "account-password-reset-request-v1:sample"),
                Map.entry("accountId", ACCOUNT_TEXT),
                Map.entry("sourceScope", "account/" + ACCOUNT_TEXT),
                Map.entry("outboxStreamKey", STREAM_KEY),
                Map.entry("outboxSequence", sequence),
                Map.entry("accountAuthorityGeneration", generation),
                Map.entry("sourceVersion", sourceVersion),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", generation,
                        "outboxStreamKey", STREAM_KEY,
                        "outboxSequence", sequence))));
    return new String(evidence.canonicalJsonUtf8(), StandardCharsets.UTF_8);
  }

  private static String logoutEvent(String generation, String sourceVersion, String sequence) {
    String requestId = "11111111-1111-4111-8111-111111111111";
    var evidence =
        AccountLogoutAllAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId", AccountLogoutAllAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId),
                Map.entry("requestId", requestId),
                Map.entry("accountId", ACCOUNT_TEXT),
                Map.entry("sourceScope", "account/" + ACCOUNT_TEXT),
                Map.entry("outboxStreamKey", STREAM_KEY),
                Map.entry("outboxSequence", sequence),
                Map.entry("accountAuthorityGeneration", generation),
                Map.entry("sourceVersion", sourceVersion),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", generation,
                        "outboxStreamKey", STREAM_KEY,
                        "outboxSequence", sequence))));
    return new String(evidence.canonicalJsonUtf8(), StandardCharsets.UTF_8);
  }

  private static AccountSourceSnapshot source(
      long generation, long sourceVersion, long sequence, Optional<Event> event, long fenceValue) {
    return new AccountSourceSnapshot(
        ACCOUNT_ID,
        new ScopeState(
            AuthorityScope.account(ACCOUNT_ID),
            generation,
            sourceVersion,
            new IssuanceFence(ACCOUNT_ID, fenceValue, 1L)),
        STREAM_KEY,
        sequence,
        event);
  }
}
