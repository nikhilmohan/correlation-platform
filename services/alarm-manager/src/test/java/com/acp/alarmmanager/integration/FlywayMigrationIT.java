package com.acp.alarmmanager.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC23 — V1 baseline creates the owned {@code live_alarm} schema + the three base tables; the full
 * V1->V2->V3 chain applies cleanly. AC24 — all tables live in {@code live_alarm} (never
 * {@code public}); post-migration shapes (in-progress constraint, audit source/changed_at, NOT
 * NULL alarm_type, the indexes) are present.
 */
class FlywayMigrationIT extends PostgresIntegrationBase {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = dataSource();
        migrate(ds);
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void liveAlarmSchemaAndTablesCreatedInLiveAlarmNeverPublic() {
        // schema exists
        Integer schema = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'live_alarm'",
                Integer.class);
        assertThat(schema).isEqualTo(1);

        // the three base tables exist in live_alarm, none in public
        for (String table : List.of("alarm", "state_transition", "processed_event")) {
            Integer inLiveAlarm = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'live_alarm' AND table_name = ?
                    """, Integer.class, table);
            assertThat(inLiveAlarm).as(table + " in live_alarm").isEqualTo(1);
            Integer inPublic = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?
                    """, Integer.class, table);
            assertThat(inPublic).as(table + " NOT in public").isEqualTo(0);
        }

        // Flyway history inside live_alarm records V1, V2, V3 all successful.
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM live_alarm.flyway_schema_history
                WHERE success = true ORDER BY installed_rank
                """, String.class);
        assertThat(versions).contains("1", "2", "3");
    }

    @Test
    void postMigrationShapesPresent() {
        // NOT NULL alarm_type column
        Integer alarmTypeNotNull = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='live_alarm' AND table_name='alarm'
                  AND column_name='alarm_type' AND is_nullable='NO'
                """, Integer.class);
        assertThat(alarmTypeNotNull).isEqualTo(1);

        // audit source/changed_at columns
        List<String> auditCols = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='live_alarm' AND table_name='state_transition'
                """, String.class);
        assertThat(auditCols).contains("source", "changed_at");

        // lifecycle check constraint admits in-progress: insert should succeed
        seedAlarm("ALM-CHK", "in-progress");
        Integer inProgress = jdbc.queryForObject(
                "SELECT count(*) FROM live_alarm.alarm WHERE lifecycle_state='in-progress'",
                Integer.class);
        assertThat(inProgress).isEqualTo(1);

        // indexes present
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname='live_alarm'
                """, String.class);
        assertThat(indexes).contains("idx_alarm_alarm_type", "gin_alarm_trail_ids",
                "idx_transition_alarm_id", "uq_transition_open_ingest");
    }

    private void seedAlarm(String alarmId, String lifecycleState) {
        jdbc.update("""
                INSERT INTO live_alarm.alarm (
                  alarm_id, managed_object_id, event_type, probable_cause, alarm_type,
                  perceived_severity, wire_state, raised_at, trail_ids, lifecycle_state, role,
                  published, raw_envelope, created_at, updated_at)
                VALUES (?, 'Port:ne1-1-1', 'communicationsAlarm', 'lossOfSignal', 'PortDown',
                  'critical', 'raised', now(), '["trail-77"]'::jsonb, ?, 'none', false, '{}'::jsonb,
                  now(), now())
                """, alarmId, lifecycleState);
    }
}
