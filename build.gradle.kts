plugins {
    java
}

group = "dev.ivoshow"
version = "1.0.0"

// Paper 26.2 build 126 -- matches the paper-26.2-126.jar used by the server.
val paperApiVersion = "26.2.build.126-stable"
val pluginApiVersion = "26.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // GriefPrevention, for the optional land-trust integration.
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // Optional at runtime: the hook class is only loaded when the plugin is present.
    compileOnly("com.github.TechFortress:GriefPrevention:18.0.0")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

java {
    // Paper 26.2 runs on Java 25; the API jar is compiled to class file 69.
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:-deprecation")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to pluginApiVersion
    )
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("${project.name}-${project.version}.jar")
}
