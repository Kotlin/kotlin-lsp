#!/bin/bash

if [ ! -d "lib" ]; then
  echo "The 'lib' directory does not exist."
  exit 1
fi

jars=$(find lib -name "*.jar" -type f | tr '\n' ':')

# Remove the trailing colon from the classpath
classpath=${jars%:}

main_class="com.jetbrains.ls.kotlinLsp.KotlinLspServerKt"

java "--add-opens" "java.base/java.lang=ALL-UNNAMED" -cp "$classpath" "$main_class" "$@"