package net.firedevops.firemud.test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical Account gameplay-connect claim values shared by focused boundary tests. */
public final class SelectedTargetConnectContextTestVectors {
  public static final String ACCOUNT_ID = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
  public static final String TENANT_ID = "018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f";
  public static final String REALM_ID = "018f8f0a-4d9e-7c46-be37-8c1d0e9f7a5b";
  public static final String NAMESPACE_ID = "018f8f0a-5eaf-7d57-9c48-9d2e1f0a8b6c";
  public static final String GAME_INSTANCE_ID = "018f8f0a-6fb0-7e68-ad59-ae3f201b9c7d";
  public static final String GATEWAY_KID = "gateway-context-key-2026-01";
  public static final long NOW_EPOCH_SECONDS = 1_700_000_000L;
  public static final long SOURCE_ISSUED_AT = NOW_EPOCH_SECONDS - 2L;
  public static final long SOURCE_EXPIRES_AT = SOURCE_ISSUED_AT + 30L;
  public static final long GATEWAY_VERIFIED_AT = NOW_EPOCH_SECONDS - 1L;
  public static final BigInteger LARGE_COUNTER =
      new BigInteger("999999999999999999999999999999999999");

  private SelectedTargetConnectContextTestVectors() {}

  /** A complete public-production Account JWT claim set before Gateway projection. */
  public static Map<String, Object> sourceConnectTokenClaims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("aud", "gameplay-connect");
    claims.put("accountId", ACCOUNT_ID);
    claims.put("tenantId", TENANT_ID);
    claims.put("realmId", REALM_ID);
    claims.put("worldSlug", "demo-world");
    claims.put("realmSlug", "production");
    claims.put("playableStateNamespaceId", NAMESPACE_ID);
    claims.put("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED");
    claims.put("gameInstanceId", GAME_INSTANCE_ID);
    claims.put("pointerVersion", BigInteger.valueOf(17));
    claims.put("catalogRevision", BigInteger.valueOf(42));
    claims.put("connectScopeId", "connect-scope-17");
    claims.put("requestId", "connect-request-42");
    claims.put("authorityTuple", authorityTuple());
    claims.put("membershipVersion", Map.of(TENANT_ID, LARGE_COUNTER.toString()));
    claims.put("replayAdmissionFence", LARGE_COUNTER);
    claims.put("jti", "connect-token-jti-42");
    claims.put("iat", BigInteger.valueOf(SOURCE_ISSUED_AT));
    claims.put("exp", BigInteger.valueOf(SOURCE_EXPIRES_AT));
    return claims;
  }

  /** A private-realm source vector with matching lifecycle and exact grant evidence. */
  public static Map<String, Object> privateSourceConnectTokenClaims() {
    Map<String, Object> claims = sourceConnectTokenClaims();
    claims.put("realmSlug", "preview");
    claims.put("playtestLifecycleId", "018f8f0a-7ac1-7f79-be6a-bf4a312c0d8e");
    claims.put("playtestStateGeneration", BigInteger.valueOf(3));
    @SuppressWarnings("unchecked")
    Map<String, Object> tuple =
        new LinkedHashMap<>((Map<String, Object>) claims.get("authorityTuple"));
    tuple.put(
        "privateRealmGrantVersions",
        List.of(
            Map.of(
                "tenantId",
                TENANT_ID,
                "worldSlug",
                "demo-world",
                "realmSlug",
                "preview",
                "playtestLifecycleId",
                claims.get("playtestLifecycleId"),
                "grantVersion",
                BigInteger.valueOf(5))));
    claims.put("authorityTuple", tuple);
    return claims;
  }

  private static Map<String, Object> authorityTuple() {
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", BigInteger.TEN);
    tuple.put("accountAuthorityGeneration", BigInteger.valueOf(12));
    tuple.put("tenantAuthorityGeneration", Map.of(TENANT_ID, BigInteger.valueOf(13)));
    tuple.put("membershipAuthorityGeneration", Map.of(TENANT_ID, LARGE_COUNTER));
    tuple.put("privateRealmGrantVersions", List.of());
    return tuple;
  }
}
