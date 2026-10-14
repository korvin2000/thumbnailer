plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "thumbnailer"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(27) } }

repositories { mavenCentral() }

// Undertow instead of Tomcat: Spring Boot 4 ships no Undertow, web/UndertowServer is the server factory.
configurations.all { exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat") }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-web-server") // the server SPI, otherwise only via the Tomcat starter
    implementation("io.undertow.ee:undertow-servlet:2.0.3.Final") // Undertow EE: Servlet 6.1 + Jakarta WebSocket 2.2
    implementation("io.undertow.ee:undertow-websockets:2.0.3.Final")
    implementation("io.undertow:undertow-core:2.4.4.Final") // the HTTP core under Undertow EE (its own pin, made explicit)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.h2database:h2") // SQL metadata; its MVStore API backs the alternative cover store (blobs: mvstore)
    implementation("org.cojen:tupl:1.9.1") // default cover store (AGPL-3.0); it declares open ranges, so pin them:
    implementation("org.cojen:cojen-maker:2.8.4")
    implementation("org.cojen:cojen-dirmi:2.4.4")
    implementation("com.dynatrace.hash4j:hash4j:0.31.0") // wyhash final4 for signatures
    // Natives (src/main/resources/natives/<os>-<arch>/, loaded by natives.LibraryLoader): libturbojpeg 3.2.1 over FFM,
    // webp4j 2.5.0's JNI library on libwebp 1.6.0 (its Java side vendored as dev.matrixlab.webp4j), 7-Zip-JBinding 26.03:
    implementation(files("libs/sevenzipjbinding-26.03-2.3.jar")) // the fork's Java API (github.com/korvin2000/sevenzipjbinding)
    implementation("com.drewnoakes:metadata-extractor:2.21.0")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-jpeg:3.15.3") // what libjpeg-turbo refuses: CMYK/YCCK, 12-bit
    runtimeOnly("com.twelvemonkeys.imageio:imageio-webp:3.15.3") // animated WebP (libwebp's simple decoder takes stills only)
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

// TuplDB and TurboJPEG (FFM), webp4j and 7-Zip-JBinding (JNI) call native code: grant it, or the JDK warns (27 does) and a later one blocks.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"
// image/Resampler's SIMD path (Vector API, incubating in JDK 27); without the module it runs its scalar path.
val vector = listOf("--add-modules", "jdk.incubator.vector")
tasks.withType<JavaCompile> { options.compilerArgs.addAll(vector + "-Xlint:-incubating") }

tasks.bootJar {
    archiveBaseName = "thumbnailer"
    manifest { attributes("Enable-Native-Access" to "ALL-UNNAMED", "Implementation-Version" to project.version) } // About: version
}
tasks.bootRun { jvmArgs(vector + nativeAccess) }
tasks.jar { enabled = false }
tasks.test {
    useJUnitPlatform()
    jvmArgs(vector + nativeAccess)
    systemProperty("org.cojen.tupl.core.DirectPageOpsSelector", "ffm")
    systemProperty("thumbnailer.natives", layout.buildDirectory.dir("natives").get().asFile.path) // not the user's cache
}
