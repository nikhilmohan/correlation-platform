-- migration V4__pending_status.sql
-- Ordering-race fix: park a status change that references an alarm which has not yet been
-- persisted on the ingest path. On the P3 live path the Correlation Engine correlates fast and
-- can emit AlarmStatusChange(correlated) BEFORE the Alarm Manager has persisted that specific
-- alarm from alarms.enriched.live. When the status change wins the race, applyState previously
-- saw the alarm "unknown" and DROPPED it (no-op) — the alarm then persisted as 'open' and stayed
-- 'open' forever. This table durably PARKS such a pending status keyed by alarm_id; the ingest
-- path re-applies it after persisting the alarm, then deletes the entry.
--
-- Durability: a real table (survives restart), so a parked status is never lost between the
-- status-change arriving and the alarm being ingested. Last-write-wins per alarm_id by
-- changed_at (the state machine is monotonic toward 'correlated', so keeping the latest pending
-- status is correct): e.g. in-progress then correlated arriving before persist ends 'correlated'.
CREATE TABLE live_alarm.pending_status (
  alarm_id           text        PRIMARY KEY,
  new_status         text        NOT NULL,
  source             text,
  changed_at         timestamptz,
  caused_by_event_id text,
  received_at        timestamptz NOT NULL
);

-- Diagnostic index (parked backlog age); not on the hot path.
CREATE INDEX idx_pending_status_received_at ON live_alarm.pending_status (received_at);
