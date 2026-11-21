package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;

/**
 * Pre-v1 gate waiver (ADR 0185) stand-ins for canonical PLAY, composed once per process over the
 * Game Session Coordination Redis. Public callers get the startup bootstrap and budgets; the issuer
 * state source, INITIAL attestor and region incarnation source stay package-private for the PLAY
 * composition in this package.
 */
public final class PreV1CanonicalPlayStandIns {
  /** Tenant gameplay tag allocation version shared by every canonical PLAY owner. */
  public static final int TAG_ALLOCATION_VERSION = 1;

  private final RedisClient coordination;
  private final RedisScriptCatalog scripts;
  private final CanonicalGameplayTenantGameplayTagRepository tags;
  private final PreV1CurrentIssuerStateSource issuerState;
  private final PreV1LocalIncarnationAttestor attestor;
  private final PreV1RegionIncarnationSource regionIncarnation;
  private final PreV1CanonicalGameplayStoreBootstrap storeBootstrap;
  private final PreV1CanonicalPlayBudgets budgets;

  PreV1CanonicalPlayStandIns(
      RedisClient coordination,
      RedisScriptCatalog scripts,
      CanonicalGameplayTenantGameplayTagRepository tags,
      PreV1CurrentIssuerStateSource issuerState,
      PreV1LocalIncarnationAttestor attestor,
      PreV1RegionIncarnationSource regionIncarnation,
      PreV1CanonicalGameplayStoreBootstrap storeBootstrap,
      PreV1CanonicalPlayBudgets budgets) {
    this.coordination = Objects.requireNonNull(coordination, "coordination");
    this.scripts = Objects.requireNonNull(scripts, "scripts");
    this.tags = Objects.requireNonNull(tags, "tags");
    this.issuerState = Objects.requireNonNull(issuerState, "issuerState");
    this.attestor = Objects.requireNonNull(attestor, "attestor");
    this.regionIncarnation = Objects.requireNonNull(regionIncarnation, "regionIncarnation");
    this.storeBootstrap = Objects.requireNonNull(storeBootstrap, "storeBootstrap");
    this.budgets = Objects.requireNonNull(budgets, "budgets");
  }

  public PreV1CanonicalGameplayStoreBootstrap storeBootstrap() {
    return storeBootstrap;
  }

  public PreV1CanonicalPlayBudgets budgets() {
    return budgets;
  }

  RedisClient coordination() {
    return coordination;
  }

  RedisScriptCatalog scripts() {
    return scripts;
  }

  CanonicalGameplayTenantGameplayTagRepository tags() {
    return tags;
  }

  PreV1CurrentIssuerStateSource issuerState() {
    return issuerState;
  }

  PreV1LocalIncarnationAttestor attestor() {
    return attestor;
  }

  PreV1RegionIncarnationSource regionIncarnation() {
    return regionIncarnation;
  }
}
