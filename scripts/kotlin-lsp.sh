#!/bin/bash

SOURCE=${BASH_SOURCE[0]}
while [ -L "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink
  DIR=$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)
  SOURCE=$(readlink "$SOURCE")
  # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
  [[ $SOURCE != /* ]] && SOURCE=$DIR/$SOURCE
done
DIR=$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)

if [ ! -d "$DIR/lib" ]; then
  echo >&2 -e "The 'lib' directory does not exist."
  exit 1
fi

classpath="$DIR/lib/*"

main_class="com.jetbrains.ls.kotlinLsp.KotlinLspServerKt"

JAVA_BIN="java"
if [ -n "$JAVA_HOME" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVA_BIN" ]; then
    echo "'java' should be on the PATH or JAVA_HOME must point to a valid JDK installation"
    exit 1
  fi
fi

"$JAVA_BIN" "--add-opens" "java.base/java.lang=ALL-UNNAMED" -cp "$classpath" "$main_class" "$@"