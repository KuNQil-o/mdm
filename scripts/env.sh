#!/usr/bin/env bash
set -euo pipefail
if [ -d /workspace/.tools/jdk-21.0.6+7 ]; then export JAVA_HOME=/workspace/.tools/jdk-21.0.6+7; export PATH="$JAVA_HOME/bin:$PATH"; fi
if [ -d /workspace/.tools/apache-maven-3.9.9 ]; then export PATH="/workspace/.tools/apache-maven-3.9.9/bin:$PATH"; fi
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=/workspace/.tools/m2"
# Explicit local demo source credentials; production source profiles use their own environment refs.
export ERP_READ_USER="${ERP_READ_USER:-mdm_erp_reader}"
export ERP_READ_PASSWORD="${ERP_READ_PASSWORD:-erp_read_dev_only}"
