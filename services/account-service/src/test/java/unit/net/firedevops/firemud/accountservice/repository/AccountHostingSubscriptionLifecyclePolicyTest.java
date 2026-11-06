package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Reason;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Status;
import org.junit.jupiter.api.Test;

/** Real Stripe SDK deserialization over controlled transport; no remote Stripe requests. */
class AccountHostingSubscriptionLifecyclePolicyTest {
  private static final Instant NOW = Instant.ofEpochSecond(1_778_000_010);
  private static final Instant END = Instant.ofEpochSecond(1_780_592_000);
  private static final UUID SUBSCRIPTION = UUID.fromString("0c9ee07d-0e6d-4f66-9a87-54110acbc611");
  private static final UUID OPERATION = UUID.fromString("4e2af076-4a10-4aaf-93a0-e5df29271310");
  private static final String RESPONSE =
      """
      {"id":"sub_expected","object":"subscription","customer":"cus_expected",
       "default_payment_method":"pm_expected","livemode":false,"status":"active",
       "cancel_at_period_end":false,"cancel_at":null,"canceled_at":null,"ended_at":null,
       "metadata":{"firemud_metadata_schema_version":"1",
        "firemud_operation_type":"subscription_provisioning",
        "firemud_operation_id":"4e2af076-4a10-4aaf-93a0-e5df29271310",
        "firemud_subscription_id":"0c9ee07d-0e6d-4f66-9a87-54110acbc611"},
       "items":{"object":"list","data":[{"id":"si_expected","object":"subscription_item",
        "subscription":"sub_expected","quantity":1,"current_period_start":1778000000,
        "current_period_end":1780592000,"price":{"id":"price_expected","object":"price"}}],
        "has_more":false}}
      """;

  @Test
  void mapsActiveAndTrialOnlyFromActualSdkEvidenceAndExpiresAtStrictReadStartDeadline() {
    var active = observed(RESPONSE, NOW);
    var decision = policy().evaluate(active, NOW, null);
    assertThat(decision.status()).isEqualTo(Status.ACTIVE);
    assertThat(decision.validUntil()).isEqualTo(NOW.plusSeconds(15));
    assertThat(decision.receiptValidUntil()).isEqualTo(END);
    assertThat(policy().evaluate(active, NOW.plusSeconds(14), null).resolved()).isTrue();
    assertThat(policy().evaluate(active, NOW.plusSeconds(15), null).reason())
        .isEqualTo(Reason.OBSERVATION_EXPIRED);
    assertThat(policy().evaluate(active, NOW.minusNanos(1), null).reason())
        .isEqualTo(Reason.UNSAFE_READ_INTERVAL);
    var trial = observed(RESPONSE.replace("\"active\"", "\"trialing\""), NOW);
    assertThat(policy().evaluate(trial, NOW, null).status()).isEqualTo(Status.TRIALING);
  }

  @Test
  void pastDueUsesRetainedEpisodeAndExactConfiguredBoundariesWithoutRestartingGrace() {
    String response = RESPONSE.replace("\"active\"", "\"past_due\"");
    assertThat(policy().evaluate(observed(response, NOW), NOW, null).reason())
        .isEqualTo(Reason.RECOVERY_ORIGIN_UNRESOLVED);
    assertThat(policy().evaluate(observed(response, NOW), NOW, NOW.plusNanos(1)).resolved())
        .isFalse();
    Instant delayedRead = NOW.plusSeconds(600);
    assertThat(policy().evaluate(observed(response, delayedRead), delayedRead, null).reason())
        .isEqualTo(Reason.RECOVERY_ORIGIN_UNRESOLVED);
    assertThat(policy().evaluate(observed(response, delayedRead), delayedRead, NOW).status())
        .isEqualTo(Status.SUSPENDED);
    Instant warningEnd = NOW.plusSeconds(60);
    assertThat(policy().evaluate(observed(response, NOW), NOW, NOW).status())
        .isEqualTo(Status.PAST_DUE);
    assertThat(policy().evaluate(observed(response, warningEnd), warningEnd, NOW).status())
        .isEqualTo(Status.GRACE);
    Instant graceEnd = warningEnd.plusSeconds(120);
    var cutoff = policy().evaluate(observed(response, graceEnd), graceEnd, NOW);
    assertThat(cutoff.status()).isEqualTo(Status.SUSPENDED);
    assertThat(cutoff.cutoffAt()).isEqualTo(graceEnd);
    assertThat(policy().evaluate(observed(response, NOW), NOW, NOW).validUntil())
        .isEqualTo(NOW.plusSeconds(15));
    assertThat(policy().evaluate(observed(response, NOW), NOW, NOW).receiptValidUntil())
        .isEqualTo(warningEnd);
    assertThat(
            policy().evaluate(observed(response, warningEnd), warningEnd, NOW).receiptValidUntil())
        .isEqualTo(graceEnd);
    assertThat(cutoff.receiptValidUntil()).isEqualTo(END);
  }

