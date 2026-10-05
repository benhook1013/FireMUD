package net.firedevops.firemud.accountservice.client;

/** Account-only owner read port. No implementation is wired until Game Design publishes it. */
public interface GameDesignAccountTenantAssociationOwnerClient {
  OwnerApprovedAccountTenantAssociation resolveApprovedAssociation(long legacyAccountTenantId);
}
