package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import java.lang.reflect.Method;
import java.util.List;
import net.firedevops.firemud.common.security.RequireAdminRole;
import net.firedevops.firemud.gamedesign.v1.DeleteSettingsDomainOverrideRequest;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PutSettingsDomainOverrideRequest;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionRequest;
import org.junit.jupiter.api.Test;

class GameDesignGrpcServiceAuthTest {

  @Test
  void adminMethodsRequireAdminRole() throws Exception {
    for (Method method :
        List.of(
            GameDesignGrpcService.class.getMethod(
                "saveRevision", SaveRevisionRequest.class, StreamObserver.class),
            GameDesignGrpcService.class.getMethod(
                "publishVersion", PublishVersionRequest.class, StreamObserver.class),
            GameDesignGrpcService.class.getMethod(
                "publishScriptPatchVersion",
                PublishScriptPatchVersionRequest.class,
                StreamObserver.class),
            GameDesignGrpcService.class.getMethod(
                "listVersions", ListVersionsRequest.class, StreamObserver.class),
            GameDesignGrpcService.class.getMethod(
                "getScopedSettingsOverrides",
                GetScopedSettingsOverridesRequest.class,
                StreamObserver.class),
            GameDesignGrpcService.class.getMethod(
                "putSettingsDomainOverride",
                PutSettingsDomainOverrideRequest.class,
                StreamObserver.class),
            GameDesignGrpcService.class.getMethod(
                "deleteSettingsDomainOverride",
                DeleteSettingsDomainOverrideRequest.class,
                StreamObserver.class))) {
      assertTrue(method.isAnnotationPresent(RequireAdminRole.class), method.getName());
    }
  }
}
