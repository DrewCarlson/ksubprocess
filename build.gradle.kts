plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless)
    //alias(libs.plugins.completeKotlin)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.atomicfu)
}

repositories {
    mavenCentral()
}

version = System.getenv("GITHUB_REF")?.substringAfter("refs/tags/v", version.toString()) ?: version

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}
    jvmToolchain(11)
    jvm()
    macosX64()
    macosArm64()
    mingwX64("windows")
    configure(listOf(linuxX64(), linuxArm64())) {
        compilations.named("main") {
            cinterops {
                create("runprocess") {
                    defFile(project.file("src/linuxMain/cinterop/runprocess.def"))
                }
            }
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            explicitApi()
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        matching { !it.name.startsWith("common") && !it.name.startsWith("jvm") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
        commonMain {
            dependencies {
                api(libs.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.coroutines.test)
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val posixMain = create("posixMain") {
            dependsOn(commonMain.get())
        }

        linuxMain {
            dependsOn(posixMain)
        }

        macosMain {
            dependsOn(posixMain)
        }

        val posixTest = create("posixTest") {
            dependsOn(commonTest.get())
        }

        linuxTest {
            dependsOn(posixTest)
        }

        macosTest {
            dependsOn(posixTest)
        }

        jvmTest {
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
