package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Immutable full inventory read; the revision and every typed row come from one DB snapshot. */
public record CanonicalGameplayBindingInventorySnapshot(
    BigInteger inventoryRevision,
    List<CanonicalGameplayBindingInventoryEntry> bindings,
    List<CanonicalGameplayBindingTransitionSnapshot> transitions,
    List<CanonicalIssuerPartitionReservation> reservations,
    List<CanonicalGameplayBindingAccountIndexObligation> accountIndexObligations,
    List<CanonicalGameplayBindingIssuerIndexObligation> issuerIndexObligations,
    List<CanonicalGameplayBindingRegionBridgeObligation> regionBridgeObligations) {
  public CanonicalGameplayBindingInventorySnapshot {
    Objects.requireNonNull(inventoryRevision, "inventoryRevision");
    if (inventoryRevision.signum() < 0) {
      throw new IllegalArgumentException("inventoryRevision must not be negative");
    }
    bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
    reservations = List.copyOf(Objects.requireNonNull(reservations, "reservations"));
    accountIndexObligations =
        List.copyOf(Objects.requireNonNull(accountIndexObligations, "accountIndexObligations"));
    issuerIndexObligations =
        List.copyOf(Objects.requireNonNull(issuerIndexObligations, "issuerIndexObligations"));
    regionBridgeObligations =
        List.copyOf(Objects.requireNonNull(regionBridgeObligations, "regionBridgeObligations"));
    if (bindings.stream().anyMatch(row -> row.inventoryRevision().compareTo(inventoryRevision) > 0)
        || transitions.stream()
            .anyMatch(row -> row.inventoryRevision().compareTo(inventoryRevision) > 0)
        || reservations.stream()
            .anyMatch(row -> row.inventoryRevision().compareTo(inventoryRevision) > 0)) {
      throw new IllegalArgumentException("snapshot cannot contain rows newer than its revision");
    }
    if (accountIndexObligations.stream()
            .anyMatch(row -> row.inventoryRevision().compareTo(inventoryRevision) > 0)
        || issuerIndexObligations.stream()
            .anyMatch(row -> row.inventoryRevision().compareTo(inventoryRevision) > 0)
        || regionBridgeObligations.stream()
            .anyMatch(row -> row.inventoryRevision().compareTo(inventoryRevision) > 0)) {
      throw new IllegalArgumentException("snapshot cannot contain obligations newer than revision");
    }
  }
}
