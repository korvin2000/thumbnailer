// Build glue only: the Angular CLI does the work; skipped with -PskipFrontend so the backend builds alone.
val skip = providers.gradleProperty("skipFrontend").isPresent
val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
    onlyIf { !skip }
    commandLine(npm, "ci")
    inputs.files("package.json", "package-lock.json")
    outputs.file("node_modules/.package-lock.json")
}

tasks.register<Exec>("npmBuild") {
    onlyIf { !skip }
    dependsOn(npmInstall)
    commandLine(npm, "run", "build")
    inputs.files(fileTree("src"), fileTree("public"), "angular.json", "package.json", "tsconfig.json", "tsconfig.app.json")
    outputs.dir("dist/frontend/browser")
}
