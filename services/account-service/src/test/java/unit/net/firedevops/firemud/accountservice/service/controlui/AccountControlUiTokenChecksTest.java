package unit.net.firedevops.firemud.accountservice.service.controlui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiTokenChecks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountControlUiTokenChecksTest {
  private AccountControlUiTokenFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    fixture =
        new AccountControlUiTokenFixture(
            Clock.fixed(AccountControlUiTokenFixture.NOW, ZoneOffset.UTC));
  }

  @Test
  void realRs256UnscopedTokenAndExactActiveRecordProduceOnlyNonAuthorizingInspection()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    Map<String, Object> claims = fixture.claims(accountId);
    String token = fixture.sign(claims);
    var inspected =
        fixture
            .checks()
            .inspect(
                "fresh-creator-candidate",
                token,
                AccountControlUiTokenFixture.canonical(fixture.registry(token, claims)),
                AccountControlUiTokenFixture.unscopedShape());
    assertThat(inspected.accountId()).isEqualTo(accountId);
    assertThat(inspected.toString()).contains("non-authorizing").doesNotContain(token);
    inspected.registryRecord().put("state", "revoked");
    assertThat(inspected.registryRecord().get("state")).isEqualTo("active");
    assertThatThrownBy(() -> inspected.claims().put("actorId", UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void adjacentLargeSignedAndRegistryCountersCannotMatchThroughCanonicalRounding()
      throws Exception {
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    claims.put("issuanceFence", "9007199254740993");
    String token = fixture.sign(claims);
    Map<String, Object> record = fixture.registry(token, claims);
    record.put("issuanceFence", "9007199254740992");
    byte[] bytes = AccountControlUiTokenFixture.canonical(record);
    assertThatThrownBy(
            () ->
                fixture
                    .checks()
                    .inspect(
                        "fresh-creator-candidate",
                        token,
                        bytes,
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
  }

  @Test
  void numericAuthorityCounterDeniesWithoutCompatibility() throws Exception {
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    claims.put("tokenGeneration", new java.math.BigInteger("9007199254740993"));
    String token = fixture.sign(claims);
    byte[] exactRecord = AccountControlUiTokenFixture.exactJson(fixture.registry(token, claims));
    assertThatThrownBy(
            () ->
                fixture
                    .checks()
                    .inspect(
                        "fresh-creator-candidate",
                        token,
                        exactRecord,
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
  }

  @Test
  void largeDecimalStringCountersPreserveExactSignedRegistryAndCatalogCorrespondence()
      throws Exception {
    for (String counter :
        List.of(
            "9007199254740992", "9007199254740993", "9223372036854775808", "9223372036854775809")) {
      Map<String, Object> claims = fixture.claims(UUID.randomUUID());
      claims.put("tokenGeneration", counter);
      claims.put("issuanceFence", counter);
      Map<String, Object> tuple =
          new LinkedHashMap<>((Map<String, Object>) claims.get("authorityTuple"));
      tuple.put("issuerAuthGeneration", counter);
      tuple.put("accountAuthorityGeneration", counter);
      claims.put("authorityTuple", tuple);
      String token = fixture.sign(claims);
      var inspected =
          fixture
              .checks()
              .inspect(
                  "creator-candidate",
                  token,
                  AccountControlUiTokenFixture.canonical(fixture.registry(token, claims)),
                  AccountControlUiTokenFixture.unscopedShape());
      assertThat(inspected.claims().get("tokenGeneration")).isEqualTo(counter);
      assertThat(inspected.registryRecord().get("issuanceFence")).isEqualTo(counter);
      assertThat(inspected.registryRecord().get("authorityTuple")).isEqualTo(tuple);
    }
  }

  @Test
  void positiveLineageAndFenceCountersRejectAllNoncanonicalWireForms() throws Exception {
    for (Object invalid : List.of(1L, "0", "-1", "+1", "01", " 1", "1 ", "1.0", "1e3", "", "bad")) {
      for (String field : List.of("tokenGeneration", "issuanceFence")) {
        Map<String, Object> claims = fixture.claims(UUID.randomUUID());
        claims.put(field, invalid);
        String token = fixture.sign(claims);
        byte[] record = AccountControlUiTokenFixture.canonical(fixture.registry(token, claims));
        assertThatThrownBy(
                () ->
                    fixture
                        .checks()
                        .inspect(
                            "creator-candidate",
                            token,
                            record,
                            AccountControlUiTokenFixture.unscopedShape()))
            .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
      }
    }
  }

  @Test
  void rewritingSignedNumericBytesCannotRescueAHistoricalToken() throws Exception {
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    claims.put("tokenGeneration", 1L);
    String numericToken = fixture.sign(claims);
    String[] parts = numericToken.split("\\.");
    String numericPayload =
        new String(
            java.util.Base64.getUrlDecoder().decode(parts[1]),
            java.nio.charset.StandardCharsets.UTF_8);
    String rewrittenPayload =
        numericPayload.replace("\"tokenGeneration\":1", "\"tokenGeneration\":\"1\"");
    assertThat(rewrittenPayload).isNotEqualTo(numericPayload);
    String rewrittenToken =
        parts[0]
            + "."
            + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rewrittenPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + parts[2];
    claims.put("tokenGeneration", "1");
    byte[] record =
        AccountControlUiTokenFixture.canonical(fixture.registry(rewrittenToken, claims));
    assertThatThrownBy(
            () ->
                fixture
                    .checks()
                    .inspect(
                        "creator-candidate",
                        rewrittenToken,
                        record,
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
  }

  @Test
  void durableCaptureVersionAndAccountSourceVersionAdvanceIndependently() throws Exception {
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    String token = fixture.sign(claims);
    var checks = fixture.checks();
    for (long[] versions :
        List.of(new long[] {71L, 3L}, new long[] {72L, 3L}, new long[] {72L, 4L})) {
      Map<String, Object> record = fixture.registry(token, claims);
      record.put(
          "authoritySourceVersions",
          Map.of(
              "issuerSourceVersion",
              1L,
              "accountSourceVersion",
              versions[1],
              "issuanceFenceSourceVersion",
              1L));
      record.put(
          "authEvidenceBundle",
          Map.of(
              "bundleVersion",
              "1",
              "sourceVersion",
              Long.toString(versions[0]),
              "linearization",
              "123",
              "canonicalSha256",
              "c".repeat(64)));
      var inspected =
          checks.inspect(
              "fresh-creator-candidate",
              token,
              AccountControlUiTokenFixture.canonical(record),
              AccountControlUiTokenFixture.unscopedShape());
      assertThat(inspected.registryRecord().get("authEvidenceBundle"))
          .isEqualTo(record.get("authEvidenceBundle"));
      assertThat(inspected.toString()).contains("non-authorizing");
    }
  }

  @Test
  void billingSafeMembershipDoesNotCreateTargetTenantAuthority() throws Exception {
    UUID tenant = UUID.randomUUID();
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    Map<String, Object> tuple =
        new LinkedHashMap<>((Map<String, Object>) claims.get("authorityTuple"));
    tuple.put("membershipAuthorityGeneration", Map.of(tenant.toString(), "9223372036854775808"));
    claims.put("authorityTuple", tuple);
    claims.put("membershipVersion", Map.of(tenant.toString(), "9223372036854775809"));
    claims.put("scopedRoles", Map.of(tenant.toString(), List.of("tenantAdmin")));
    String token = fixture.sign(claims);
    var inspected =
        fixture
            .checks()
            .inspect(
                "billing-safe-candidate",
                token,
                AccountControlUiTokenFixture.canonical(fixture.registry(token, claims)),
                AccountControlUiTokenFixture.shape(
                    Set.of(tenant), Set.of(tenant), Set.of(), Set.of(tenant), Set.of(tenant)));
    assertThat(
            ((Map<?, ?>) inspected.claims().get("authorityTuple")).get("tenantAuthorityGeneration"))
        .isEqualTo(Map.of());
  }

  @Test
  void canonicalZeroMembershipVersionPassesCatalogWithoutInferringMembershipAuthority()
      throws Exception {
    UUID tenant = UUID.randomUUID();
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    Map<String, Object> tuple =
        new LinkedHashMap<>((Map<String, Object>) claims.get("authorityTuple"));
    tuple.put("membershipAuthorityGeneration", Map.of(tenant.toString(), "9223372036854775808"));
    claims.put("authorityTuple", tuple);
    claims.put("membershipVersion", Map.of(tenant.toString(), "0"));
    claims.put("scopedRoles", Map.of(tenant.toString(), List.of("tenantAdmin")));
    String token = fixture.sign(claims);
    var inspected =
        fixture
            .checks()
            .inspect(
                "billing-safe-candidate",
                token,
                AccountControlUiTokenFixture.canonical(fixture.registry(token, claims)),
                AccountControlUiTokenFixture.shape(
                    Set.of(tenant), Set.of(tenant), Set.of(), Set.of(tenant), Set.of(tenant)));
    assertThat(inspected.registryRecord().get("membershipVersion"))
        .isEqualTo(Map.of(tenant.toString(), "0"));
    assertThat(inspected.toString()).contains("non-authorizing");
  }

  @Test
  void exactRecordIdentityStateTimesProfileTupleAndMembershipMustMatch() throws Exception {
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    String token = fixture.sign(claims);
    for (Map.Entry<String, Object> change :
        Map.<String, Object>ofEntries(
                Map.entry("state", "pending"),
                Map.entry("profile", "game-session-account-delegation"),
                Map.entry("type", "private_player_delegation"),
                Map.entry("issuer", "other"),
                Map.entry("audience", "player-bootstrap"),
                Map.entry("accountId", UUID.randomUUID().toString()),
                Map.entry("kid", "other-key"),
                Map.entry("signerGeneration", "0"),
                Map.entry("tokenHash", "0".repeat(64)),
                Map.entry("exp", AccountControlUiTokenFixture.NOW.getEpochSecond() + 901L),
                Map.entry("issuanceFence", "2"),
                Map.entry("membershipVersion", Map.of(UUID.randomUUID().toString(), "1")),
                Map.entry("schemaVersion", 1L),
                Map.entry("registryVersion", 1L),
                Map.entry("authorityTuple", Map.of()))
            .entrySet()) {
      Map<String, Object> record = fixture.registry(token, claims);
      record.put(change.getKey(), change.getValue());
      byte[] bytes = AccountControlUiTokenFixture.canonical(record);
      assertThatThrownBy(
              () ->
                  fixture
                      .checks()
                      .inspect(
                          "creator-candidate",
                          token,
                          bytes,
                          AccountControlUiTokenFixture.unscopedShape()))
          .as(change.getKey())
          .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class)
          .hasNoCause();
    }
  }

  @Test
  void omittedRequiredEmptyCollectionsAndPrivateDelegationCannotSubstituteForControlUi()
      throws Exception {
    for (String field : List.of("scopedRoles", "membershipVersion", "authorityTuple")) {
      Map<String, Object> claims = fixture.claims(UUID.randomUUID());
      claims.remove(field);
      String token = fixture.sign(claims);
      assertThatThrownBy(
              () ->
                  fixture
                      .checks()
                      .inspect(
                          "creator-candidate",
                          token,
                          new byte[0],
                          AccountControlUiTokenFixture.unscopedShape()))
          .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
    }
    Map<String, Object> privateClaims = fixture.claims(UUID.randomUUID());
    privateClaims.put("aud", "account-service");
    String privateToken = fixture.sign(privateClaims);
    assertThatThrownBy(
            () ->
                fixture
                    .checks()
                    .inspect(
                        "creator-candidate",
                        privateToken,
                        new byte[0],
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
  }

  @Test
  void canonicalRecordMissingRegistryAndLogicalExpiryFailClosed() throws Exception {
    Map<String, Object> claims = fixture.claims(UUID.randomUUID());
    String token = fixture.sign(claims);
    byte[] record = AccountControlUiTokenFixture.canonical(fixture.registry(token, claims));
    assertThatThrownBy(
            () ->
                fixture
                    .checks()
                    .inspect(
                        "creator-candidate",
                        token,
                        null,
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
    byte[] whitespace =
        (" " + new String(record, java.nio.charset.StandardCharsets.UTF_8))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                fixture
                    .checks()
                    .inspect(
                        "creator-candidate",
                        token,
                        whitespace,
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
    var expiredFixture =
        new AccountControlUiTokenFixture(
            Clock.fixed(AccountControlUiTokenFixture.NOW.plusSeconds(900L), ZoneOffset.UTC));
    String expiredToken = expiredFixture.sign(claims);
    byte[] expiredRecord =
        AccountControlUiTokenFixture.canonical(expiredFixture.registry(expiredToken, claims));
    assertThatThrownBy(
            () ->
                expiredFixture
                    .checks()
                    .inspect(
                        "creator-candidate",
                        expiredToken,
                        expiredRecord,
                        AccountControlUiTokenFixture.unscopedShape()))
        .isInstanceOf(AccountControlUiTokenChecks.InvalidTokenException.class);
  }
}
