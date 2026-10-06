#!/usr/bin/env bash
set -euo pipefail
if [ -d /workspace/.tools/jdk-21.0.6+7 ]; then export JAVA_HOME=/workspace/.tools/jdk-21.0.6+7; export PATH="$JAVA_HOME/bin:$PATH"; fi
if [ -d /workspace/.tools/apache-maven-3.9.9 ]; then export PATH="/workspace/.tools/apache-maven-3.9.9/bin:$PATH"; fi
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=/workspace/.tools/m2"
