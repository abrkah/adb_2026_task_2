#!/bin/bash
# 30-second live demo of the continuous transaction stream.
#   scripts/demo.sh                 best version (one round trip + shared scans), 24 threads
#   scripts/demo.sh optimized 8     one round trip per transaction, 8 threads
#   scripts/demo.sh baseline 8      baseline, 8 threads
# stderr: one line per second (committed/s, in-flight = equilibrium, deadlocks)
# stdout: every transaction prints its result; we show every 2000th line.
cd "$(dirname "$0")/.." || exit 1
MODE=${1:-best}; T=${2:-24}; D=${3:-30}
case "$MODE" in
  best)      OPTS="preset=optimized shareScans=true" ;;
  optimized) OPTS="preset=optimized" ;;
  *)         OPTS="preset=baseline" ;;
esac
javac -d out src/*.java || exit 1
# shellcheck disable=SC2086
java -cp out:lib/postgresql-42.2.18.jar Benchmark $OPTS threads="$T" duration="$D" warmup=0 label="demo-$MODE" \
  | awk 'NR % 2000 == 0 { print "    sample output: " $0; fflush() }'
