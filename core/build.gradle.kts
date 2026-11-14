plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    // 若已构建 native/pcg-dsp, 对照测试会加载它; 不存在时对照测试自动跳过
    systemProperty("java.library.path", rootProject.file("native/pcg-dsp/target/release").absolutePath)
}