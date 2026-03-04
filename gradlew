#!/bin/sh

APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "${0%/*}" && pwd )

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
JAVACMD="$JAVA_HOME/bin/java"

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
