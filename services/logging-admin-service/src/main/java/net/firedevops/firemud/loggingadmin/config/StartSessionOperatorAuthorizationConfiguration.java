package net.firedevops.firemud.loggingadmin.config;

import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disabled-by-default composition for the Logging/Admin StartSession authorization coordinator.
 *
 * <p>This supplies no ingress, request authority, reservation state, or TLS material. Enabling it
 * requires the existing durable reservation owner, a canonical workload namespace, and readable
 * file-backed client mTLS inputs.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = StartSessionOperatorAuthorizationConfiguration.PROPERTY_PREFIX,
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class StartSessionOperatorAuthorizationConfiguration {
  static final String PROPERTY_PREFIX =
      "firemud.logging-admin.start-session-operator-authorization";

  /** Opens the standard Account client only when this explicitly enabled composition is loaded. */
  @Bean(initMethod = "initialize", destroyMethod = "close")
  public StartSessionOperatorAuthorizationClient startSessionOperatorAuthorizationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      GrpcTlsMaterialResolver tlsMaterialResolver,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "A valid configured workload namespace is required for StartSession authorization");
    }
    FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        tlsMaterialResolver,
        "StartSession authorization",
        "StartSession authorization requires file-backed workload mTLS");
    return new StartSessionOperatorAuthorizationClient(
        endpoints, tlsProperties, channelFactory, stubCustomizer);
  }

  /** Wires the durable Logging reservation owner to its explicitly initialized Account client. */
  @Bean
  public StartSessionAuthorizationCoordinator startSessionAuthorizationCoordinator(
      StartSessionPreAuthorizationReservationService reservations,
      StartSessionOperatorAuthorizationClient accountClient) {
    return new StartSessionAuthorizationCoordinator(reservations, accountClient);
  }
}
