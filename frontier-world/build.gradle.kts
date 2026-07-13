plugins { `java-library` }
dependencies {
    api(project(":frontier-api"))
    implementation(project(":frontier-city"))
    implementation(project(":frontier-economy"))
    implementation(project(":frontier-warfare"))
}
