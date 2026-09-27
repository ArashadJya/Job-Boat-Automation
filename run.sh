#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
mvn -q -DskipTests package
java -jar target/ResumeJobHunter-4.0-jar-with-dependencies.jar "$@"
