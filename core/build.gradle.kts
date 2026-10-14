import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 纯 Kotlin 模块,不依赖任何第三方库:训练器与 Android APP 共用同一份特征/推理代码,
// 保证"训练时的特征"与"APP 里计算的特征"逐位一致。
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
