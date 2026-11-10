plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.5"
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.9")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.9"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                named("java") { option("lite") }
            }
        }
    }
}

tasks.register<JavaExec>("runPlacementDatasetRead") {
    group = "application"
    dependsOn(tasks.classes)
    mainClass.set("one.cloud.benchmark.dataset.DatasetReader")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register("writePlacementDatasetRuntime") {
    dependsOn(tasks.classes)
    doLast {
        val destination = file(providers.gradleProperty("datasetRuntimeFile").get())
        val reader = tasks.named<JavaExec>("runPlacementDatasetRead").get()
        destination.parentFile.mkdirs()
        destination.writeText(reader.javaLauncher.get().executablePath.asFile.absolutePath + "\n"
                + sourceSets.main.get().runtimeClasspath.asPath + "\n")
    }
}
