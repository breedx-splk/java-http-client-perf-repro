import org.gradle.api.tasks.JavaExec

plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.46.20"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.grpc:grpc-api:1.83.1")
    implementation("io.grpc:grpc-stub:1.83.1")
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:2.29.0")
    implementation("software.amazon.awssdk:cloudwatch")
    implementation("software.amazon.awssdk:netty-nio-client")
    runtimeOnly("io.grpc:grpc-netty-shaded:1.83.1")
    runtimeOnly("org.jolokia:jolokia-jvm:1.3.3:agent")
}

application {
    mainClass = "com.splunk.repro.HttpClientBenchmark"
}

val agentVersion = providers.gradleProperty("agentVersion").orElse("2.30.0")
val agentFlavor = providers.gradleProperty("agentFlavor").orElse("csa")
val agentJar = layout.projectDirectory.file(
    agentVersion.zip(agentFlavor) { version, flavor ->
        if (flavor == "csa") {
            "splunk-otel-javaagent-csa-$version.jar"
        } else {
            "splunk-otel-javaagent-$version.jar"
        }
    },
)
val benchmarkHeap = providers.gradleProperty("heap").orElse("20g")
val profilerEnabled = providers.gradleProperty("profiler").orElse("true")
val memoryProfilerProperty = providers.gradleProperty("memoryProfilerProperty").orElse("false")
val jolokiaEnabled = providers.gradleProperty("jolokia").orElse("true")
val jmxPort = providers.gradleProperty("jmxPort").orElse("18752")
val otlpEndpoint = providers.gradleProperty("otlpEndpoint").orElse("http://localhost:4318")
val otlpMetricsEndpoint = providers.gradleProperty("otlpMetricsEndpoint")
    .orElse("http://localhost:4319/v1/metrics")

