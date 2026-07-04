-- migration V2__add_in_progress_state_and_audit_source.sql
-- The lifecycle_state check constraint now admits the new 'in-progress' value. 'reverted-open'
-- is NOT a stored state — it is a transition TO 'open' distinguished by the audit reason.
ALTER TABLE live_alarm.alarm DROP CONSTRAINT IF EXISTS alarm_lifecycle_state_chk;
ALTER TABLE live_alarm.alarm ADD CONSTRAINT alarm_lifecycle_state_chk
  CHECK (lifecycle_state IN ('open', 'in-progress', 'correlated', 'cleared'));

-- audit table gains the originating source plus the payload changedAt
ALTER TABLE live_alarm.state_transition ADD COLUMN IF NOT EXISTS source     text;
ALTER TABLE live_alarm.state_transition ADD COLUMN IF NOT EXISTS changed_at timestamptz;
