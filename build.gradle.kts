plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  id("com.vanniktech.maven.publish") version "0.33.0"
  id("java-test-fixtures")
  id("com.diffplug.spotless") version "8.11.0"
  jacoco
}

val springBootVersion = "4.1.2"
val kotlinVersion: String by project
val packageId = "event-dispatcher"
val packageName = "Event Dispatcher"
group = "io.github.kostack"
version = providers.gradleProperty("projectVersion").getOrElse("1.0.0")
description = "Coroutine event dispatcher"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

repositories {
  mavenCentral()
}

dependencies {
  // Build and tests only: the Spring Boot BOM manages all versions below and is not published.
  val springBootBom = platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
  compileOnly(springBootBom)
  testImplementation(springBootBom)
  testFixturesImplementation(springBootBom)

  // Provided by the consuming application; not published as dependencies.
  listOf(
    "org.springframework.boot:spring-boot-starter",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    kotlin("reflect")
  ).forEach {
    compileOnly(it)
    testImplementation(it)
    testFixturesImplementation(it)
  }

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
  testImplementation("io.mockk:mockk:1.14.11")
  testImplementation(kotlin("test"))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

// Test fixtures are for this project's tests only; don't publish them.
(components["java"] as AdhocComponentWithVariants).apply {
  withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
  withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}
// The publish plugin adds the fixtures' sources variant late, so skip it after evaluation.
afterEvaluate {
  configurations.findByName("testFixturesSourcesElements")?.let {
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(it) { skip() }
  }
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
  // Needed by MockK's inline mocking agent on recent JDKs.
  jvmArgs("-XX:+EnableDynamicAgentLoading")

  testLogging {
    events("passed", "skipped", "failed")
  }
}

tasks.jacocoTestReport {
  reports {
    xml.required = true
    html.required = true
    csv.required = false
    html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
    xml.outputLocation = layout.buildDirectory.file("jacocoReport.xml")
  }
}

tasks.jacocoTestCoverageVerification {
  violationRules {
    rule {
      limit {
        minimum = "0.6".toBigDecimal()
      }
    }
  }
}

tasks.check {
  dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
  kotlin {
    target("src/**/*.kt")
    ktlint()
  }
  kotlinGradle {
    target("*.kts")
    ktlint()
  }
}

mavenPublishing {
  publishToMavenCentral()
  signAllPublications()

  coordinates(
    groupId = group.toString(),
    artifactId = packageId,
    version = version.toString()
  )

  pom {
    name = packageName
    description = project.description
    url = "https://github.com/kostack/$packageId"

    licenses {
      license {
        name = "MIT License"
        url = "https://opensource.org/licenses/MIT"
      }
    }

    developers {
      developer {
        id = "zender"
        name = "Nikolay Georgiev"
        url = "https://github.com/zender"
      }
    }

    scm {
      connection = "scm:git:git://github.com/kostack/$packageId.git"
      developerConnection = "scm:git:ssh://github.com/kostack/$packageId.git"
      url = "https://github.com/kostack/$packageId"
    }
  }
}
