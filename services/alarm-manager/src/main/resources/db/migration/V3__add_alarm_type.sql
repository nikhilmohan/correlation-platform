-- migration V3__add_alarm_type.sql
-- alarm_type carries AlarmEvent.alarmType, the platform canonical alarm-type join token
-- (distinct from event_type / probable_cause). Required on every AlarmEvent, so NOT NULL.
ALTER TABLE live_alarm.alarm ADD COLUMN IF NOT EXISTS alarm_type text;
-- backfill is N/A for the MVP live-only store (no historical rows); enforce NOT NULL:
ALTER TABLE live_alarm.alarm ALTER COLUMN alarm_type SET NOT NULL;
-- canonical join-token index (forward-looking):
CREATE INDEX IF NOT EXISTS idx_alarm_alarm_type ON live_alarm.alarm (alarm_type);
