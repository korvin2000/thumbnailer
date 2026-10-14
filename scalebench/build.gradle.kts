plugins { application }

java { toolchain { languageVersion = JavaLanguageVersion.of(27) } }

repositories { mavenCentral() }

dependencies {
    implementation("com.mortennobel:java-image-scaling:0.8.6") // NOBEL_* baselines (xview ScaleFactory)
}

// Candidates may use the Vector API (incubating in JDK 27) and FFM.
val vector = listOf("--add-modules", "jdk.incubator.vector")
tasks.withType<JavaCompile> { options.compilerArgs.addAll(vector + "-Xlint:-incubating") }

// The production resampler is measured from its source, never from a copy.
sourceSets.main {
    java.srcDir("../backend/src/main/java")
    java.include("scalebench/**", "thumbnailer/image/Resampler.java", "thumbnailer/image/Pixels.java", "thumbnailer/image/ProductionScaler.java")
}

application { mainClass = "scalebench.Bench" }

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    jvmArgs(vector + listOf("--enable-native-access=ALL-UNNAMED", "-Xmx6g", "-Xss4m"))
}
