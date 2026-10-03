package net.firedevops.firemud.common.security;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable proof material for a previously signed Gateway assertion.
 *
 * <p>This is deliberately not a {@link GatewayConnectContext}: expired evidence cannot be passed to
 * APIs that require a currently valid Gateway context. The original envelope and payload are
 * retained exactly for a later exact encrypted-original readback. This type establishes neither
 * source issuance nor current authorization, and must not be logged with its retained payload.
 */
public final class HistoricalGatewayConnectEvidence {
  private final String signedEnvelope;
  private final byte[] signedPayload;
  private final String kid;
  private final Map<String, Object> claims;

  HistoricalGatewayConnectEvidence(
      String signedEnvelope, byte[] signedPayload, String kid, Map<String, ?> claims) {
    this.signedEnvelope = Objects.requireNonNull(signedEnvelope, "signed envelope is required");
    this.signedPayload =
        Objects.requireNonNull(signedPayload, "signed payload is required").clone();
    this.kid = Objects.requireNonNull(kid, "Gateway key identifier is required");
    this.claims = immutableObject(claims);
  }

  /** Returns the exact compact JWS that was verified. */
  public String signedEnvelope() {
    return signedEnvelope;
  }

  /** Returns a defensive copy of the exact signed UTF-8 payload bytes. */
  public byte[] signedPayload() {
    return signedPayload.clone();
  }

  public String kid() {
    return kid;
  }

  /** Returns an immutable, recursively copied view of every accepted payload field. */
  public Map<String, Object> claims() {
    return immutableObject(claims);
  }

  public String text(String name) {
    Object value = claims.get(name);
    return value instanceof String text ? text : null;
  }

  public BigInteger integer(String name) {
    Object value = claims.get(name);
    return value instanceof BigInteger integer ? integer : null;
  }

  @Override
  public String toString() {
    return "HistoricalGatewayConnectEvidence[kid=" + kid + ", historical=true]";
  }

  private static Map<String, Object> immutableObject(Map<String, ?> source) {
    Objects.requireNonNull(source, "context claims are required");
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> typed = new LinkedHashMap<>();
      map.forEach(
          (key, nested) -> {
            if (!(key instanceof String text)) {
              throw new IllegalArgumentException("context object keys must be strings");
            }
            typed.put(text, immutableValue(nested));
          });
      return Collections.unmodifiableMap(typed);
    }
    if (value instanceof java.util.List<?> list) {
      return list.stream().map(HistoricalGatewayConnectEvidence::immutableValue).toList();
    }
    if (value instanceof String
        || value instanceof Boolean
        || value instanceof BigInteger
        || value == null) {
      return value;
    }
    throw new IllegalArgumentException("unsupported decoded context value type");
  }
}
