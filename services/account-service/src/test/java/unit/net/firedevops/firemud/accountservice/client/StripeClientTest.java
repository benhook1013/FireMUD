package net.firedevops.firemud.accountservice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StripeClientTest {
  @Test
  void calculatesPlatformFeeBasedOnPercent() {
    StripeClient client = new StripeClient("key", 5.0);
    assertEquals(50L, client.calculatePlatformFee(1000L));
  }
}
