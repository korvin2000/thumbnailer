// Sample plugin: the file-name parser as a META-INF/services MetadataExtractor; the backend bundles it (runtimeOnly).
plugins { `java-library` }

base { archivesName = "thumbnailer-names" }

java { toolchain { languageVersion = JavaLanguageVersion.of(27) } }

repositories { mavenCentral() }

dependencies {
    implementation(project(":spi"))
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
