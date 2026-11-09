#!/bin/sh
#
# Minimal Gradle wrapper launcher.
#
# The binary wrapper jar/scripts are not committed in this repository (all app sources
# are generated and reviewed in the open), so this script downloads the Gradle
# distribution pinned in gradle/wrapper/gradle-wrapper.properties on first use and
# then simply delegates to it. CI therefore needs nothing but a JDK and curl.

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$PROPS" ]; then
  echo "Missing $PROPS" >&2
  exit 1
fi

DIST_URL=$(grep -E '^distributionUrl=' "$PROPS" | cut -d'=' -f2- | sed 's#\\:#:#g')
GRADLE_VERSION=$(echo "$DIST_URL" | sed -n 's#.*gradle-\([0-9.]*\)-.*#\1#p')
[ -n "$GRADLE_VERSION" ] || { echo "Cannot parse Gradle version from $PROPS" >&2; exit 1; }

CACHE_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$CACHE_BASE/wrapper/dists/gradle-$GRADLE_VERSION-bin"
GRADLE_HOME="$DIST_DIR/gradle-$GRADLE_VERSION"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..." >&2
  mkdir -p "$DIST_DIR"
  ZIP="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$ZIP" "$DIST_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$ZIP" "$DIST_URL"
  else
    echo "Neither curl nor wget is available" >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$DIST_DIR"
  rm -f "$ZIP"
fi

exec "$GRADLE_HOME/bin/gradle" -p "$APP_HOME" "$@"
