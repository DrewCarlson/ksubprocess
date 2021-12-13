@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.dokka)
}

apply(from = "gradle/publish.gradle.kts")

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    macosX64("macos")
    macosArm64()
    mingwX64("windows")
    linuxX64("linux") {
        compilations.named("main") {
            cinterops {
                create("runprocess") {
                    defFile(project.file("src/linuxMain/cinterop/runprocess.def"))
                }
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
        val commonMain by getting {
            dependencies {
                implementation(libs.ktor.io)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val macosMain by getting
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
}


tasks.withType<Test> {
    dependsOn(":testprograms:shadowJar")
    environment("TEST", "TESTVAL")
    environment("PT_JAVA_EXE", org.gradle.internal.jvm.Jvm.current().javaExecutable.absolutePath)
    doFirst {
        environment(
            "PT_JAR",
            project(":testprograms").tasks.named<Jar>("shadowJar").get().archiveFile.get()
        )
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest> {
    dependsOn(":testprograms:shadowJar")
    environment("TEST", "TESTVAL")
    environment("PT_JAVA_EXE", org.gradle.internal.jvm.Jvm.current().javaExecutable.absolutePath)
    doFirst {
        environment(
            "PT_JAR",
            project(":testprograms").tasks.named<Jar>("shadowJar").get().archiveFile.get()
        )
    }
}
