#!/bin/bash
# Throughput vs. number of client threads (continuous stream, closed loop).
# Usage: scripts/sweep.sh [repeats] [seconds] ["thread list"]
cd "$(dirname "$0")/.." || exit 1
R=${1:-3}; D=${2:-10}; THREADS=${3:-"1 2 4 6 8 10 12 16 24 32 48 64"}
CSV=results/sweep.csv
CP=out:lib/postgresql-42.2.18.jar
javac -d out src/*.java || exit 1
mkdir -p results/logs

for r in $(seq 1 "$R"); do
  for t in $THREADS; do
    for cfg in "baseline|preset=baseline" \
               "optimized|preset=optimized" \
               "optimized+share|preset=optimized shareScans=true"; do
      label=${cfg%%|*}; opts=${cfg#*|}
      # shellcheck disable=SC2086
      java -cp $CP Benchmark threads=$t duration=$D warmup=2 label="$label" csv=$CSV $opts \
        > /dev/null 2> "results/logs/sweep-$label-t$t-$r.log" || { echo "FAILED $label t=$t"; exit 1; }
      grep RESULT "results/logs/sweep-$label-t$t-$r.log"
    done
  done
done
