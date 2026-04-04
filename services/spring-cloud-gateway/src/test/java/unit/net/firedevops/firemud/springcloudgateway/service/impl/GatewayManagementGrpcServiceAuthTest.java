package net.firedevops.firemud.springcloudgateway.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import java.lang.reflect.Method;
import java.util.List;
import net.firedevops.firemud.common.security.RequireAdminRole;
import net.firedevops.firemud.gateway.v1.RemoveRouteRequest;
import net.firedevops.firemud.gateway.v1.UpsertRouteRequest;
import org.junit.jupiter.api.Test;

class GatewayManagementGrpcServiceAuthTest {

  @Test
  void routeManagementMethodsRequireAdminRole() throws Exception {
    for (Method method :
        List.of(
            GatewayManagementGrpcService.class.getMethod(
                "upsertRoute", UpsertRouteRequest.class, StreamObserver.class),
            GatewayManagementGrpcService.class.getMethod(
                "removeRoute", RemoveRouteRequest.class, StreamObserver.class))) {
      assertTrue(method.isAnnotationPresent(RequireAdminRole.class), method.getName());
    }
  }
}
