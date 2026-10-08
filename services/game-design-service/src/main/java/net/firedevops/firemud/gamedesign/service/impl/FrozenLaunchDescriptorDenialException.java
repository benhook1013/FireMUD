package net.firedevops.firemud.gamedesign.service.impl;

/** A deterministic launch business denial whose exact request outcome is stored by the owner. */
final class FrozenLaunchDescriptorDenialException extends IllegalArgumentException {
  private final String failureCode;

  static FrozenLaunchDescriptorDenialException create(String failureCode, String detail) {
    return new FrozenLaunchDescriptorDenialException(failureCode, failureCode + ": " + detail);
  }

  private FrozenLaunchDescriptorDenialException(String failureCode, String message) {
    super(message);
    this.failureCode = failureCode;
  }

  static FrozenLaunchDescriptorDenialException fromStored(String failureCode, String message) {
    return new FrozenLaunchDescriptorDenialException(failureCode, message);
  }

  String failureCode() {
    return failureCode;
  }
}
