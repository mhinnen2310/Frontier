CREATE UNIQUE INDEX uq_dynamic_event_source_open
  ON world_events ((payload->>'sourceKey'))
  WHERE payload ? 'sourceKey' AND state IN ('SCHEDULED','ANNOUNCED','ACTIVE');
ALTER TABLE dynamic_event_responses
  ADD CONSTRAINT dynamic_event_response_bounded CHECK(contribution BETWEEN 1 AND 1000000000);
ALTER TABLE kingdom_war_approvals
  ADD CONSTRAINT uq_kingdom_war_approval_consumption UNIQUE(consumed_by);
CREATE INDEX idx_security_damage_stale ON damage_journal(authorized_at)
  WHERE mutation_state='AUTHORIZED';
CREATE INDEX idx_security_reservation_stale ON stock_reservations(expires_at)
  WHERE status='ACTIVE';
