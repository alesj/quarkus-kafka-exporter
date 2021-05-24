#!/bin/sh
set -e
set +x

# We don't need LOG_DIR because we write no log files, but setting it to a
# directory avoids trying to create it (and logging a permission denied error)
if [ -n "$KAFKA_EXPORTER_HOME" ]; then
    export LOG_DIR="$KAFKA_EXPORTER_HOME"
fi

export QUARKUS_HTTP_PORT=9404
export QUARKUS_PROFILE=strimzi

exec /work/application
