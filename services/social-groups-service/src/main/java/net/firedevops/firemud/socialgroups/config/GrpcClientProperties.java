package net.firedevops.firemud.socialgroups.config;

import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TLS configuration for outbound gRPC connections. */
@ConfigurationProperties(prefix = "firemud.grpc")
public class GrpcClientProperties extends CommonGrpcClientProperties {}
