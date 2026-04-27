package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import org.junit.jupiter.api.Test;

class AccountRuntimeStubServerCompatibilityTest {
  private static final Set<String> NON_RUNTIME_METHODS =
      Set.of(
          "CreateAccount",
          "GetProfile",
          "UpdateProfile",
          "ExportAccount",
          "ExportTenantData",
          "DeleteAccount",
          "RequestPasswordReset",
          "CompletePasswordReset",
          "LinkExternalAccount",
          "RequestEmailVerification",
          "VerifyEmail");

  @Test
  void accountServiceMethodSetIsFullyCategorized() {
    Set<String> actualMethods =
        AccountServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(method -> method.getBareMethodName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(actualMethods)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(
                    AccountRuntimeStubServer.implementedRuntimeMethodNames().stream(),
                    NON_RUNTIME_METHODS.stream())
                .collect(java.util.stream.Collectors.toSet()));
  }
}