fun JavaExec.useJava21() {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

fun JavaExec.configureCloudMetricSyncerRuntime(protocol: String) {
    useJava21()

    doFirst {
        check(agentFlavor.get() in setOf("csa", "standard")) {
            "agentFlavor must be csa or standard"
        }
        check(agentJar.get().asFile.isFile) {
            "Missing ${agentJar.get().asFile.name}; choose a checked-in agent version"
        }

        val profilerDirectory = layout.buildDirectory.dir("profiler").get().asFile
        val logDirectory = layout.buildDirectory.dir("logs").get().asFile
        profilerDirectory.mkdirs()
        logDirectory.mkdirs()

        jvmArgs(
            "-javaagent:${agentJar.get().asFile.absolutePath}",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-server",
            "-showversion",
            "-Xms${benchmarkHeap.get()}",
            "-Xmx${benchmarkHeap.get()}",
            "-XX:+UseG1GC",
            "-XX:HeapDumpPath=${logDirectory.absolutePath}",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:-OmitStackTraceInFastThrow",
            "-Xlog:gc*:${logDirectory.resolve("gc-$protocol.log").absolutePath}::filecount=3,filesize=100M",
        )

        systemProperties(
            mapOf(
                "benchmark.agent.version" to agentVersion.get(),
                "benchmark.agent.flavor" to agentFlavor.get(),
                "visualvm.display.name" to "local/cloud-metric-syncer-$protocol",
                "signalfuse.sourceName" to "cloud-metric-syncer-$protocol",
                "signalfuse.serviceName" to "cloud-metric-syncer",
                "file.encoding" to "UTF-8",
                "disco.ninja" to "false",
                "docker.tag" to "local-agent-${agentVersion.get()}",
                "disco.hostId" to "cloud-metric-syncer-local",
                "disco.realm" to "local",
                "disco.zone" to "local",
                "disco.publishAddress" to "127.0.0.1",
                "disco.zkConnectString" to "localhost:2181",
                "disco.zkConnectTimeout" to "10000",
                "logJsonToStdout" to "true",
                "otel.traces.sampler" to "parentbased_traceidratio",
                "otel.traces.sampler.arg" to "0.005",
                "otel.instrumentation.lettuce.enabled" to "false",
                "otel.instrumentation.reactor.enabled" to "false",
                "otel.exporter.otlp.protocol" to "http/protobuf",
                "otel.exporter.otlp.endpoint" to otlpEndpoint.get(),
                "otel.exporter.otlp.metrics.endpoint" to otlpMetricsEndpoint.get(),
                "otel.exporter.otlp.metrics.temporality.preference" to "DELTA",
                "otel.propagators" to "b3,tracecontext,baggage",
                "otel.javaagent.debug" to "false",
                "splunk.profiler.enabled" to profilerEnabled.get(),
                "splunk.profiler.directory" to profilerDirectory.absolutePath,
                "splunk.profiler.memory.enabled" to memoryProfilerProperty.get(),
                "sf.cloudmetricsyncer.onlyRunJobFamilies" to "AWS",
                "thrift.maxFrameSize" to "33554432",
                "com.datastax.driver.USE_NATIVE_CLOCK" to "false",
            ),
        )

        if (jmxPort.get() != "0") {
            systemProperties(
                mapOf(
                    "java.rmi.server.hostname" to "127.0.0.1",
                    "com.sun.management.jmxremote.port" to jmxPort.get(),
                    "com.sun.management.jmxremote.rmi.port" to jmxPort.get(),
                    "com.sun.management.jmxremote.authenticate" to "false",
                    "com.sun.management.jmxremote.local.only" to "false",
                    "com.sun.management.jmxremote.ssl" to "false",
                ),
            )
        }

        if (jolokiaEnabled.get().toBoolean()) {
            val jolokiaAgent = classpath.files.single {
                it.name == "jolokia-jvm-1.3.3-agent.jar"
            }
            jvmArgs("-javaagent:${jolokiaAgent.absolutePath}=host=0.0.0.0,port=6000,mimeType=application/json")
        }

        val resourceAttributes = listOf(
            "service.name=cloud-metric-syncer",
            "k8s.deployment.name=cloud-metric-syncer-aws",
            "service.version=benchmark-agent-${agentVersion.get()}",
            "deployment.environment.name=local-benchmark",
            "sfx_realm=local",
            "service.namespace=imm",
            "k8s.pod.uid=local-benchmark-pod-uid",
            "k8s.pod.name=cloud-metric-syncer-local",
            "k8s.node.name=localhost",
            "k8s.cluster.name=local",
            "k8s.namespace.name=local",
            "service.criticality=high",
            "benchmark.protocol=$protocol",
        ).joinToString(",")

        environment(
            mapOf(
                "OTEL_AGENT_ENABLED" to "true",
                "OTEL_JAVAAGENT_PATH" to agentJar.get().asFile.absolutePath,
                "OTEL_RESOURCE_ATTRIBUTES" to resourceAttributes,
                "OTEL_RESOURCE_DISABLED_KEYS" to listOf(
                    "process.command_args",
                    "process.command_line",
                    "process.runtime.description",
                    "process.runtime.name",
                    "process.runtime.version",
                    "process.pid",
                    "process.executable.path",
                ).joinToString(","),
                "TRACE_SAMPLER" to "parentbased_traceidratio",
                "TRACE_SAMPLER_ARG" to "0.005",
                "SPLUNK_PROFILER_ENABLED" to profilerEnabled.get(),
                "SPLUNK_PROFILER_MEMORY_ENABLED" to profilerEnabled.get(),
                "SPLUNK_SNAPSHOT_PROFILER_ENABLED" to profilerEnabled.get(),
                "SPLUNK_SNAPSHOT_PROFILER_SAMPLING_INTERVAL" to "1ms",
                "SPLUNK_SNAPSHOT_SELECTION_PROBABILITY" to "0.1",
                "MULTI_TENANT_AGENT_OTEL_SEND_FAKE_LOG_EVENTS" to "FALSE",
                "ARGENTO_ALLOW_SECURITY_EVENTS" to "FALSE",
            ),
        )
    }
}

tasks.register<JavaExec>("benchmark") {
    group = "application"
    description = "Runs the OkHttp benchmark with the production-like CMS runtime"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    configureCloudMetricSyncerRuntime("http-okhttp")
    args(
        providers.gradleProperty("iterations").orElse("1000").get(),
        providers.gradleProperty("warmups").orElse("100").get(),
        providers.gradleProperty("url").orElse("http://localhost:50054/").get(),
    )
}

tasks.register<JavaExec>("httpMockServer") {
    group = "application"
    description = "Runs the local HTTP benchmark service"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.HttpMockServer"
    useJava21()
    args(providers.gradleProperty("httpPort").orElse("50054").get())
}

tasks.register<JavaExec>("grpcServer") {
    group = "application"
    description = "Runs the local gRPC benchmark service"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.GrpcTestServer"
    useJava21()
    args(providers.gradleProperty("grpcPort").orElse("50052").get())
}

tasks.register<JavaExec>("grpcBenchmark") {
    group = "application"
    description = "Runs the gRPC benchmark with the production-like CMS runtime"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.GrpcClientBenchmark"
    configureCloudMetricSyncerRuntime("grpc")
    args(
        providers.gradleProperty("iterations").orElse("1000").get(),
        providers.gradleProperty("warmups").orElse("100").get(),
        providers.gradleProperty("grpcHost").orElse("localhost").get(),
        providers.gradleProperty("grpcPort").orElse("50052").get(),
    )
}

tasks.register<JavaExec>("awsMockServer") {
    group = "application"
    description = "Runs the local CloudWatch-compatible benchmark endpoint"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.AwsCloudWatchMockServer"
    useJava21()
    args(providers.gradleProperty("awsPort").orElse("50053").get())
}

tasks.register<JavaExec>("awsBenchmark") {
    group = "application"
    description = "Runs the AWS SDK async benchmark with the production-like CMS runtime"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.splunk.repro.AwsCloudWatchAsyncBenchmark"
    configureCloudMetricSyncerRuntime("aws-sdk-v2-netty")
    args(
        providers.gradleProperty("iterations").orElse("1000").get(),
        providers.gradleProperty("warmups").orElse("100").get(),
        providers.gradleProperty("awsEndpoint").orElse("http://localhost:50053").get(),
        providers.gradleProperty("concurrency").orElse("100").get(),
        providers.gradleProperty("completionThreads").orElse("4").get(),
    )
}
