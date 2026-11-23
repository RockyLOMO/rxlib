#!/usr/bin/env bash
set -eu
run_as_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif sudo -n true >/dev/null 2>&1; then
    sudo "$@"
  else
    echo "Error: JDK 21 install requires root or passwordless sudo."
    exit 1
  fi
}

apt_get_retry() {
  max_attempts=18
  retry_delay=10
  i=1
  while [ "$i" -le "$max_attempts" ]; do
    if run_as_root apt-get "$@"; then
      return 0
    fi
    if [ "$i" -eq "$max_attempts" ]; then
      echo "apt-get $* failed after ${max_attempts} attempts"
      return 1
    fi
    echo "apt-get $* failed, retrying in ${retry_delay}s..."
    sleep "$retry_delay"
    i=$((i + 1))
  done
}

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

install_jdk21() {
  if command -v apt-get >/dev/null 2>&1; then
    apt_get_retry update -qq
    apt_get_retry install -y -qq openjdk-21-jdk-headless
  elif command -v dnf >/dev/null 2>&1; then
    run_as_root dnf --disablerepo=epel --disablerepo='epel-*' install -y java-21-openjdk-headless
  elif command -v yum >/dev/null 2>&1; then
    run_as_root yum --disablerepo=epel --disablerepo='epel-*' install -y java-21-openjdk-headless
  else
    echo "Error: unsupported package manager, please install JDK 21 manually."
    exit 1
  fi
}

set_java_alternative() {
  name="$1"
  binary="$2"
  link="/usr/bin/${name}"
  priority=2100
  if command -v update-alternatives >/dev/null 2>&1; then
    run_as_root update-alternatives --set "$name" "$binary" \
      || { run_as_root update-alternatives --install "$link" "$name" "$binary" "$priority" \
        && run_as_root update-alternatives --set "$name" "$binary"; } || true
  elif command -v alternatives >/dev/null 2>&1; then
    run_as_root alternatives --set "$name" "$binary" \
      || { run_as_root alternatives --install "$link" "$name" "$binary" "$priority" \
        && run_as_root alternatives --set "$name" "$binary"; } || true
  else
    return 1
  fi
}

use_jdk21_default() {
  for java21 in \
    /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
    /usr/lib/jvm/java-21-openjdk/bin/java \
    /usr/lib/jvm/java-21*/bin/java; do
    if [ -x "$java21" ]; then
      JAVA_HOME="$(cd "$(dirname "$java21")/.." && pwd)"
      export JAVA_HOME
      export PATH="$(dirname "$java21"):${PATH}"
      hash -r 2>/dev/null || true
      set_java_alternative java "$java21" || true
      javac21="$(dirname "$java21")/javac"
      if [ -x "$javac21" ]; then
        set_java_alternative javac "$javac21" || true
      fi
      return 0
    fi
  done
  return 1
}

JAVA_MAJOR="$(java_major_version)"
if [ "$JAVA_MAJOR" != "21" ]; then
  echo "JDK 21 is not the active java, current java major version: ${JAVA_MAJOR}."
  if ! use_jdk21_default || [ "$(java_major_version)" != "21" ]; then
    echo "Installing JDK 21..."
    install_jdk21
    use_jdk21_default || true
  fi
else
  echo "JDK 21 already installed."
fi

JAVA_MAJOR="$(java_major_version)"
if [ "$JAVA_MAJOR" != "21" ]; then
  echo "Error: JDK 21 install check failed, current java major version: ${JAVA_MAJOR}."
  exit 1
fi
java -version
