plugins {
    java
    id("org.springframework.boot") version "3.4.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    group = "tj.mintrans.epd"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        options.release.set(21)
        options.encoding = "UTF-8"
    }

    dependencies {
        "implementation"("org.springframework.boot:spring-boot-starter-web")
        "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
        "implementation"("org.springframework.boot:spring-boot-starter-validation")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("org.springframework.boot:spring-boot-starter-security")
        "implementation"("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
        "implementation"("org.flywaydb:flyway-core")
        "implementation"("org.flywaydb:flyway-database-postgresql")
        "implementation"("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
        "runtimeOnly"("org.postgresql:postgresql")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
