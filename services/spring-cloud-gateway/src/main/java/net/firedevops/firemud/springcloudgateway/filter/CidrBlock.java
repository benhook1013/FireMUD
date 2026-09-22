package net.firedevops.firemud.springcloudgateway.filter;

import io.netty.util.NetUtil;
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
    byte[] addressBytes = parseIpLiteral(parts[0]);
    if (addressBytes == null) {
      return null;
    }
    int prefix;
    try {
      prefix = Integer.parseInt(parts[1].trim());
    } catch (Exception ignored) {
      return null;
    }
    try {
      boolean ipv4Mapped = isIpv4MappedIpv6(addressBytes);
      if (ipv4Mapped) {
        if (prefix < 96) {
          return null;
        }
        addressBytes = ipv4MappedAddressBytes(addressBytes);
        prefix -= 96;
      }
      int max = addressBytes.length * 8;
      if (prefix < 0 || prefix > max) {
        return null;
      }
      byte[] networkBytes = addressBytes.clone();
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
    byte[] addressBytes = parseIpLiteral(trimmed);
    if (addressBytes == null) {
      return null;
    }
    if (isIpv4MappedIpv6(addressBytes)) {
      addressBytes = ipv4MappedAddressBytes(addressBytes);
    }
    try {
      return InetAddress.getByAddress(addressBytes).getHostAddress();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static byte[] parseIpLiteral(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      trimmed = trimmed.substring(1, trimmed.length() - 1);
    } else if (trimmed.indexOf('[') >= 0 || trimmed.indexOf(']') >= 0) {
      return null;
    }
    if (trimmed.isEmpty() || trimmed.indexOf('%') >= 0) {
      return null;
    }
    byte[] bytes = NetUtil.createByteArrayFromIpAddressString(trimmed);
    if (bytes == null) {
      return null;
    }
    // NetUtil intentionally handles only dotted-decimal IPv4 and IPv6 literals;
    // retain an explicit guard against non-canonical IPv4 shorthand/integer forms.
    if (bytes.length == 4 && !isCanonicalIpv4Literal(trimmed)) {
      return null;
    }
    return bytes;
  }

  private static boolean isCanonicalIpv4Literal(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
        return false;
      }
      try {
        if (Integer.parseInt(part) > 255) {
          return false;
        }
      } catch (NumberFormatException ignored) {
        return false;
      }
    }
    return true;
  }

  boolean contains(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (network.length == 4 && isIpv4MappedIpv6(bytes)) {
      bytes = ipv4MappedAddressBytes(bytes);
    }
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

  private static boolean isIpv4MappedIpv6(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }

  private static byte[] ipv4MappedAddressBytes(byte[] bytes) {
    return java.util.Arrays.copyOfRange(bytes, 12, 16);
  }
}
