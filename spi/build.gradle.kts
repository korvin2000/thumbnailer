// The plugin contract (package thumbnailer.spi): JDK only, no Spring, so a plugin jar depends on nothing else.
plugins { `java-library` }

base { archivesName = "thumbnailer-spi" }

java { toolchain { languageVersion = JavaLanguageVersion.of(27) } }
