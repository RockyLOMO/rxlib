#!/bin/bash
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

java_major_version() {
  if ! command -v java >/dev/null 2>&1; then
    echo 0
    return
  fi
  java -version 2>&1 | awk -F '"' '/version/ {
    split($2, parts, ".")
    if (parts[1] == "1") {
      print parts[2]
    } else {
      print parts[1]
    }
    exit
  }'
}

JAVA_MAJOR=$(java_major_version)
if [ "${JAVA_MAJOR}" != "21" ]; then
  echo "Error: rss rollback requires JDK 21, current java major version: ${JAVA_MAJOR}." >&2
  exit 1
fi
java -version

exec "${SCRIPT_DIR}/start.sh" rollback
