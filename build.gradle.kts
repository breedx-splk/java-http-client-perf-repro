plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.grpc:grpc-api:1.83.1")
    implementation("io.grpc:grpc-stub:1.83.1")
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:2.29.0")
    runtimeOnly("io.grpc:grpc-netty-shaded:1.83.1")
}

application {
    mainClass = "com.splunk.repro.HttpClientBenchmark"
}

val agentVersion = providers.gradleProperty("agentVersion").orElse("2.30.0")
val agentJar = layout.projectDirectory.file(
    agentVersion.map { "splunk-otel-javaagent-$it.jar" }
)

tasks.register<JavaExec>("benchmark") {
    group = "application"
    description = "Runs the HTTP benchmark with -PagentVersion (default: 2.30.0)"
    dependsOn(tasks.classes)

    doFirst {
        check(agentJar.get().asFile.isFile) {
            "Missing ${agentJar.get().asFile.name}; choose a checked-in agent version"
        }
    }

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(17)
    }
    jvmArgs("-javaagent:${agentJar.get().asFile.absolutePath}")
    systemProperty("benchmark.agent.version", agentVersion.get())
    systemProperty("otel.service.name", "java-http-client-perf-repro")
    systemProperty(
        "otel.resource.attributes",
        "deployment.environment=benchmark,benchmark.agent.version=${agentVersion.get()},benchmark.protocol=http",
    )

    args(
        providers.gradleProperty("iterations").orElse("100").get(),
        providers.gradleProperty("warmups").orElse("10").get(),
        providers.gradleProperty("url").orElse("https://www.splunk.com/").get(),
    )
}

tasks.register<JavaExec>("grpcServer") {
    group = "application"
    description = "Runs the local gRPC benchmark service"
    dependsOn(tasks.classes)

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.GrpcTestServer"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(17)
    }
    args(providers.gradleProperty("grpcPort").orElse("50052").get())
}

tasks.register<JavaExec>("grpcBenchmark") {
    group = "application"
    description = "Runs the gRPC client benchmark with -PagentVersion (default: 2.30.0)"
    dependsOn(tasks.classes)

    doFirst {
        check(agentJar.get().asFile.isFile) {
            "Missing ${agentJar.get().asFile.name}; choose a checked-in agent version"
        }
    }

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.GrpcClientBenchmark"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(17)
    }
    jvmArgs("-javaagent:${agentJar.get().asFile.absolutePath}")
    systemProperty("benchmark.agent.version", agentVersion.get())
    systemProperty("otel.service.name", "java-http-client-perf-repro")
    systemProperty(
        "otel.resource.attributes",
        "deployment.environment=benchmark,benchmark.agent.version=${agentVersion.get()},benchmark.protocol=grpc",
    )

    args(
        providers.gradleProperty("iterations").orElse("1000").get(),
        providers.gradleProperty("warmups").orElse("100").get(),
        providers.gradleProperty("grpcHost").orElse("localhost").get(),
        providers.gradleProperty("grpcPort").orElse("50052").get(),
    )
}
