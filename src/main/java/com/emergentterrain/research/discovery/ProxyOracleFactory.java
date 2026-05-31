package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b — builds a {@link ProxyOracle} for a {@link ProxySpec}.
 * Injected into {@link ProxyDiscoveryService} so the loop is testable with a fake
 * oracle; the production impl is {@link SummatoryProxyOracle}.
 */
public interface ProxyOracleFactory {

    ProxyOracle forSpec(ProxySpec spec);
}
