package com.acp.enrichment.ruleset;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Selects exactly one {@link Ruleset} per alarm by equality lookup of the envelope {@code source}
 * field in the {@link RulesetRegistry}, falling back to the {@code default} ruleset when no
 * source-specific ruleset matches (resolves design open question #3; criterion 13).
 */
@Component
public class RulesetSelector {

    private final RulesetRegistry registry;
    private final MeterRegistry meters;

    public RulesetSelector(RulesetRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
    }

    /**
     * @param source the envelope {@code source} value
     * @return the resolved ruleset (source-specific or {@code default}); never {@code null}
     */
    public Ruleset select(String source) {
        if (!registry.hasSource(source)) {
            meters.counter("ruleset_default_fallback_total", "source", String.valueOf(source))
                    .increment();
            return registry.getDefault();
        }
        return registry.forSource(source);
    }
}