  @Test
  void scheduledCancellationKeepsPaidPeriodAndDoesNotUseCanceledAtAsEffectiveCutoff() {
    String response =
        RESPONSE
            .replace("\"cancel_at_period_end\":false", "\"cancel_at_period_end\":true")
            .replace("\"canceled_at\":null", "\"canceled_at\":1778000005");
    var scheduled = policy().evaluate(observed(response, NOW), NOW, null);
    assertThat(scheduled.status()).isEqualTo(Status.ACTIVE);
    assertThat(scheduled.receiptValidUntil()).isEqualTo(END);
    var atBoundary = policy().evaluate(observed(response, END), END, null);
    assertThat(atBoundary.status()).isEqualTo(Status.CANCELED);
    assertThat(atBoundary.cutoffAt()).isEqualTo(END);
    assertThat(policy().evaluate(observed(RESPONSE, END), END, null).reason())
        .isEqualTo(Reason.PERIOD_UNRESOLVED);
  }

  @Test
  void confirmedCanceledMayOmitPeriodButFutureTerminationCannotAuthorizeAMapping() {
    String response =
        RESPONSE
            .replace("\"active\"", "\"canceled\"")
            .replace("\"current_period_start\":1778000000", "\"current_period_start\":null")
            .replace("\"current_period_end\":1780592000", "\"current_period_end\":null");
    assertThat(policy().evaluate(observed(response, NOW), NOW, null).status())
        .isEqualTo(Status.CANCELED);
    var contradictory =
        observed(response.replace("\"ended_at\":null", "\"ended_at\":1780592000"), NOW);
    assertThat(policy().evaluate(contradictory, NOW, null).reason())
        .isEqualTo(Reason.CONTRADICTORY_PROVIDER_FACTS);
  }

  @Test
  void unsupportedIncompletePausedAndUnpaidNeverAcquireAnInventedPositiveOrGraceState() {
    for (String status :
        new String[] {"incomplete", "incomplete_expired", "paused", "unpaid", "future_status"}) {
      var result = observed(RESPONSE.replace("\"active\"", "\"" + status + "\""), NOW);
      assertThat(policy().evaluate(result, NOW, NOW).resolved()).as(status).isFalse();
    }
    var wrongBinding = observed(RESPONSE.replace("cus_expected", "cus_other"), NOW);
    assertThat(policy().evaluate(wrongBinding, NOW, null).reason())
        .isEqualTo(Reason.PROVIDER_UNRESOLVED);
  }

  @Test
  void requiresExplicitFinitePositiveTimingInsteadOfDefaultRecoveryOrObservationWindows() {
    assertThatThrownBy(() -> new RecoveryTiming(Duration.ZERO, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecoveryTiming(Duration.ofMinutes(1), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RecoveryTiming(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountHostingSubscriptionLifecyclePolicy(null, timing()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AccountHostingSubscriptionLifecyclePolicy(Duration.ofSeconds(1), null))
        .isInstanceOf(NullPointerException.class);
  }

  private static AccountHostingSubscriptionLifecyclePolicy policy() {
    return new AccountHostingSubscriptionLifecyclePolicy(Duration.ofSeconds(15), timing());
  }

  private static RecoveryTiming timing() {
    return new RecoveryTiming(Duration.ofSeconds(60), Duration.ofSeconds(120));
  }

  private static StripeHostingSubscriptionObserver.Result observed(String json, Instant readAt) {
    var observer =
        new StripeHostingSubscriptionObserver(
            new com.stripe.StripeClient(new ControlledTransport(json)),
            Clock.fixed(readAt, ZoneOffset.UTC),
            Duration.ofMillis(250),
            Duration.ofSeconds(1));
    return observer.observe(
        new StripeHostingSubscriptionObserver.ExpectedBinding(
            "sub_expected",
            SUBSCRIPTION,
            "cus_expected",
            "pm_expected",
            "price_expected",
            1,
            StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
            OPERATION,
            false));
  }

  private record ControlledTransport(String json) implements StripeResponseGetter {
    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      assertThat(method).isEqualTo(RequestMethod.GET);
      assertThat(path).isEqualTo("/v1/subscriptions/sub_expected");
      return (T) new com.stripe.StripeClient(this).deserialize(json, mode);
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      throw new AssertionError("No streaming or provider mutation is allowed");
    }
  }
}
