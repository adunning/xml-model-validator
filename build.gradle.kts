plugins {
    application
    alias(libs.plugins.spotless)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "ca.andrewdunning.xmlmodelvalidator.XmlValidationApplication"
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

spotless {

    java {
        importOrder()
        removeUnusedImports()
        forbidWildcardImports()
        forbidModuleImports()
        palantirJavaFormat().formatJavadoc(true)
        formatAnnotations()
    }

    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }

    format("misc") {
        target("*.gradle", ".gitattributes", "*.md", "*.toml", "*.yml", "*.yaml", ".gitignore")
        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
}

dependencies {
    implementation(libs.jing)
    implementation(libs.trang)
    implementation(libs.saxonHe)
    implementation(libs.schxslt2)
    implementation(libs.picocli)
    implementation(libs.tomlj)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<Exec>("smokeTest") {
    description = "Runs entrypoint.sh end-to-end smoke test."
    group = "verification"
    dependsOn(tasks.named("jar"))
    commandLine("bash", "scripts/smoke-test.sh", layout.projectDirectory.asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(tasks.named("smokeTest"))
}

tasks.named<Jar>("jar") {
    archiveBaseName = "xml-model-validator"
    archiveVersion = ""

    manifest {
        attributes(
            mapOf(
                "Main-Class" to application.mainClass.get(),
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            ),
        )
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(
        configurations.runtimeClasspath.map { classpath ->
            classpath.map { dep -> if (dep.isDirectory) dep else zipTree(dep) }
        },
    )

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register("printVersion") {
    group = "help"
    description = "Prints the project version for release scripts."
    val currentVersion = project.version.toString()
    doLast {
        println(currentVersion)
    }
}
