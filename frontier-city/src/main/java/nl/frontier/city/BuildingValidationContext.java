package nl.frontier.city;

public record BuildingValidationContext(
    boolean controlledBySettlement,
    boolean overlap,
    boolean districtCompatible,
    boolean roadConnected) {}
