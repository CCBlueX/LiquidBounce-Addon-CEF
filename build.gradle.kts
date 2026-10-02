plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

base {
    archivesName = project.property("archives_base_name") as String
    // The Minecraft version the add-on is built for goes into its version, e.g. 1.0.0+26.3, and CI publishes
    // every push to main as 1.0.0+26.3-SNAPSHOT with -Psnapshot
    version = "${project.property("mod_version")}+${libs.versions.minecraft.get()}" +
        if (providers.gradleProperty("snapshot").isPresent) "-SNAPSHOT" else ""
    group = project.property("maven_group") as String
}

repositories {
    mavenCentral()
    // Lets you test against a locally built client (`./gradlew publishToMavenLocal` in LiquidBounce).
    mavenLocal()
    maven {
        name = "CCBlueX Releases"
        url = uri("https://maven.ccbluex.net/releases")
    }
    maven {
        name = "CCBlueX Snapshots"
        url = uri("https://maven.ccbluex.net/snapshots")
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
}

// `./gradlew runClientGameTest` starts the client with the add-on and runs src/gametest.
loom {
    accessWidenerPath = file("src/main/resources/liquidbounce-cef.accesswidener")
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "liquidbounce-cef-gametest"
        enableGameTests = false
    }
}

loom.runs.named("clientGameTest") {
    // A loader error would otherwise wait on a dialog nobody sees; the client's own fatal errors go to
    // the log when CI is set.
    systemProperties.put("fabric.noGui", "true")
    environmentVars.put("CI", "true")
}

// The run directory is wiped before every run and the client then downloads JCEF into it. Point it at a
// directory to keep the download in: -Pgametest.libraries=$HOME/.cache/lb/jcef
tasks.named<JavaExec>("runClientGameTest") {
    providers.gradleProperty("gametest.libraries").orNull?.let { environment("LB_BROWSER_LIBRARIES", it) }
}

// JCEF's Java side comes from the java-cef submodule, the natives of the same commit are downloaded at runtime.
sourceSets {
    create("jcef") {
        java {
            srcDir("java-cef/java")
            exclude("**/tests/**")
        }
    }
    main {
        compileClasspath += sourceSets["jcef"].output
        runtimeClasspath += sourceSets["jcef"].output
    }
    named("gametest") {
        runtimeClasspath += sourceSets["jcef"].output
    }
}

val jcefCommit = providers.exec {
    commandLine("git", "-C", "java-cef", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)

val generateJcefCommit = tasks.register("generateJcefCommit") {
    val output = layout.buildDirectory.file("generated/jcef/jcef.commit")
    inputs.property("commit", jcefCommit)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(jcefCommit.get())
    }
}

// Two things to leave alone here:
//
// 1. There is no `mappings(...)` line. LiquidBounce declares none either, and Loom defaults to
//    Mojang official mappings for this Minecraft version. A different mapping set produces an
//    add-on that compiles and then fails on every Minecraft call.
// 2. Dependencies use plain `implementation`, not `modImplementation`. This Loom version has no
//    remapping step - the development and production namespaces are both Mojang official - so the
//    `mod*` configurations do not exist. LiquidBounce's own build does the same.
dependencies {
    minecraft(libs.minecraft)

    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.kotlin)

    // The client itself; there is no separate API artifact. Clients that still came with MCEF must not bring it
    // in a second time.
    implementation(libs.liquidbounce) {
        exclude(group = "net.ccbluex", module = "mcef")
    }

    // The client ships them
    compileOnly(libs.lwjgl.egl)
    compileOnly(libs.okhttp)

    testImplementation(libs.okhttp)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Gradle keeps a resolved snapshot for a day; the client publishes one on every push to nextgen.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.processResources {
    val modVersion = providers.gradleProperty("mod_version").zip(libs.versions.minecraft) { version, minecraft ->
        "$version+$minecraft"
    }
    val minecraftVersion = libs.versions.minecraft
    val loaderVersion = libs.versions.fabric.loader
    val fabricKotlinVersion = libs.versions.fabric.kotlin

    inputs.property("version", modVersion)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    inputs.property("fabric_kotlin_version", fabricKotlinVersion)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion.get(),
                "minecraft_version" to minecraftVersion.get(),
                "loader_version" to loaderVersion.get(),
                "fabric_kotlin_version" to fabricKotlinVersion.get(),
            )
        )
    }

    from(generateJcefCommit)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.versions.jdk.get().toInt()
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()

    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }
}

kotlin {
    compilerOptions {
        jvmToolchain(libs.versions.jdk.get().toInt())
        // LiquidBounce is compiled with preview features, which marks its classes as pre-release
        freeCompilerArgs.add("-Xskip-prerelease-check")
        // As in LiquidBounce, whose API uses them
        freeCompilerArgs.add("-Xcompanion-blocks-and-extensions")
    }
}

tasks.jar {
    from(sourceSets["jcef"].output)
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// LiquidBounce bundles the add-on from the CCBlueX Maven
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = base.archivesName.get()
            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "CCBlueX"
            url = uri(
                if (version.toString().endsWith("-SNAPSHOT")) {
                    "https://maven.ccbluex.net/snapshots"
                } else {
                    "https://maven.ccbluex.net/releases"
                }
            )
            credentials {
                username = System.getenv("MAVEN_TOKEN_NAME")
                password = System.getenv("MAVEN_TOKEN_SECRET")
            }
        }
    }
}
