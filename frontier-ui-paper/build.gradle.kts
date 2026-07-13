plugins { `java-library` }
dependencies {
    api(project(":frontier-api"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.60-beta")
}
