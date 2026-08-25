#!/usr/bin/env bash
set -euo pipefail

benchmark="${BENCHMARK:-aws}"
heap="${BENCHMARK_HEAP:-20g}"
jmx_port="${JMX_PORT:-18752}"
profiler_enabled="${SPLUNK_PROFILER_ENABLED:-true}"
pod_address="${POD_IP_ADDRESS:-127.0.0.1}"
otlp_endpoint="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://host.docker.internal:4318}"
otlp_metrics_endpoint="${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:-http://host.docker.internal:4319/v1/metrics}"
agent_path="${OTEL_JAVAAGENT_PATH:-/opt/otel/splunk-otel-javaagent.jar}"

agent_version="${BENCHMARK_AGENT_VERSION:-unknown}"

case "$benchmark" in
  http)
    protocol=http-okhttp
    main_class=com.splunk.repro.HttpClientBenchmark
    if [[ $# -eq 0 ]]; then
      set -- 1000 100 "${HTTP_URL:-http://host.docker.internal:50054/}"
    fi
    ;;
  grpc)
    protocol=grpc
    main_class=com.splunk.repro.GrpcClientBenchmark
    if [[ $# -eq 0 ]]; then
      set -- 1000 100 "${GRPC_HOST:-host.docker.internal}" "${GRPC_PORT:-50052}"
    fi
    ;;
  aws)
    protocol=aws-sdk-v2-netty
    main_class=com.splunk.repro.AwsCloudWatchAsyncBenchmark
    if [[ $# -eq 0 ]]; then
      set -- 1000 100 "${AWS_ENDPOINT:-http://host.docker.internal:50053}" 100 4
    fi
    ;;
  *)
    echo "BENCHMARK must be http, grpc, or aws" >&2
    exit 2
    ;;
esac

export OTEL_AGENT_ENABLED=true
export OTEL_JAVAAGENT_PATH="$agent_path"
export TRACE_SAMPLER="${TRACE_SAMPLER:-parentbased_traceidratio}"
export TRACE_SAMPLER_ARG="${TRACE_SAMPLER_ARG:-0.005}"
export SPLUNK_PROFILER_ENABLED="$profiler_enabled"
export SPLUNK_PROFILER_MEMORY_ENABLED="${SPLUNK_PROFILER_MEMORY_ENABLED:-$profiler_enabled}"
export PROFILER_MEMORY_ENABLED="${PROFILER_MEMORY_ENABLED:-false}"
export SPLUNK_SNAPSHOT_PROFILER_ENABLED="${SPLUNK_SNAPSHOT_PROFILER_ENABLED:-$profiler_enabled}"
export SPLUNK_SNAPSHOT_PROFILER_SAMPLING_INTERVAL="${SPLUNK_SNAPSHOT_PROFILER_SAMPLING_INTERVAL:-1ms}"
export SPLUNK_SNAPSHOT_SELECTION_PROBABILITY="${SPLUNK_SNAPSHOT_SELECTION_PROBABILITY:-0.1}"
export MULTI_TENANT_AGENT_OTEL_SEND_FAKE_LOG_EVENTS="${MULTI_TENANT_AGENT_OTEL_SEND_FAKE_LOG_EVENTS:-FALSE}"
export ARGENTO_ALLOW_SECURITY_EVENTS="${ARGENTO_ALLOW_SECURITY_EVENTS:-FALSE}"
export OTEL_RESOURCE_DISABLED_KEYS="${OTEL_RESOURCE_DISABLED_KEYS:-process.command_args,process.command_line,process.runtime.description,process.runtime.name,process.runtime.version,process.pid,process.executable.path}"
export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES:-service.name=cloud-metric-syncer,k8s.deployment.name=cloud-metric-syncer-aws,service.version=benchmark-agent-$agent_version,deployment.environment.name=local-benchmark,sfx_realm=local,service.namespace=imm,k8s.pod.uid=local-benchmark-pod-uid,k8s.pod.name=cloud-metric-syncer-local,k8s.node.name=localhost,k8s.cluster.name=local,k8s.namespace.name=local,service.criticality=high,benchmark.protocol=$protocol}"

java_opts=(
  "-javaagent:$agent_path"
  "-javaagent:$SVC_HOME/lib/jolokia-jvm-1.3.3-agent.jar=host=0.0.0.0,port=6000,mimeType=application/json"
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
  -server
  -showversion
  "-Xms$heap"
  "-Xmx$heap"
  -XX:+UseG1GC
  -XX:HeapDumpPath=/var/log/sf
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:-OmitStackTraceInFastThrow
  "-Xlog:gc*:/var/log/sf/gc.log::filecount=3,filesize=100M"
  "-Dbenchmark.agent.version=$agent_version"
  -Dbenchmark.agent.flavor=csa
  "-Dvisualvm.display.name=local/cloud-metric-syncer-$protocol"
  "-Dsignalfuse.sourceName=cloud-metric-syncer-$protocol"
  -Dsignalfuse.serviceName=cloud-metric-syncer
  -Dfile.encoding=UTF-8
  -Ddisco.ninja=false
  "-Ddocker.tag=local-agent-$agent_version"
  -Ddisco.hostId=cloud-metric-syncer-local
  -Ddisco.realm=local
  -Ddisco.zone=local
  "-Ddisco.publishAddress=$pod_address"
  -Ddisco.zkConnectString=localhost:2181
  -Ddisco.zkConnectTimeout=10000
  -DlogJsonToStdout=true
  "-Dotel.traces.sampler=$TRACE_SAMPLER"
  "-Dotel.traces.sampler.arg=$TRACE_SAMPLER_ARG"
  -Dotel.instrumentation.lettuce.enabled=false
  -Dotel.instrumentation.reactor.enabled=false
  -Dotel.exporter.otlp.protocol=http/protobuf
  "-Dotel.exporter.otlp.endpoint=$otlp_endpoint"
  "-Dotel.exporter.otlp.metrics.endpoint=$otlp_metrics_endpoint"
  -Dotel.exporter.otlp.metrics.temporality.preference=DELTA
  -Dotel.propagators=b3,tracecontext,baggage
  -Dotel.javaagent.debug=false
  "-Dsplunk.profiler.enabled=$profiler_enabled"
  -Dsplunk.profiler.directory=/opt/profiler
  "-Dsplunk.profiler.memory.enabled=$PROFILER_MEMORY_ENABLED"
  -Dsf.cloudmetricsyncer.onlyRunJobFamilies=AWS
  -Dthrift.maxFrameSize=33554432
  -Dcom.datastax.driver.USE_NATIVE_CLOCK=false
)

if [[ "$jmx_port" != 0 ]]; then
  java_opts+=(
    "-Djava.rmi.server.hostname=$pod_address"
    "-Dcom.sun.management.jmxremote.port=$jmx_port"
    "-Dcom.sun.management.jmxremote.rmi.port=$jmx_port"
    -Dcom.sun.management.jmxremote.authenticate=false
    -Dcom.sun.management.jmxremote.local.only=false
    -Dcom.sun.management.jmxremote.ssl=false
  )
fi

if [[ -n "${JAVA_EXTRA_OPTS:-}" ]]; then
  read -r -a extra_opts <<< "$JAVA_EXTRA_OPTS"
  java_opts+=("${extra_opts[@]}")
fi

exec java "${java_opts[@]}" -cp "$SVC_HOME/lib/*" "$main_class" "$@"
