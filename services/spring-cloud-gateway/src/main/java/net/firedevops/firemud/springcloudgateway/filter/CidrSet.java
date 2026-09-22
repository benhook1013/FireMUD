package net.firedevops.firemud.springcloudgateway.filter;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;

/** Parsed CIDR ranges used by gateway trust policies. */
final class CidrSet {
  private static final Logger LOG = LoggingUtil.getLogger(CidrSet.class);
  private final List<CidrBlock> blocks;

  CidrSet(List<String> cidrs) {
    List<CidrBlock> parsed = new ArrayList<>();
    if (cidrs != null) {
      for (String cidr : cidrs) {
        CidrBlock block = CidrBlock.parse(cidr);
        if (block != null) {
          parsed.add(block);
        } else if (cidr != null) {
          LOG.warn("Ignoring invalid configured CIDR entry value={}", cidr);
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
