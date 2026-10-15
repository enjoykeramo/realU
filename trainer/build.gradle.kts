import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDateTime

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

// 构建信息(版本 + 构建时间),供 `realu version` 显示,用来确认运行的是不是最新编译的程序
val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val buildInfo = tasks.register("buildInfo") {
    val out = buildInfoDir
    val ver = project.version.toString()
    outputs.dir(out)
    outputs.upToDateWhen { false }
    doLast {
        val f = out.get().file("realu-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$ver\nbuildTime=${LocalDateTime.now().withNano(0)}\n")
    }
}
sourceSets.main { resources.srcDir(buildInfo) }

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
