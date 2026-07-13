package nl.frontier.economy;

public record InfrastructureSurvey(
    int samples,
    int connectedSamples,
    int minimumWidth,
    int bridgeSamples,
    int tunnelSamples,
    int surfaceQuality,
    double maximumSlope,
    int brokenSegments,
    int destroyedBridges) {
  public double connectivity() {
    return samples == 0 ? 0 : (double) connectedSamples / samples;
  }
}
