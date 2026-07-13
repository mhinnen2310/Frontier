plugins { `java-library` }
dependencies {
    api(project(":frontier-api"))
    implementation("io.micrometer:micrometer-core:1.15.2")
}
