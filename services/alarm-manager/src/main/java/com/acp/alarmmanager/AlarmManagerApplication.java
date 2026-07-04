package com.acp.alarmmanager;

import com.acp.alarmmanager.config.AlarmManagerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Alarm Manager — sole owner of live alarm state. In-line on the P3 real-time path between the
 * Enrichment Service and the Correlation Engine: consumes {@code alarms.enriched.live}, persists
 * each live alarm (lifecycle {@code open}) into the owned {@code live_alarm} PostgreSQL schema,
 * and republishes on {@code alarms.persisted.live}. Maintains lifecycle STATE from
 * {@code alarms.status.changed} and correlation ROLE + incident linkage from
 * {@code correlation.results}; serves the live alarm query API to the web-ui.
 */
@SpringBootApplication
@EnableConfigurationProperties(AlarmManagerProperties.class)
public class AlarmManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlarmManagerApplication.class, args);
    }
}
