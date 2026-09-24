package net.firedevops.firemud.common.grpc;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Version-one canonical form for the URI identity syntax shared by internal mTLS receivers.
 *
 * <p>This parser deliberately accepts only ASCII URI spellings whose normalization is
 * deterministic. It does not decide whether a normalized URI is an allowed workload identity.
 */
public record CanonicalUri(String value, String scheme, String authority, String path) {
  public static final int NORMALIZATION_VERSION = 1;

  private static final String SUB_DELIMITERS = "!$&'()*+,;=";

  public CanonicalUri {
    if (value == null || scheme == null || authority == null || path == null) {
      throw new IllegalArgumentException("Canonical URI fields are required");
    }
  }

  /** Parses and normalizes one absolute URI using the version-one identity contract. */
  public static Optional<CanonicalUri> parse(String raw) {
    if (raw == null
        || raw.isEmpty()
        || !StandardCharsets.US_ASCII.newEncoder().canEncode(raw)) {
      return Optional.empty();
    }

    try {
      URI uri = new URI(raw);
      if (!uri.isAbsolute()
          || uri.isOpaque()
          || uri.getRawAuthority() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getScheme() == null) {
        return Optional.empty();
      }

      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      String authority = normalizeAuthority(uri.getRawAuthority());
      if (authority == null) {
        return Optional.empty();
      }
      String path = normalizePath(uri.getRawPath());
      if (path == null) {
        return Optional.empty();
      }
      return Optional.of(
          new CanonicalUri(scheme + "://" + authority + path, scheme, authority, path));
    } catch (IllegalArgumentException | URISyntaxException ex) {
      return Optional.empty();
    }
  }

  private static String normalizeAuthority(String rawAuthority) {
    if (rawAuthority.isEmpty()
        || rawAuthority.indexOf('@') >= 0
        || rawAuthority.indexOf('%') >= 0) {
      return null;
    }

    if (rawAuthority.charAt(0) == '[') {
      int closingBracket = rawAuthority.indexOf(']');
      if (closingBracket < 0 || rawAuthority.indexOf('[', 1) >= 0) {
        return null;
      }
      String literal = rawAuthority.substring(1, closingBracket);
      if (!isIpv6Literal(literal)) {
        return null;
      }
      String suffix = rawAuthority.substring(closingBracket + 1);
      if (!suffix.isEmpty() && !suffix.startsWith(":")) {
        return null;
      }
      String rawPort = suffix.isEmpty() ? "" : suffix.substring(1);
      if (rawPort.isEmpty() && !suffix.isEmpty()) {
        return null;
      }
      String port = normalizePort(rawPort);
      if (port == null && !rawPort.isEmpty()) {
        return null;
      }
      try {
        byte[] address = InetAddress.getByName(literal).getAddress();
        if (address.length != 16) {
          return null;
        }
        String normalizedLiteral = normalizeIpv6(address);
        return "[" + normalizedLiteral + "]" + (port == null ? "" : ":" + port);
      } catch (java.net.UnknownHostException ex) {
        return null;
      }
    }

    if (rawAuthority.indexOf('[') >= 0
        || rawAuthority.indexOf(']') >= 0
        || rawAuthority.indexOf(':') != rawAuthority.lastIndexOf(':')) {
      return null;
    }
    int colon = rawAuthority.indexOf(':');
    String host = colon < 0 ? rawAuthority : rawAuthority.substring(0, colon);
    String suffix = colon < 0 ? "" : rawAuthority.substring(colon + 1);
    if (colon >= 0 && suffix.isEmpty()) {
      return null;
    }
    if (host.isEmpty() || !isRegName(host)) {
      return null;
    }
    String port = normalizePort(suffix);
    if (port == null && !suffix.isEmpty()) {
      return null;
    }
    return host.toLowerCase(Locale.ROOT) + (port == null ? "" : ":" + port);
  }

  private static String normalizePort(String suffix) {
    if (suffix.isEmpty()) {
      return null;
    }
    if (suffix.length() > 1 && suffix.charAt(0) == '0') {
      return null;
    }
    for (int index = 0; index < suffix.length(); index++) {
      if (suffix.charAt(index) < '0' || suffix.charAt(index) > '9') {
        return null;
      }
    }
    try {
      int port = Integer.parseInt(suffix);
      return port >= 1 && port <= 65535 ? Integer.toString(port) : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static boolean isIpv6Literal(String literal) {
    if (literal.isEmpty() || literal.indexOf(':') < 0) {
      return false;
    }
    for (int index = 0; index < literal.length(); index++) {
      char value = literal.charAt(index);
      if (!((value >= '0' && value <= '9')
          || (value >= 'a' && value <= 'f')
          || (value >= 'A' && value <= 'F')
          || value == ':'
          || value == '.')) {
        return false;
      }
    }
    return true;
  }

  private static String normalizeIpv6(byte[] address) {
    int[] words = new int[8];
    for (int index = 0; index < words.length; index++) {
      words[index] = ((address[index * 2] & 0xff) << 8) | (address[index * 2 + 1] & 0xff);
    }

    int bestStart = -1;
    int bestLength = 0;
    for (int index = 0; index < words.length; index++) {
      if (words[index] != 0) {
        continue;
      }
      int end = index;
      while (end < words.length && words[end] == 0) {
        end++;
      }
      if (end - index > bestLength && end - index >= 2) {
        bestStart = index;
        bestLength = end - index;
      }
      index = end - 1;
    }

    StringBuilder normalized = new StringBuilder(39);
    for (int index = 0; index < words.length; index++) {
      if (index == bestStart) {
        normalized.append("::");
        index += bestLength - 1;
        continue;
      }
      if (normalized.length() > 0 && normalized.charAt(normalized.length() - 1) != ':') {
        normalized.append(':');
      }
      normalized.append(Integer.toHexString(words[index]));
    }
    return normalized.toString();
  }

  private static boolean isRegName(String host) {
    for (int index = 0; index < host.length(); index++) {
      char value = host.charAt(index);
      if (!(isUnreserved(value) || SUB_DELIMITERS.indexOf(value) >= 0)) {
        return false;
      }
    }
    return true;
  }

  private static String normalizePath(String rawPath) {
    if (rawPath == null || rawPath.isEmpty()) {
      return "/";
    }
    if (!rawPath.startsWith("/")) {
      return null;
    }
    StringBuilder normalized = new StringBuilder(rawPath.length());
    for (int index = 0; index < rawPath.length(); index++) {
      char value = rawPath.charAt(index);
      if (value == '%') {
        if (index + 2 >= rawPath.length()) {
          return null;
        }
        int high = Character.digit(rawPath.charAt(index + 1), 16);
        int low = Character.digit(rawPath.charAt(index + 2), 16);
        if (high < 0 || low < 0) {
          return null;
        }
        char decoded = (char) ((high << 4) | low);
        if (!isUnreserved(decoded)) {
          return null;
        }
        normalized.append(decoded);
        index += 2;
      } else {
        if (value > 0x7f || Character.isISOControl(value) || Character.isWhitespace(value)) {
          return null;
        }
        normalized.append(value);
      }
    }
    for (String segment : normalized.toString().split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        return null;
      }
    }
    return normalized.toString();
  }

  private static boolean isUnreserved(char value) {
    return (value >= 'A' && value <= 'Z')
        || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }
}
