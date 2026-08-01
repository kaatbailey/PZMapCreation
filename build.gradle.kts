plugins {
    java
    application
}

group = "pzformat"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

sourceSets {
    named("test") {
        java.setSrcDirs(listOf("src/test/java"))
    }
}

application {
    mainClass = "pzformat.Probe"
}

// Runs the synthetic-fixture self test (plain main(), no JUnit dependency).
tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Verify readers/writers against synthetic fixtures"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "pzformat.SelfTest"
}
