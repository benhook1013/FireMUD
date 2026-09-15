package net.firedevops.firemud.springcloudgateway.filter;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Parsed CIDR ranges used by gateway trust policies. */
final class CidrSet {
  private final List<CidrBlock> blocks;

  CidrSet(List<String> cidrs) {
    List<CidrBlock> parsed = new ArrayList<>();
    if (cidrs != null) {
      for (String cidr : cidrs) {
        CidrBlock block = CidrBlock.parse(cidr);
        if (block != null) {
          parsed.add(block);
        }
      }
    }
    this.blocks = List.copyOf(parsed);
  }

  boolean contains(InetAddress address) {
    if (address == null) {
      return false;
    }
    for (CidrBlock block : blocks) {
      if (block.contains(address)) {
        return true;
      }
    }
    return false;
  }
}
