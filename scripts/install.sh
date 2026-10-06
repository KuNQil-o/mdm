#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
mdm_tools=/workspace/.tools
mkdir -p "$mdm_tools/downloads" "$mdm_root/.runtime"
mdm_verify(){ echo "$1  $2" | sha256sum -c -; }
if [ ! -x "$mdm_tools/jdk-21.0.6+7/bin/javac" ]; then
 curl --fail --location --retry 2 --output "$mdm_tools/downloads/jdk.tar.gz" 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jdk_x64_linux_hotspot_21.0.6_7.tar.gz'
 mdm_verify a2650fba422283fbed20d936ce5d2a52906a5414ec17b2f7676dddb87201dbae "$mdm_tools/downloads/jdk.tar.gz"
 tar -xzf "$mdm_tools/downloads/jdk.tar.gz" -C "$mdm_tools"
fi
if [ ! -x "$mdm_tools/apache-maven-3.9.9/bin/mvn" ]; then
 curl --fail --location --retry 2 --output "$mdm_tools/downloads/maven.tar.gz" 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz'
 echo "a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b34  $mdm_tools/downloads/maven.tar.gz" | sha512sum -c -
 tar -xzf "$mdm_tools/downloads/maven.tar.gz" -C "$mdm_tools"
fi
source "$mdm_root/scripts/env.sh"
java -version
node --version
npm --prefix "$mdm_root/frontend" ci --cache "$mdm_tools/npm-cache" --no-audit --no-fund
npm --prefix "$mdm_root/frontend" run build --cache "$mdm_tools/npm-cache"
# Installation builds the application; business tests require a separately prepared PostgreSQL database.
"$mdm_root/scripts/mvn.sh" -q -DskipTests package
