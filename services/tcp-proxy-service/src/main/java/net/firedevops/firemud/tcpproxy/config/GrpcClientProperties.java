package net.firedevops.firemud.tcpproxy.config;

import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TLS configuration for outbound gRPC connections from the TCP proxy. */
@ConfigurationProperties(prefix = "firemud.grpc")
public class GrpcClientProperties extends CommonGrpcClientProperties {}
