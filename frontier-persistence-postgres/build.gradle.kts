plugins { `java-library` }
dependencies {
    api(project(":frontier-api"))
    implementation(project(":frontier-city"))
    implementation(project(":frontier-influence"))
    implementation(project(":frontier-economy"))
    implementation(project(":frontier-npc"))
    implementation(project(":frontier-warfare"))
    implementation(project(":frontier-repair"))
    implementation(project(":frontier-world"))
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.flywaydb:flyway-core:11.10.5")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.5")
    implementation("org.slf4j:slf4j-api:2.0.17")
}

tasks.test {
    systemProperty("frontier.test.database.url", System.getenv("FRONTIER_TEST_DATABASE_URL") ?: "")
    systemProperty("frontier.scale.database.url", System.getenv("FRONTIER_SCALE_DATABASE_URL") ?: "")
}

tasks.register<Test>("scaleTest") {
    group = "verification"
    description = "Runs the 50/100/250/500-player synthetic PostgreSQL load matrix."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("nl.frontier.persistence.MultiplayerScaleTest")
    systemProperty("frontier.scale.database.url", System.getenv("FRONTIER_SCALE_DATABASE_URL") ?: "")
}
