package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.reflect.Proxy;
import java.time.Clock;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.CredentialSourceContext;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Outcome;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Transport;
import org.junit.jupiter.api.Test;

class AccountCredentialAbuseConfigurationTest {
  @Test
  void missingInputsAssembleOnlyAClosedOutcome() {
    var service = AccountCredentialAbuseConfiguration.controlled(null, null, Clock.systemUTC());
    var result =
        service.admit(
            new CredentialSourceContext("192.0.2.1", Transport.HTTP), "unknown@example.test");
    assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
    assertThat(result.outcome().refusalCode()).isEqualTo("AUTH_ABUSE_CONTROL_UNAVAILABLE");
    assertThat(result.retryAfter()).isNull();
    assertThat(result.permit()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void explicitConnectionDoesNotInventPolicyOrInitiateCommands() {
    StatefulRedisConnection<byte[], byte[]> connection =
        (StatefulRedisConnection<byte[], byte[]>)
            Proxy.newProxyInstance(
                StatefulRedisConnection.class.getClassLoader(),
                new Class<?>[] {StatefulRedisConnection.class},
                (proxy, method, args) -> {
                  throw new AssertionError(
                      "Unexpected connection interaction: " + method.getName());
                });
    var service =
        AccountCredentialAbuseConfiguration.controlled(null, connection, Clock.systemUTC());
    assertThat(
            service
                .admit(
                    new CredentialSourceContext("192.0.2.1", Transport.HTTP),
                    "unknown@example.test")
                .outcome())
        .isEqualTo(Outcome.UNAVAILABLE);
  }

  @Test
  void configurationHasNoAutomaticSpringRegistration() {
    assertThat(AccountCredentialAbuseConfiguration.class.getAnnotations()).isEmpty();
    for (var method : AccountCredentialAbuseConfiguration.class.getDeclaredMethods()) {
      assertThat(method.getAnnotations()).isEmpty();
    }
  }
}
