# ksubprocess

[![Maven Central](https://img.shields.io/maven-central/v/org.drewcarlson/ksubprocess?label=maven&color=blue)](https://central.sonatype.com/search?q=ksubprocess-*&namespace=org.drewcarlson)
![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/org.drewcarlson/ksubprocess?server=https%3A%2F%2Fs01.oss.sonatype.org)
![](https://github.com/DrewCarlson/ksubprocess/workflows/Tests/badge.svg)

![](https://img.shields.io/static/v1?label=&message=Platforms&color=grey)
![](https://img.shields.io/static/v1?label=&message=Jvm&color=blue)
![](https://img.shields.io/static/v1?label=&message=Linux&color=blue)
![](https://img.shields.io/static/v1?label=&message=macOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Windows&color=blue)

Kotlin multiplatform library for launching child processes, monitoring their state, and capturing output.

```kotlin
val result = exec {
    // some command line program
    arg("curl")
    args("-d", "@-")
    arg("https://my.api")
    // redirect streams
    stdin = Redirect.Pipe
    stdout = Redirect.Pipe
    stderr = Redirect.Write("/log/file")
    // pipe input data to stdin
    input {
        append("Hello, World")
    }
    // check for errors
    check = true
}

// use result
println(result.output)
```

## Supported platforms

Ksubprocess supports the following platforms:

- JVM via `java.lang.Process`
- Native/Linux via `fork`/`exec`
- Native/Windows via `CreateProcess`
- Native/macOS via `NSTask`


## Download

[![Maven Central](https://img.shields.io/maven-central/v/org.drewcarlson/ksubprocess-jvm?label=maven&color=blue)](https://search.maven.org/search?q=g:org.drewcarlson%20a:ksubprocess*)
![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/org.drewcarlson/ksubprocess-jvm?server=https%3A%2F%2Fs01.oss.sonatype.org)


![](https://img.shields.io/static/v1?label=&message=Platforms&color=grey)
![](https://img.shields.io/static/v1?label=&message=Jvm&color=blue)
![](https://img.shields.io/static/v1?label=&message=Linux&color=blue)
![](https://img.shields.io/static/v1?label=&message=macOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Windows&color=blue)

```kotlin
repositories {
    mavenCentral()
    // Or snapshots
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    implementation("org.drewcarlson:ksubprocess:$VERSION")
}
```
