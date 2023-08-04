@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binaryCompat)
    alias(libs.plugins.spotless)
    alias(libs.plugins.completeKotlin)
    alias(libs.plugins.mavenPublish)
}

repositories {
    mavenCentral()
}

version = System.getenv("GITHUB_REF")?.substringAfter("refs/tags/v", version.toString()) ?: version

kotlin {
    jvm()
    macosX64()
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
            explicitApi()
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        val commonMain by getting {
            dependencies {
                api(libs.okio.core)
                api(libs.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.coroutines.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val posixMain by creating {
            dependsOn(commonMain)
        }

        val linuxMain by getting {
            dependsOn(posixMain)
        }
        val macosMain by creating {
            dependsOn(posixMain)
        }
        val macosX64Main by getting {
            dependsOn(macosMain)
        }
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

spotless {
    kotlin {
        target("**/**.kt")
        ktlint(libs.versions.ktlint.get())
            //.setUseExperimental(true)
            /*.editorConfigOverride(mapOf(
                "disabled_rules" to "no-wildcard-imports,trailing-comma,filename"
            ))*/
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
