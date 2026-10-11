plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "thumbnailer"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(26) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.h2database:h2") // also the MVStore API behind the thumbnail BlobStore
    implementation("com.drewnoakes:metadata-extractor:2.21.0")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-jpeg:3.15.3")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-webp:3.15.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val skipFrontend = providers.gradleProperty("skipFrontend").isPresent

tasks.processResources {
    if (!skipFrontend) {
        dependsOn(":frontend:npmBuild")
        from(rootProject.file("frontend/dist/frontend/browser")) { into("static") }
    }
}

tasks.bootJar { archiveBaseName = "thumbnailer" }
tasks.jar { enabled = false }
tasks.test { useJUnitPlatform() }
