package org.admany.quantified.api.interfaces;

import org.admany.quantified.api.builders.QuantifiedCacheBuilder;
import org.admany.quantified.api.builders.QuantifiedHybridBuilder;
import org.admany.quantified.api.builders.QuantifiedNetworkBuilder;
import org.admany.quantified.api.builders.QuantifiedTaskBuilder;

public interface ConnectedMod {
    String getModId();
    String getVersion();
    String getDisplayName();
    ModStatistics getStatistics();
    QuantifiedTaskBuilder task(String name);
    QuantifiedCacheBuilder cache(Enum<?> cacheType);
    QuantifiedHybridBuilder hybrid(String name);
    QuantifiedNetworkBuilder network(String channel);
    void disconnect();
}