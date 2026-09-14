plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":protocol"))
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
