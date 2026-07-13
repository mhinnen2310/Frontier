plugins { `java-library` }
dependencies { api(project(":frontier-api")); api(platform("org.junit:junit-bom:6.0.3")); api("org.junit.jupiter:junit-jupiter-api") }
