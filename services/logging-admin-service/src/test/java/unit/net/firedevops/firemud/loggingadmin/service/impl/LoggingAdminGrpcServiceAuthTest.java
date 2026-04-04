package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import java.lang.reflect.Method;
import java.util.List;
import net.firedevops.firemud.common.security.RequireAdminRole;
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.v1.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagRequest;
import org.junit.jupiter.api.Test;

class LoggingAdminGrpcServiceAuthTest {

  @Test
  void adminMethodsRequireAdminRole() throws Exception {
    for (Method method :
        List.of(
            LoggingAdminGrpcService.class.getMethod(
                "toggleFeatureFlag", ToggleFeatureFlagRequest.class, StreamObserver.class),
            LoggingAdminGrpcService.class.getMethod(
                "queryLogs", QueryLogsRequest.class, StreamObserver.class),
            LoggingAdminGrpcService.class.getMethod(
                "applyModerationAction",
                ApplyModerationActionRequest.class,
                StreamObserver.class))) {
      assertTrue(method.isAnnotationPresent(RequireAdminRole.class), method.getName());
    }
  }
}
