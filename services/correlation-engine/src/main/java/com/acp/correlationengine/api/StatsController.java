package com.acp.correlationengine.api;

import com.acp.correlationengine.api.dto.StatsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /stats} — aggregate counts for the web-ui Correlation Stats module. Keeps the raw
 * counts (alarm-reduction ratio derivable) and adds {@code correlatedAlarmCount} (auto-correlation
 * rate, D1) and eval-mode {@code rcaAccuracy} (D2). No contract change.
 */
@RestController
public class StatsController {

    private final StatsAggregator aggregator;

    public StatsController(StatsAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @GetMapping("/stats")
    public StatsView stats() {
        return aggregator.snapshot();
    }
}
