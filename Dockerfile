# check=skip=InvalidDefaultArgInFrom
# Intentionally no default: callers must provide the exact comparison base image.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

ARG AGENT_VERSION
ENV BENCHMARK_AGENT_VERSION=${AGENT_VERSION}
ENV SVC_HOME=/opt/java-http-client-perf-repro

WORKDIR ${SVC_HOME}

COPY --chown=9739:9739 build/install/java-http-client-perf-repro/ ${SVC_HOME}/
COPY --chown=9739:9739 docker/run-benchmark.sh ${SVC_HOME}/bin/run-benchmark

RUN chmod 0755 ${SVC_HOME}/bin/run-benchmark \
    && install -d -o 9739 -g 9739 /opt/profiler /var/log/sf

USER 9739:9739

ENTRYPOINT ["/opt/java-http-client-perf-repro/bin/run-benchmark"]
