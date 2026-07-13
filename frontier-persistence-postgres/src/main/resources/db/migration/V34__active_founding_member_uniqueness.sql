UPDATE settlement_founding_expedition_members m
SET status = CASE e.status
  WHEN 'COMPLETED' THEN CASE WHEN m.status='ACCEPTED' THEN 'FOUNDED' ELSE 'EXPIRED' END
  WHEN 'CANCELLED' THEN 'CANCELLED'
  WHEN 'EXPIRED' THEN 'EXPIRED'
  ELSE m.status
END
FROM settlement_founding_expeditions e
WHERE e.id=m.expedition_id
  AND e.status IN ('COMPLETED','CANCELLED','EXPIRED');

WITH duplicate_expeditions AS (
  SELECT expedition_id
  FROM (
    SELECT m.expedition_id,
           row_number() OVER (
             PARTITION BY m.player_id
             ORDER BY e.created_at,e.id
           ) AS occurrence
    FROM settlement_founding_expedition_members m
    JOIN settlement_founding_expeditions e ON e.id=m.expedition_id
    WHERE m.status='ACCEPTED'
  ) ranked
  WHERE occurrence > 1
)
UPDATE settlement_founding_expeditions
SET status='REVIEW_REQUIRED',version=version+1
WHERE id IN (SELECT expedition_id FROM duplicate_expeditions);

UPDATE settlement_founding_expedition_members m
SET status='REVIEW_REQUIRED'
FROM settlement_founding_expeditions e
WHERE e.id=m.expedition_id AND e.status='REVIEW_REQUIRED';

CREATE UNIQUE INDEX uq_active_founding_member
  ON settlement_founding_expedition_members(player_id)
  WHERE status = 'ACCEPTED';
