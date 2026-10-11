package unit.net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GameSessionAccountDelegationRegistryRecordTest {
  private static final String ACCOUNT_ID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final String JTI = "2921ba03-bf74-49ac-b24b-a9255f5de308";
  private static final String OPERATION_ID = "5414e55d-0393-4561-ac3d-cb916a08d3f0";
  private static final String REQUEST_ID = "1ee95a1e-83f2-4a63-a7ba-6288e246ac76";
  private static final long NOW = 1_800_000_000L;
  private static final long MAX_RECORD_BYTES = 16_384L;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void boundRegistryRoundTripsEqualValuesInIndependentAccountAndTenantUuidDomains()
      throws Exception {
    var claims = new LinkedHashMap<>(claims());
    var tuple = new LinkedHashMap<>(authorityTuple(7L, 11L, 10L));
    tuple.put("tenantAuthorityGeneration", Map.of(ACCOUNT_ID, "5"));
    tuple.put("membershipAuthorityGeneration", Map.of(ACCOUNT_ID, "7"));
    claims.put("tenantId", ACCOUNT_ID);
    claims.put("authorityTuple", tuple);
    claims.put("membershipVersion", Map.of(ACCOUNT_ID, "3"));
    String compact = compact(claims);
    var bound =
        GameSessionAccountDelegationRegistryRecord.fromAccountSignedBoundCompactJwt(
            compact,
            "5",
            binding(),
            snapshot(),
            bundleReference(),
            ACCOUNT_ID,
            tuple,
            Map.of(ACCOUNT_ID, "3"),
            NOW,
            MAX_RECORD_BYTES);
    var decoded =
        GameSessionAccountDelegationRegistryRecord.decode(
            bound.toCanonicalJsonBytes(MAX_RECORD_BYTES), MAX_RECORD_BYTES);
    assertThat(decoded.fields())
        .containsEntry("accountId", ACCOUNT_ID)
        .containsEntry("tenantId", ACCOUNT_ID);
    assertThat(decoded.fields().get("membershipVersion")).isEqualTo(Map.of(ACCOUNT_ID, "3"));
    assertThatThrownBy(() -> record(compact))
        .isInstanceOf(GameSessionAccountDelegationRegistryRecord.InvalidRecordException.class);
    assertThatThrownBy(
            () ->
                GameSessionAccountDelegationRegistryRecord.fromAccountSignedBoundCompactJwt(
                    compact,
                    "5",
                    binding(),
                    snapshot(),
                    bundleReference(),
                    "22222222-2222-4222-8222-222222222222",
                    tuple,
                    Map.of(ACCOUNT_ID, "3"),
                    NOW,
                    MAX_RECORD_BYTES))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
  }

  @Test
  void buildsStrictPendingRecordForTheMinimalCredentialLoginProfile() throws Exception {
    String compact = compact(claims());
    GameSessionAccountDelegationRegistryRecord record = record(compact);

    assertThat(record.state()).isEqualTo("pending");
    assertThat(record.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(record.jti()).isEqualTo(JTI);
    assertThat(record.operationId()).isEqualTo(OPERATION_ID);
    assertThat(record.requestId()).isEqualTo(REQUEST_ID);
    assertThat(record.issuanceFence()).isEqualTo(11L);
    assertThat(record.evidenceBundleReference()).isEqualTo(bundleReference());
    byte[] encoded = record.toCanonicalJsonBytes(MAX_RECORD_BYTES);
    assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain(compact);
    assertThat(
            GameSessionAccountDelegationRegistryRecord.decode(encoded, MAX_RECORD_BYTES).fields())
        .containsEntry("state", "pending")
        .containsEntry("tokenHash", sha256(compact));
  }

  @Test
  void roundTripsSmallAndLargestOwnerLongAuthorityCountersAsDecimalStrings() throws Exception {
    long large = Long.MAX_VALUE;
    AccountAuthoritySnapshot largeSnapshot =
        new AccountAuthoritySnapshot(
            UUID.fromString(ACCOUNT_ID),
            large,
            large,
            large,
            large,
            large,
            large,
            Optional.of(cutoff(large, large - 1L)));
    Map<String, Object> largeClaims = new LinkedHashMap<>(claims());
    largeClaims.put("authorityTuple", authorityTuple(large, large, large - 1L));
    largeClaims.put("issuanceFence", Long.toString(large));
    String compact = compact(largeClaims);
    GameSessionAccountDelegationRegistryRecord decoded =
        GameSessionAccountDelegationRegistryRecord.decode(
            GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                    compact,
                    "5",
                    binding(),
                    largeSnapshot,
                    bundleReference(),
                    NOW,
                    MAX_RECORD_BYTES)
                .toCanonicalJsonBytes(MAX_RECORD_BYTES),
            MAX_RECORD_BYTES);

    assertThat(decoded.issuanceFence()).isEqualTo(large);
    Map<?, ?> authorityTuple = (Map<?, ?>) decoded.fields().get("authorityTuple");
    assertThat(decoded.fields().get("tokenGeneration")).isEqualTo("1");
    assertThat(decoded.fields().get("issuanceFence")).isEqualTo(Long.toString(large));
    assertThat(authorityTuple.get("issuerAuthGeneration")).isEqualTo(Long.toString(large));
    assertThat(authorityTuple.get("accountAuthorityGeneration")).isEqualTo(Long.toString(large));
    Map<?, ?> sourceVersions = (Map<?, ?>) decoded.fields().get("authoritySourceVersions");
    assertThat(sourceVersions.get("issuerSourceVersion")).isEqualTo(Long.toString(large));
    assertThat(sourceVersions.get("accountSourceVersion")).isEqualTo(Long.toString(large));
    assertThat(sourceVersions.get("issuanceFenceSourceVersion")).isEqualTo(Long.toString(large));
    assertThat(record(compact(claims())).issuanceFence()).isEqualTo(11L);
  }

  @Test
  void rejectsWrongAudienceTenantScopedOrExpandedClaimCandidates() throws Exception {
    Map<String, Object> wrongAudience = new LinkedHashMap<>(claims());
    wrongAudience.put("aud", "social-groups-service");
    assertInvalid(wrongAudience);

    Map<String, Object> tenantScoped = new LinkedHashMap<>(claims());
    Map<String, Object> tuple = new LinkedHashMap<>();
    ((Map<?, ?>) tenantScoped.get("authorityTuple"))
        .forEach((key, value) -> tuple.put((String) key, value));
    tuple.put("tenantAuthorityGeneration", Map.of("tenant", 1L));
    tenantScoped.put("authorityTuple", tuple);
    assertInvalid(tenantScoped);

    Map<String, Object> expanded = new LinkedHashMap<>(claims());
    expanded.put("backendJWT", "forbidden");
    assertInvalid(expanded);
  }

  @Test
  void advancedAccountRequiresTheExactCurrentCutoffAndSequenceZeroOmitsIt() throws Exception {
    Map<String, Object> missingCutoff = new LinkedHashMap<>(claims());
    Map<String, Object> missingCutoffTuple = new LinkedHashMap<>();
    ((Map<?, ?>) claims().get("authorityTuple"))
        .forEach((key, value) -> missingCutoffTuple.put((String) key, value));
    missingCutoffTuple.remove("accountSecurityCutoff");
    missingCutoff.put("authorityTuple", missingCutoffTuple);
    assertInvalid(missingCutoff);

    Map<String, Object> wrongCutoff = new LinkedHashMap<>(claims());
    Map<String, Object> wrongTuple = new LinkedHashMap<>();
    ((Map<?, ?>) wrongCutoff.get("authorityTuple"))
        .forEach((key, value) -> wrongTuple.put((String) key, value));
    wrongTuple.put("accountSecurityCutoff", cutoff(11L, 9L).toMap());
    wrongCutoff.put("authorityTuple", wrongTuple);
    assertInvalid(wrongCutoff);

    Map<String, Object> baseline = new LinkedHashMap<>(claims());
    baseline.put("authorityTuple", GameSessionAccountDelegationProfile.authorityTuple(1L, 1L));
    baseline.put("issuanceFence", "1");
    GameSessionAccountDelegationRegistryRecord baselineRecord =
        GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
            compact(baseline),
            "5",
            binding(),
            baselineSnapshot(),
            bundleReference(),
            NOW,
            MAX_RECORD_BYTES);
    assertThat(
            ((Map<?, ?>) baselineRecord.fields().get("authorityTuple"))
                .containsKey("accountSecurityCutoff"))
        .isFalse();

    Map<String, Object> fabricatedBaseline = new LinkedHashMap<>(baseline);
    Map<String, Object> fabricatedTuple = new LinkedHashMap<>();
    ((Map<?, ?>) baseline.get("authorityTuple"))
        .forEach((key, value) -> fabricatedTuple.put((String) key, value));
    fabricatedTuple.put("accountSecurityCutoff", cutoff(1L, 1L).toMap());
    fabricatedBaseline.put("authorityTuple", fabricatedTuple);
    assertThatThrownBy(
            () ->
                GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                    compact(fabricatedBaseline),
                    "5",
                    binding(),
                    baselineSnapshot(),
                    bundleReference(),
                    NOW,
                    MAX_RECORD_BYTES))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesShortLifetimeCanonicalJwtAndExactRequestIdentity() throws Exception {
    Map<String, Object> tooLong = new LinkedHashMap<>(claims());
    tooLong.put("exp", NOW + 301L);
    assertInvalid(tooLong);

    String compact = compact(claims());
    assertThatThrownBy(
            () ->
                GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                    compact + "=",
                    "5",
                    binding(),
                    snapshot(),
                    bundleReference(),
                    NOW,
                    MAX_RECORD_BYTES))
        .isInstanceOf(IllegalArgumentException.class);

    GameSessionAccountDelegationRegistryRecord record = record(compact);
    byte[] bytes = record.toCanonicalJsonBytes(MAX_RECORD_BYTES);
    String nonCanonical = "{ \"state\": \"pending\" }";
    assertThatThrownBy(
            () ->
                GameSessionAccountDelegationRegistryRecord.decode(
                    nonCanonical.getBytes(StandardCharsets.UTF_8), MAX_RECORD_BYTES))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(bytes).isNotEmpty();
  }

  @Test
  void requiresDecimalCountersAndCompleteFiveFieldBundleReference() throws Exception {
    Map<String, Object> numericGeneration = new LinkedHashMap<>(claims());
    numericGeneration.put("tokenGeneration", 1L);
    assertInvalid(numericGeneration);

    Map<String, Object> numericFence = new LinkedHashMap<>(claims());
    numericFence.put("issuanceFence", 11L);
    assertInvalid(numericFence);

    Map<String, Object> numericAuthority = new LinkedHashMap<>(claims());
    Map<String, Object> tuple = new LinkedHashMap<>();
    ((Map<?, ?>) numericAuthority.get("authorityTuple"))
        .forEach((key, value) -> tuple.put((String) key, value));
    tuple.put("issuerAuthGeneration", 7L);
    numericAuthority.put("authorityTuple", tuple);
    assertInvalid(numericAuthority);

    Map<String, Object> leadingZeroGeneration = new LinkedHashMap<>(claims());
    Map<String, Object> leadingZeroTuple = new LinkedHashMap<>();
    ((Map<?, ?>) leadingZeroGeneration.get("authorityTuple"))
        .forEach((key, value) -> leadingZeroTuple.put((String) key, value));
    leadingZeroTuple.put("accountAuthorityGeneration", "011");
    leadingZeroGeneration.put("authorityTuple", leadingZeroTuple);
    assertInvalid(leadingZeroGeneration);

    Map<String, Object> overflowFence = new LinkedHashMap<>(claims());
    overflowFence.put("issuanceFence", "9223372036854775808");
    assertInvalid(overflowFence);

    GameSessionAccountDelegationRegistryRecord record = record(compact(claims()));
    Map<?, ?> reference = (Map<?, ?>) record.fields().get("authEvidenceBundle");
    assertThat(reference.keySet())
        .isEqualTo(
            Set.of(
                "bundleVersion",
                "sourceVersion",
                "sourceFence",
                "linearization",
                "canonicalSha256"));
    assertThat(reference.get("sourceFence")).isEqualTo("9");

    Map<String, Object> incompleteFields = new LinkedHashMap<>(record.fields());
    Map<String, Object> incompleteReference = new LinkedHashMap<>();
    reference.forEach((key, value) -> incompleteReference.put((String) key, value));
    incompleteReference.remove("sourceFence");
    incompleteFields.put("authEvidenceBundle", incompleteReference);
    byte[] incompleteBytes =
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(incompleteFields));
    assertThatThrownBy(
            () ->
                GameSessionAccountDelegationRegistryRecord.decode(
                    incompleteBytes, MAX_RECORD_BYTES))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalid(Map<String, Object> claims) throws Exception {
    assertThatThrownBy(() -> record(compact(claims))).isInstanceOf(IllegalArgumentException.class);
  }

  private static GameSessionAccountDelegationRegistryRecord record(String compact) {
    return GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
        compact, "5", binding(), snapshot(), bundleReference(), NOW, MAX_RECORD_BYTES);
  }

  private static Map<String, Object> claims() {
    return Map.ofEntries(
        Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
        Map.entry("sub", ACCOUNT_ID),
        Map.entry("accountId", ACCOUNT_ID),
        Map.entry("jti", JTI),
        Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
        Map.entry("iat", NOW),
        Map.entry("nbf", NOW),
        Map.entry("exp", NOW + 120L),
        Map.entry("tokenGeneration", "1"),
        Map.entry("authorityTuple", authorityTuple(7L, 11L, 10L)),
        Map.entry("membershipVersion", Map.of()),
        Map.entry("issuanceFence", "11"));
  }

  private static String compact(Map<String, Object> claims) throws Exception {
    String header =
        JSON.writeValueAsString(Map.of("alg", "RS256", "kid", "test-key", "typ", "JWT"));
    String payload = JSON.writeValueAsString(claims);
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString("signature-bytes".getBytes(StandardCharsets.UTF_8));
  }

  private static IssuanceBinding binding() {
    return new IssuanceBinding(OPERATION_ID, REQUEST_ID, "a".repeat(64), ACCOUNT_ID);
  }

  private static EvidenceBundleReference bundleReference() {
    return new EvidenceBundleReference("1", "11", "9", "3", "d".repeat(64));
  }

  private static AccountAuthoritySnapshot snapshot() {
    return new AccountAuthoritySnapshot(
        UUID.fromString(ACCOUNT_ID), 7L, 7L, 11L, 11L, 11L, 11L, Optional.of(cutoff(11L, 10L)));
  }

  private static AccountAuthoritySnapshot baselineSnapshot() {
    return new AccountAuthoritySnapshot(
        UUID.fromString(ACCOUNT_ID), 1L, 1L, 1L, 1L, 1L, 1L, Optional.empty());
  }

  private static Map<String, Object> authorityTuple(
      long issuerGeneration, long accountGeneration, long cutoffSequence) {
    return GameSessionAccountDelegationProfile.authorityTuple(
        issuerGeneration,
        accountGeneration,
        Optional.of(cutoff(accountGeneration, cutoffSequence)));
  }

  private static AccountSecurityCutoff cutoff(long generation, long sequence) {
    return new AccountSecurityCutoff(
        Long.toString(generation),
        "account:auth-authority:v1:account/" + ACCOUNT_ID,
        Long.toString(sequence));
  }

  private static String sha256(String value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
  }
}
