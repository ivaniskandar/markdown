plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":markdown-renderer"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.hrm.markdown.benchmark.StreamingRenderBenchmarkKt")
}
