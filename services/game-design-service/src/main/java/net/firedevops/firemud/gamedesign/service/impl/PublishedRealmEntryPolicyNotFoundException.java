package net.firedevops.firemud.gamedesign.service.impl;

public class PublishedRealmEntryPolicyNotFoundException extends RuntimeException {
  public PublishedRealmEntryPolicyNotFoundException() {
    super(
        "Published realm-entry policy is unavailable for the exact tenant, version, and selector");
  }
}
