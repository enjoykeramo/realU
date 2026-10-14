import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.realu.trainer.MainKt")
    applicationName = "realu-trainer"
    applicationDefaultJvmArgs = listOf("-Xmx6g", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// 单文件可执行 jar:java -jar realu-trainer.jar train config.properties
tasks.register<Jar>("fatJar") {
    group = "build"
    archiveFileName.set("realu-trainer.jar")
    manifest { attributes["Main-Class"] = "com.realu.trainer.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// Windows 分发包:build/distributions/realu-trainer-win.zip(解压即用,需本机安装 Java 17+)
tasks.register<Zip>("winDist") {
    group = "distribution"
    dependsOn("fatJar")
    archiveFileName.set("realu-trainer-win.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("realu-trainer") {
        from(layout.buildDirectory.file("libs/realu-trainer.jar"))
        from(rootProject.file("scripts")) { include("*.bat", "config.example.properties") }
        from(rootProject.file("docs")) { into("docs") }
        from(rootProject.file("README.md"))
    }
}
