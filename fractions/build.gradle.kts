plugins {
    id("org.jetbrains.kotlin.jvm")
    jacoco
}
kotlin { jvmToolchain(21) }
dependencies { testImplementation("junit:junit:4.13.2") }
jacoco { toolVersion = "0.8.12" }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules { rule { limit { minimum = "0.80".toBigDecimal() } } }
}
