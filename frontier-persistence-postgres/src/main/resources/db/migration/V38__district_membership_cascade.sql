ALTER TABLE district_memberships
  DROP CONSTRAINT district_memberships_district_id_role_key_fkey,
  ADD CONSTRAINT fk_district_membership_role
    FOREIGN KEY(district_id,role_key)
    REFERENCES district_roles(district_id,role_key)
    ON DELETE CASCADE;
