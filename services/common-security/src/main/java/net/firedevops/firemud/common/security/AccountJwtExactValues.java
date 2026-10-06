package net.firedevops.firemud.common.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Exact counter and complete JSON-value comparisons at Account JWT inspection boundaries. */
public final class AccountJwtExactValues {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private AccountJwtExactValues() {}

  /**
   * Parses an exact positive JSON number for numeric epoch/schema fields and separately owned
   * source evidence. Authority counters use {@link #positiveDecimalCounter(Object)} instead.
   */
  public static BigInteger positiveCounter(Object value) {
    if (!(value instanceof Number number)) throw invalid();
    try {
      BigInteger integer = exactNumber(number).toBigIntegerExact();
      if (integer.signum() <= 0) throw invalid();
      return integer;
    } catch (ArithmeticException failure) {
      throw invalid();
    }
  }

  /** Parses canonical positive decimal-string authority counters without a BIGINT ceiling. */
  public static BigInteger positiveDecimalCounter(Object value) {
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]*")) throw invalid();
    return new BigInteger(text);
  }

  /** Parses a canonical unsigned decimal string where the owning field permits zero. */
  public static BigInteger nonNegativeDecimalCounter(Object value) {
    if (!(value instanceof String text) || !text.matches("0|[1-9][0-9]*")) throw invalid();
    return new BigInteger(text);
  }

  /**
   * Compares the complete nested JSON structure and exact numeric values. RFC 8785 bytes are not an
   * equality oracle: its binary64 rendering can collapse adjacent large integers.
   */
  public static boolean sameJsonValue(Object left, Object right) {
    if (left == null || right == null) return left == right;
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      try {
        return exactNumber(leftNumber).compareTo(exactNumber(rightNumber)) == 0;
      } catch (IllegalArgumentException failure) {
        return false;
      }
    }
    if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
      if (!leftMap.keySet().equals(rightMap.keySet())) return false;
      for (Map.Entry<?, ?> entry : leftMap.entrySet()) {
        if (!(entry.getKey() instanceof String)
            || !sameJsonValue(entry.getValue(), rightMap.get(entry.getKey()))) return false;
      }
      return true;
    }
    if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
      if (leftList.size() != rightList.size()) return false;
      for (int index = 0; index < leftList.size(); index++) {
        if (!sameJsonValue(leftList.get(index), rightList.get(index))) return false;
      }
      return true;
    }
    return (left instanceof String && right instanceof String
            || left instanceof Boolean && right instanceof Boolean)
        && left.equals(right);
  }

  /**
   * Measures the required canonical bytes only when their exact decoded values survive unchanged.
   * Unrepresentable numeric candidates fail; no wire type is changed to rescue a candidate.
   */
  public static byte[] losslessCanonicalBytes(Object value) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      Object decoded = JSON.readValue(canonical, Object.class);
      if (!sameJsonValue(value, decoded)) throw invalid();
      return canonical;
    } catch (IOException | RuntimeException failure) {
      throw invalid();
    }
  }

  private static BigDecimal exactNumber(Number value) {
    if (value instanceof BigInteger integer) return new BigDecimal(integer);
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return BigDecimal.valueOf(value.longValue());
    }
    // Binary floating-point values cannot establish the original exact counter preimage.
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Account JWT numeric value is not exact");
  }
}
