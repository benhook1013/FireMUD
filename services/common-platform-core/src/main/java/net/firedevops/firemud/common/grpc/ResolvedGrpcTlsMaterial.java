package net.firedevops.firemud.common.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Resolved TLS material for gRPC clients, supporting both file and classpath-backed inputs. */
public record ResolvedGrpcTlsMaterial(
    TlsResource certChain, TlsResource privateKey, TlsResource caCert) {

  public List<Path> watchPaths() {
    List<Path> paths = new ArrayList<>();
    addIfPresent(paths, certChain.watchPath());
    addIfPresent(paths, privateKey.watchPath());
    addIfPresent(paths, caCert.watchPath());
    return paths;
  }

  private static void addIfPresent(List<Path> paths, Path path) {
    if (path != null) {
      paths.add(path);
    }
  }

  public record TlsResource(TlsInputStreamSupplier streamSupplier, Path watchPath) {
    public InputStream openStream() throws IOException {
      return streamSupplier.open();
    }
  }

  @FunctionalInterface
  public interface TlsInputStreamSupplier {
    InputStream open() throws IOException;
  }
}
