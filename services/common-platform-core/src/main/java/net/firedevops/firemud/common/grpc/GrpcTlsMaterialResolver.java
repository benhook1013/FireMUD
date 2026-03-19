package net.firedevops.firemud.common.grpc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

/** Resolves gRPC TLS material from configured file paths or classpath resources. */
public class GrpcTlsMaterialResolver {

  public ResolvedGrpcTlsMaterial resolve(CommonGrpcClientProperties properties) throws IOException {
    if (properties.isPlaintext()) {
      return null;
    }
    ResolvedGrpcTlsMaterial.TlsResource certChain =
        resolveTlsResource(properties.getCertChain(), "certChain");
    ResolvedGrpcTlsMaterial.TlsResource privateKey =
        resolveTlsResource(properties.getPrivateKey(), "privateKey");
    ResolvedGrpcTlsMaterial.TlsResource caCert =
        resolveTlsResource(properties.getCaCert(), "caCert");
    if (certChain == null && privateKey == null && caCert == null) {
      return null;
    }
    if (certChain == null || privateKey == null || caCert == null) {
      throw new IOException("TLS configuration must specify certChain, privateKey, and caCert");
    }
    return new ResolvedGrpcTlsMaterial(certChain, privateKey, caCert);
  }

  private ResolvedGrpcTlsMaterial.TlsResource resolveTlsResource(
      String configuredPath, String propertyName) throws IOException {
    if (!StringUtils.hasText(configuredPath)) {
      return null;
    }
    if (configuredPath.startsWith("classpath:")) {
      String resourcePath = configuredPath.substring("classpath:".length());
      ClassPathResource resource = new ClassPathResource(resourcePath);
      if (!resource.exists()) {
        throw new IOException("Classpath resource not found for TLS property " + propertyName);
      }
      return new ResolvedGrpcTlsMaterial.TlsResource(resource::getInputStream, null);
    }
    File file = new File(configuredPath);
    if (!file.exists()) {
      throw new IOException(
          "TLS file does not exist for property " + propertyName + ": " + configuredPath);
    }
    Path path = file.toPath();
    return new ResolvedGrpcTlsMaterial.TlsResource(() -> Files.newInputStream(path), path);
  }
}
