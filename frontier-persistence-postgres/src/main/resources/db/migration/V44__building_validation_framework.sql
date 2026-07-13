ALTER TABLE city_buildings
  ADD CONSTRAINT chk_city_building_status
  CHECK(status IN ('PLANNED','UNDER_CONSTRUCTION','VALIDATING','ACTIVE','DAMAGED','DISABLED','DESTROYED','ABANDONED'));

ALTER TABLE city_buildings
  ADD CONSTRAINT chk_city_building_validation_version CHECK(validation_version >= 0);

ALTER TABLE building_validation_history
  ADD CONSTRAINT chk_building_validation_from_state
    CHECK(from_state IS NULL OR from_state IN ('PLANNED','UNDER_CONSTRUCTION','VALIDATING','ACTIVE','DAMAGED','DISABLED','DESTROYED','ABANDONED')),
  ADD CONSTRAINT chk_building_validation_to_state
    CHECK(to_state IN ('PLANNED','UNDER_CONSTRUCTION','VALIDATING','ACTIVE','DAMAGED','DISABLED','DESTROYED','ABANDONED'));

CREATE INDEX idx_building_specialization_eligibility
  ON city_buildings(district_id,building_type,integrity)
  WHERE status IN ('ACTIVE','DAMAGED') AND district_id IS NOT NULL;
