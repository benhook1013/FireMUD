package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcAccountStartSessionAdmissionProtectionClientTest {
  @TempDir Path directory;

  @Test
  void requiresFileBackedMutualTlsAndCanonicalNamespace() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GrpcAccountStartSessionAdmissionProtectionClient(
                    endpoints, plaintext, new GrpcChannelFactory(), "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires workload mTLS");

    CommonGrpcClientProperties tls = fileBackedProperties();
    assertThatThrownBy(
            () ->
                new GrpcAccountStartSessionAdmissionProtectionClient(
                    endpoints, tls, new GrpcChannelFactory(), "WORLD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Canonical Game Session workload namespace");

    try (var client =
        new GrpcAccountStartSessionAdmissionProtectionClient(
            endpoints, tls, new GrpcChannelFactory(), "world-runtime")) {
      // Construction does not initialize a channel or register an Account caller.
    }
  }

  private CommonGrpcClientProperties fileBackedProperties() throws Exception {
    Path certificate = Files.writeString(directory.resolve("client.crt"), "test certificate");
    Path privateKey = Files.writeString(directory.resolve("client.key"), "test key");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "test ca");
    var properties = new CommonGrpcClientProperties();
    properties.setPlaintext(false);
    properties.setCertChain(certificate.toString());
    properties.setPrivateKey(privateKey.toString());
    properties.setCaCert(caCertificate.toString());
    return properties;
  }
}
