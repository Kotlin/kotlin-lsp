#!/bin/bash

_have() { type "$1" &>/dev/null; }

die() {
    echo >&2 -e "$1"
    exit 1
}

SOURCE=${BASH_SOURCE[0]}
while [ -L "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink
    DIR=$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)
    SOURCE=$(readlink "$SOURCE")
    # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
    [[ $SOURCE != /* ]] && SOURCE=$DIR/$SOURCE
done
DIR=$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)

if [ ! -d "$DIR/lib" ]; then
    die "The 'lib' directory does not exist."
fi

classpath="$DIR/lib/*"

main_class="com.jetbrains.ls.kotlinLsp.KotlinLspServerKt"

JAVA_BIN="java"
if [ -n "$JAVA_HOME" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVA_BIN" ]; then
        die "'java' should be on the PATH or JAVA_HOME must point to a valid JDK installation"
    fi
fi

run_server() {
    "$JAVA_BIN" \
        --add-opens java.base/java.io=ALL-UNNAMED \
        --add-opens java.base/java.lang=ALL-UNNAMED \
        --add-opens java.base/java.lang.ref=ALL-UNNAMED \
        --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
        --add-opens java.base/java.net=ALL-UNNAMED \
        --add-opens java.base/java.nio=ALL-UNNAMED \
        --add-opens java.base/java.nio.charset=ALL-UNNAMED \
        --add-opens java.base/java.text=ALL-UNNAMED \
        --add-opens java.base/java.time=ALL-UNNAMED \
        --add-opens java.base/java.util=ALL-UNNAMED \
        --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
        --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
        --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED \
        --add-opens java.base/jdk.internal.vm=ALL-UNNAMED \
        --add-opens java.base/sun.net.dns=ALL-UNNAMED \
        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
        --add-opens java.base/sun.nio.fs=ALL-UNNAMED \
        --add-opens java.base/sun.security.ssl=ALL-UNNAMED \
        --add-opens java.base/sun.security.util=ALL-UNNAMED \
        --add-opens java.desktop/com.apple.eawt=ALL-UNNAMED \
        --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED \
        --add-opens java.desktop/com.apple.laf=ALL-UNNAMED \
        --add-opens java.desktop/com.sun.java.swing=ALL-UNNAMED \
        --add-opens java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED \
        --add-opens java.desktop/java.awt=ALL-UNNAMED \
        --add-opens java.desktop/java.awt.dnd.peer=ALL-UNNAMED \
        --add-opens java.desktop/java.awt.event=ALL-UNNAMED \
        --add-opens java.desktop/java.awt.font=ALL-UNNAMED \
        --add-opens java.desktop/java.awt.image=ALL-UNNAMED \
        --add-opens java.desktop/java.awt.peer=ALL-UNNAMED \
        --add-opens java.desktop/javax.swing=ALL-UNNAMED \
        --add-opens java.desktop/javax.swing.plaf.basic=ALL-UNNAMED \
        --add-opens java.desktop/javax.swing.text=ALL-UNNAMED \
        --add-opens java.desktop/javax.swing.text.html=ALL-UNNAMED \
        --add-opens java.desktop/sun.awt=ALL-UNNAMED \
        --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED \
        --add-opens java.desktop/sun.awt.datatransfer=ALL-UNNAMED \
        --add-opens java.desktop/sun.awt.image=ALL-UNNAMED \
        --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED \
        --add-opens java.desktop/sun.font=ALL-UNNAMED \
        --add-opens java.desktop/sun.java2d=ALL-UNNAMED \
        --add-opens java.desktop/sun.lwawt=ALL-UNNAMED \
        --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
        --add-opens java.desktop/sun.swing=ALL-UNNAMED \
        --add-opens java.management/sun.management=ALL-UNNAMED \
        --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
        --add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
        --add-opens jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED \
        --add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED \
        -cp "$classpath" "$main_class" "$@"
}

STDIO=0
SOCKET=9999
EXPECT_SOCKET=0

for arg in "$@"; do
    case $arg in
    --stdio)
        STDIO=1
        ;;
    --socket)
        EXPECT_SOCKET=1
        ;;
    *)
        if [ "$EXPECT_SOCKET" == "1" ]; then
            SOCKET=$arg
            EXPECT_SOCKET=0
        fi
        ;;
    esac
done

if [ "$STDIO" == "1" ]; then
    _have nc || die "netcat is not installed"
    _have socat || die "socat is not installed"

    run_server "$@" &
    PID="$!"

    echo "Waiting for server to start"
    start_ts=$(date +%s)
    while :; do
        now=$(date +%s)
        if nc -z localhost "$SOCKET"; then
            break
        fi
        if [[ $((now - start_ts)) -gt 5 ]]; then
            die "Server didn't start"
        fi
        sleep 0.1
    done

    echo "Server started"

    socat - TCP:localhost:"$SOCKET"
    kill "$PID"
else
    run_server "$@"
fi
