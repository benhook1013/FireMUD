package integration.net.firedevops.firemud.accountservice.repository;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.AuthorityMapShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ClaimFieldPresence;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ControlUiShape;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.DeploymentCeilings;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ProfileLimits;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiTokenChecks;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;

/**
 * Integration-source-set-local signed records; these prove verification composition only, not
 * Account issuance or signer custody.
 */
final class AccountControlUiTokenFixture {
  static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
  private static final String KID = "control-ui-test-key";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final KeyPair keys;
  private final Clock clock;

  AccountControlUiTokenFixture(Clock clock) throws Exception {
    this.clock = clock;
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    this.keys = generator.generateKeyPair();
  }

  AccountControlUiTokenChecks checks() {
    var pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test",
            "cluster-test",
            "11111111-1111-4111-8111-111111111111",
            "firemud-test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "binding-test",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    RSAPublicKey key = (RSAPublicKey) keys.getPublic();
    String jwks =
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
            + KID
            + "\",\"n\":\""
            + unsigned(key.getModulus().toByteArray())
            + "\",\"e\":\""
            + unsigned(key.getPublicExponent().toByteArray())
            + "\"}]}";
    var cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    pin, jwks.getBytes(StandardCharsets.UTF_8)),
            pin,
            clock,
            Duration.ofSeconds(30));
    return new AccountControlUiTokenChecks(
        new AccountAsymmetricJwtVerifier(cache, clock), catalog(), clock);
  }

  Map<String, Object> claims(UUID accountId) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("aud", "control-ui");
    claims.put("sub", accountId.toString());
    claims.put("accountId", accountId.toString());
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iat", NOW.getEpochSecond());
    claims.put("nbf", NOW.getEpochSecond());
    claims.put("exp", NOW.getEpochSecond() + 900L);
    claims.put("tokenGeneration", "1");
    claims.put("issuanceFence", "1");
    claims.put("scopedRoles", Map.of());
    claims.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(),
            "membershipAuthorityGeneration",
            Map.of(),
            "privateRealmGrantVersions",
            List.of()));
    claims.put("membershipVersion", Map.of());
    return claims;
  }

  String sign(Map<String, Object> claims) throws Exception {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            ("{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"typ\":\"JWT\"}")
                .getBytes(StandardCharsets.UTF_8));
    // Preserve the signed JWT payload bytes instead of rewriting their JSON preimage.
    String payload = encoder.encodeToString(JSON.writeValueAsBytes(claims));
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keys.getPrivate());
    signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
    return header + "." + payload + "." + encoder.encodeToString(signer.sign());
  }

  Map<String, Object> registry(String token, Map<String, Object> claims) throws Exception {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("schemaVersion", 2L);
    record.put("registryVersion", 2L);
    record.put(
        "tokenHash",
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII))));
    record.put("kid", KID);
    record.put("signerGeneration", "1");
    record.put("issuer", "firemud-account-service");
    record.put("audience", "control-ui");
    record.put("profile", "control-ui");
    record.put("type", "control-ui");
    record.put("operationId", UUID.randomUUID().toString());
    record.put("requestId", UUID.randomUUID().toString());
    record.put("requestDigest", "b".repeat(64));
    record.put("state", "active");
    for (String field :
        List.of(
            "accountId",
            "jti",
            "iat",
            "nbf",
            "exp",
            "tokenGeneration",
            "authorityTuple",
            "membershipVersion",
            "issuanceFence")) {
      record.put(field, claims.get(field));
    }
    record.put(
        "authoritySourceVersions",
        Map.of(
            "issuerSourceVersion",
            1L,
            "accountSourceVersion",
            1L,
            "issuanceFenceSourceVersion",
            1L));
    record.put(
        "authEvidenceBundle",
        Map.of(
            "bundleVersion",
            "1",
            "sourceVersion",
            "1",
            "linearization",
            "123",
            "canonicalSha256",
            "c".repeat(64)));
    return record;
  }

  static ControlUiShape unscopedShape() {
    return shape(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
  }

  static ControlUiShape shape(
      Set<UUID> scopes,
      Set<UUID> roles,
      Set<UUID> tenants,
      Set<UUID> memberships,
      Set<UUID> billingSafe) {
    return new ControlUiShape(
        new ClaimFieldPresence(
            true,
            true,
            true,
            true,
            "control-ui",
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            false),
        new AuthorityMapShape(tenants, memberships, memberships, Set.of(), List.of()),
        scopes,
        roles,
        tenants,
        memberships,
        billingSafe,
        Set.of());
  }

  static byte[] canonical(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  static byte[] exactJson(Object value) throws Exception {
    return JSON.writeValueAsBytes(value);
  }

  private static AccountTokenProfileCatalog catalog() {
    return new AccountTokenProfileCatalog(
        AccountTokenProfileCatalog.VERSION,
        Map.of(
            "control-ui",
                new ProfileLimits(
                    "control-ui", 1800, 4, 4, 4, 4, 0, 8192, 16384, 16384, Optional.of(4)),
            "player-bootstrap",
                new ProfileLimits(
                    "player-bootstrap", 300, 0, 0, 0, 0, 0, 8192, 16384, 16384, Optional.empty()),
            "game-session-account-delegation",
                new ProfileLimits(
                    "account-service", 300, 1, 1, 1, 1, 1, 8192, 16384, 16384, Optional.empty())),
        new DeploymentCeilings(16384, 8192, 16384, 1800));
  }

  private static String unsigned(byte[] value) {
    byte[] bytes =
        value.length > 1 && value[0] == 0
            ? java.util.Arrays.copyOfRange(value, 1, value.length)
            : value;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
