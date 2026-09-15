package net.firedevops.firemud.springcloudgateway.filter;

import java.net.InetAddress;

/** Immutable parsed CIDR network block. */
record CidrBlock(byte[] network, int prefixBits) {
  static CidrBlock parse(String cidr) {
    if (cidr == null) {
      return null;
    }
    String trimmed = cidr.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    String[] parts = trimmed.split("/");
    if (parts.length != 2) {
      return null;
    }
    String ip = normalizeIpLiteral(parts[0]);
    if (ip == null) {
      return null;
    }
    int prefix;
    try {
      prefix = Integer.parseInt(parts[1].trim());
    } catch (Exception ignored) {
      return null;
    }
    try {
      InetAddress address = InetAddress.getByName(ip);
      int max = address.getAddress().length * 8;
      if (prefix < 0 || prefix > max) {
        return null;
      }
      byte[] networkBytes = address.getAddress();
      applyMaskInPlace(networkBytes, prefix);
      return new CidrBlock(networkBytes, prefix);
    } catch (Exception ignored) {
      return null;
    }
  }

  static String normalizeIpLiteral(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    boolean hasHexLetter = false;
    boolean hasColon = false;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      boolean hexLetter = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      boolean allowed =
          (c >= '0' && c <= '9')
              || hexLetter
              || c == '.'
              || c == ':'
              || c == '['
              || c == ']'
              || c == '%';
      if (!allowed) {
        return null;
      }
      hasHexLetter |= hexLetter;
      hasColon |= c == ':';
    }
    if (hasHexLetter && !hasColon) {
      return null;
    }
    try {
      if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        trimmed = trimmed.substring(1, trimmed.length() - 1);
      }
      InetAddress address = InetAddress.getByName(trimmed);
      return address.getHostAddress();
    } catch (Exception ignored) {
      return null;
    }
  }

  boolean contains(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != network.length) {
      return false;
    }
    int fullBytes = prefixBits / 8;
    int remainingBits = prefixBits % 8;
    for (int i = 0; i < fullBytes; i++) {
      if (bytes[i] != network[i]) {
        return false;
      }
    }
    if (remainingBits == 0) {
      return true;
    }
    int mask = 0xFF << (8 - remainingBits);
    return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
  }

  private static void applyMaskInPlace(byte[] bytes, int prefixBits) {
    int fullBytes = prefixBits / 8;
    int remainingBits = prefixBits % 8;
    for (int i = fullBytes + (remainingBits > 0 ? 1 : 0); i < bytes.length; i++) {
      bytes[i] = 0;
    }
    if (remainingBits == 0 || fullBytes >= bytes.length) {
      return;
    }
    int mask = 0xFF << (8 - remainingBits);
    bytes[fullBytes] = (byte) (bytes[fullBytes] & mask);
  }
}
