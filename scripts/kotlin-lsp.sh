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

jars=$(find "$DIR/lib" -name "*.jar" -type f | tr '\n' ':')

# Remove the trailing colon from the classpath
classpath=${jars%:}

main_class="com.jetbrains.ls.kotlinLsp.KotlinLspServerKt"

java "--add-opens" "java.base/java.lang=ALL-UNNAMED" -cp "$classpath" "$main_class" "$@"