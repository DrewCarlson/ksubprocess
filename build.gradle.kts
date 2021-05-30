plugins {
    kotlin("multiplatform") version "1.5.10"
    //id 'org.jetbrains.dokka' version "$dokka_version"
    `maven-publish`
}

repositories {
    mavenCentral()
}

/*node {
    version = "$node_version"

    download = true
    nodeModulesDir = file(buildDir)
}

task prepareNodePackage(type: Copy) {
    from("npm") {
        include 'package.json'
        expand(project.properties + [kotlinDependency: ""])
    }
    from("npm") {
        exclude 'package.json'
    }
    into "$node.nodeModulesDir"
}

npmInstall.dependsOn prepareNodePackage*/


kotlin {
    jvm()
    macosX64("macos")
    linuxX64("linux") {
        compilations.named("main") {
            cinterops {
                create("runprocess") {
                    defFile(project.file("src/linuxMain/cinterop/runprocess.def"))
                }
            }
        }
    }
    mingwX64("windows")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-io:1.6.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        named("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }

    kotlin.sourceSets.all {
        languageSettings {
            useExperimentalAnnotation("kotlin.RequiresOptIn")
        }
    }
}

/*linuxTest {
    // expected in the environment test
    environment['TEST'] = 'TESTVAL'

    // required for subprocess tests
    dependsOn ":testprograms:jar"
    environment['PT_JAVA_EXE'] = jvmTest.executable
    doFirst {
        environment['PT_JAR'] = project(":testprograms").tasks["jar"].archiveFile.get()
    }

}

windowsTest {
    // expected in the environment test
    environment['TEST'] = 'TESTVAL'

    // required for subprocess tests
    dependsOn ":testprograms:jar"
    environment['PT_JAVA_EXE'] = jvmTest.executable
    doFirst {
        environment['PT_JAR'] = project(":testprograms").tasks["jar"].archiveFile.get()
    }
}

jvmTest {
    // expected in the environment test
    environment['TEST'] = 'TESTVAL'

    // required for subprocess tests
    dependsOn ":testprograms:jar"
    environment['PT_JAVA_EXE'] = jvmTest.executable
    doFirst {
        environment['PT_JAR'] = project(":testprograms").tasks["jar"].archiveFile.get()
    }
}*/

/*dokka {
    outputFormat = 'html'
    outputDirectory = "$buildDir/dokka"

    configuration {
        includes = ['src/PACKAGES.md']
        jdkVersion = 8
    }

    impliedPlatforms = ["Common"] // This will force platform tags for all non-common sources e.g. "JVM"
    multiplatform {
        common {
            // nothing special for common
        }

        jvm {
            targets = ["JVM"]
        }
        linux {
            targets = ["Linux"]
        }
        windows {
            targets = ["Windows"]
        }
    }
}*/

publishing {
    repositories {
        maven {
            val user = "xfelde"
            val repo = "ksubprocess"
            val name = "ksubprocess"
            url = uri("https://api.bintray.com/maven/$user/$repo/$name/;publish=0")

            credentials {
                username = System.getProperty("bintray.user")
                password = System.getProperty("bintray.key")
            }
        }
    }
    publications {
        withType<MavenPublication> {
            //artifact(tasks.named("javadocJar"))
            with(pom) {
                name.set(rootProject.name)
                url.set("https://github.com/xfel/ksubprocess")
                description.set("Kotlin multiplatform subprocess library")
                scm {
                    url.set("https://github.com/xfel/ksubprocess.git")
                }
                developers {
                    developer {
                        id.set("xfel")
                        name.set("Felix Treede")
                        email.set("felixtreede@yahoo.de")
                    }
                }
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
