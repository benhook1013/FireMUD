package net.firedevops.firemud.accountservice.security;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.security.HistoricalGatewayConnectEvidence;

/**
 * Immutable Account source claims verified against a previously signed Gateway assertion.
 *
 * <p>This is historical provenance only. It does not prove original durable issuance, past Gateway
 * acceptance, current authority, registry state, recovery eligibility, or gameplay admission. It is
 * not a current source claim or a {@code GatewayConnectContext}, and it never retains the compact
 * Account JWT.
 */
public final class HistoricalAccountGameplayConnectEvidence {
  private final String accountKeyId;
  private final Map<String, Object> sourceClaims;
  private final HistoricalGatewayConnectEvidence gatewayEvidence;

  HistoricalAccountGameplayConnectEvidence(
      String accountKeyId,
      Map<String, ?> sourceClaims,
      HistoricalGatewayConnectEvidence gatewayEvidence) {
    if (accountKeyId == null || accountKeyId.isBlank()) {
      throw new IllegalArgumentException("Account source key identifier is required");
    }
    this.accountKeyId = accountKeyId;
    this.sourceClaims = immutableObject(sourceClaims);
    this.gatewayEvidence = Objects.requireNonNull(gatewayEvidence, "Gateway evidence is required");
  }

  public String accountKeyId() {
    return accountKeyId;
  }

  /** Returns an immutable, recursively copied view of the verified Account source claims. */
  public Map<String, Object> sourceClaims() {
    return immutableObject(sourceClaims);
  }

  /** Returns the distinct historical Gateway evidence used for exact source correspondence. */
  public HistoricalGatewayConnectEvidence gatewayEvidence() {
    return gatewayEvidence;
  }

  @Override
  public String toString() {
    return "HistoricalAccountGameplayConnectEvidence[accountKeyId="
        + accountKeyId
        + ", gatewayKeyId="
        + gatewayEvidence.kid()
        + ", historical=true]";
  }

  private static Map<String, Object> immutableObject(Map<String, ?> source) {
    Objects.requireNonNull(source, "source claims are required");
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object immutableValue(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Map<?, ?> source) {
      Map<String, Object> copy = new LinkedHashMap<>();
      source.forEach(
          (key, nested) -> {
            if (!(key instanceof String text)) {
              throw new IllegalArgumentException("source claim object keys must be strings");
            }
            copy.put(text, immutableValue(nested));
          });
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> source) {
      List<Object> copy = new ArrayList<>(source.size());
      source.forEach(nested -> copy.add(immutableValue(nested)));
      return Collections.unmodifiableList(copy);
    }
    throw new IllegalArgumentException("unsupported verified source claim value");
  }
}
