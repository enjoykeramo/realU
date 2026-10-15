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
    val root = rootProject.projectDir
    doLast {
        // 记录源码的 git 提交号:确认编译的确实是最新代码(git 不可用时写 unknown)
        fun git(vararg a: String): String? = runCatching {
            val p = ProcessBuilder(listOf("git") + a).directory(root).redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0) t else null
        }.getOrNull()
        val commit = git("rev-parse", "--short", "HEAD") ?: "unknown"
        val dirty = git("status", "--porcelain", "--untracked-files=no")?.isNotEmpty() == true
        val f = out.get().file("realu-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$ver\nbuildTime=${LocalDateTime.now().withNano(0)}\ncommit=$commit${if (dirty) "+local-changes" else ""}\n")
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

// ============================================================================
// Windows 可执行程序:gradle exe
//   用本机已安装的 Gradle 直接编译,并用 JDK 自带的 jpackage 打包成
//   realu.exe + 自带 Java 运行时(目标电脑不用装 Java)。
//   输出到 <项目根>/dist/realu,可用 -PoutDir=D:\tools\realu 指定。
//   重复打包只替换程序部分(app、runtime、realu.exe、docs),
//   输出目录里的 config.properties、out\ 训练结果都会保留。
// ============================================================================
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val jpackageStage = layout.buildDirectory.dir("jpackage")

fun findJpackage(): File {
    val exe = if (isWindows) "jpackage.exe" else "jpackage"
    val candidates = listOfNotNull(
        System.getProperty("java.home"),            // 运行 Gradle 的 JDK
        System.getenv("JAVA_HOME"),
    ).map { File(it, "bin/$exe") }
    return candidates.firstOrNull { it.isFile }
        ?: throw GradleException("找不到 jpackage:请安装 JDK 17 或更高版本,并让 JAVA_HOME 指向 JDK 目录(不是 JRE)")
}

val jpackageImage = tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "用 jpackage 生成 realu 程序镜像(含 Java 运行时)"
    dependsOn("installDist")
    val lib = layout.buildDirectory.dir("install/realu-trainer/lib")
    val mainJar = tasks.jar.flatMap { it.archiveFileName }
    val stage = jpackageStage
    doFirst {
        stage.get().asFile.deleteRecursively()
        executable = findJpackage().absolutePath
        args(
            "--type", "app-image", "--name", "realu",
            "--input", lib.get().asFile.absolutePath,
            "--main-jar", mainJar.get(),
            "--main-class", "com.realu.trainer.MainKt",
            "--java-options", "-XX:MaxRAMPercentage=65",
            "--java-options", "-Dfile.encoding=UTF-8",
            // jdk.charsets:通达信 txt、tdxhy.cfg、block_gn.dat 都是 GBK 编码
            "--add-modules", "java.base,jdk.charsets",
            "--dest", stage.get().asFile.absolutePath,
        )
        if (isWindows) args("--win-console")
        logger.lifecycle("jpackage: $executable")
    }
}

tasks.register("exe") {
    group = "distribution"
    description = "编译并打包 Windows 可执行程序 realu.exe 到 dist/realu"
    dependsOn(jpackageImage)
    val outDir = (findProperty("outDir") as String?)?.let { File(it) } ?: rootProject.file("dist/realu")
    val stage = jpackageStage
    val root = rootProject.projectDir
    doLast {
        val img = stage.get().asFile.resolve("realu")
        outDir.mkdirs()
        // 只替换程序部分,保留用户自己的文件
        for (name in listOf("app", "runtime", "bin", "lib", "docs")) outDir.resolve(name).deleteRecursively()
        project.copy { from(img); into(outDir) } // 保留文件权限
        root.resolve("docs").copyRecursively(outDir.resolve("docs"), overwrite = true)
        root.resolve("README.md").copyTo(outDir.resolve("README.md"), overwrite = true)
        val template = root.resolve("scripts/config.example.properties")
        template.copyTo(outDir.resolve("config.example.properties"), overwrite = true)
        val cfg = outDir.resolve("config.properties")
        if (!cfg.exists()) {
            template.copyTo(cfg)
            logger.lifecycle("已生成 ${cfg.absolutePath},训练前请修改 tdx.vipdoc")
        }
        // 自检:运行打包好的程序
        val launcher = if (isWindows) outDir.resolve("realu.exe") else outDir.resolve("bin/realu")
        val p = ProcessBuilder(launcher.absolutePath, "version").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() != 0) throw GradleException("打包后的程序无法启动:\n$text")
        logger.lifecycle("")
        logger.lifecycle("==== 打包完成 ====")
        logger.lifecycle(text)
        logger.lifecycle("程序:${launcher.absolutePath}")
        logger.lifecycle("使用:cd /d \"${outDir.absolutePath}\" 然后  realu.exe check / realu.exe train")
    }
}
