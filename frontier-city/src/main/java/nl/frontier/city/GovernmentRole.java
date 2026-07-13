package nl.frontier.city;

import java.util.EnumSet;
import java.util.Set;

public enum GovernmentRole {
  MAYOR(EnumSet.allOf(CityPermission.class)),
  TREASURER(
      EnumSet.of(
          CityPermission.MANAGE_TREASURY,
          CityPermission.CHANGE_TAXES,
          CityPermission.START_REPAIR)),
  GENERAL(EnumSet.of(CityPermission.DECLARE_WAR, CityPermission.MANAGE_WORKERS)),
  ARCHITECT(
      EnumSet.of(
          CityPermission.CLAIM_TERRITORY,
          CityPermission.MANAGE_BUILDINGS,
          CityPermission.MANAGE_DISTRICTS)),
  BUILDER_MASTER(EnumSet.of(CityPermission.START_REPAIR, CityPermission.MANAGE_WORKERS)),
  DIPLOMAT(EnumSet.of(CityPermission.ACCEPT_PEACE)),
  CITIZEN(EnumSet.noneOf(CityPermission.class)),
  RECRUIT(EnumSet.noneOf(CityPermission.class));

  private final Set<CityPermission> permissions;

  GovernmentRole(Set<CityPermission> permissions) {
    this.permissions = Set.copyOf(permissions);
  }

  public Set<CityPermission> permissions() {
    return permissions;
  }
}
