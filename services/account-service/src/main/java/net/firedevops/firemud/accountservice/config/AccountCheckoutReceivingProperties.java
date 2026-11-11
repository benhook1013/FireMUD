package net.firedevops.firemud.accountservice.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Explicit source selection for isolated receiving; never privacy or deployment qualification. */
public record AccountCheckoutReceivingProperties(
    String namespace,
    Path privateSignerRoot,
    Path publicSignerRoot,
    Path controlUiKeyringRoot,
    long sessionSafetyMarginMillis) {
  public AccountCheckoutReceivingProperties {
    if (!GrpcPeerIdentity.isValidNamespace(namespace) || sessionSafetyMarginMillis < 0L)
      throw new IllegalArgumentException("Explicit Account checkout sources required");
    privateSignerRoot = requirePath(privateSignerRoot);
    publicSignerRoot = requirePath(publicSignerRoot);
    controlUiKeyringRoot = requirePath(controlUiKeyringRoot);
    if (controlUiKeyringRoot.startsWith(privateSignerRoot)
        || privateSignerRoot.startsWith(controlUiKeyringRoot)
        || controlUiKeyringRoot.startsWith(publicSignerRoot)
        || publicSignerRoot.startsWith(controlUiKeyringRoot)) {
      throw new IllegalArgumentException("Dedicated control-ui custody must remain separate");
    }
  }

  public String gatewayPeerUri() {
    return "spiffe://firemud/ns/" + namespace + "/sa/spring-cloud-gateway";
  }

  private static Path requirePath(Path path) {
    if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
      throw new IllegalArgumentException(
          "Explicit absolute protected Account source paths required");
    }
    try {
      Path real = path.toRealPath();
      if (!Files.isDirectory(real)) {
        throw new IllegalArgumentException(
            "Protected Account source roots must be mounted directories");
      }
      return real;
    } catch (IOException | SecurityException unavailable) {
      throw new IllegalArgumentException("Protected Account source roots must resolve physically");
    }
  }
}
