#!/bin/bash
# The SAME fixed stream of N transactions (seed 42), executed
#   serially (1 thread, 1 connection, one transaction after the other) and
#   concurrently (several threads). Reports the time until all N committed.
# Usage: scripts/serial_vs_concurrent.sh [repeats] [N]
cd "$(dirname "$0")/.." || exit 1
R=${1:-3}; N=${2:-20000}
CSV=results/serial_vs_concurrent.csv
CP=out:lib/postgresql-42.2.18.jar
javac -d out src/*.java || exit 1
mkdir -p results/logs

run() {   # label, options...
  local label=$1; shift
  for r in $(seq 1 "$R"); do
    java -cp $CP Benchmark count=$N seed=42 label="$label" csv=$CSV "$@" \
      > /dev/null 2> "results/logs/svc-$label-$r.log" || { echo "FAILED $label"; exit 1; }
    grep -E "RESULT|all $N" "results/logs/svc-$label-$r.log"
  done
}

# Trivial serial execution: no concurrency, so no deadlock handling / gate needed.
run serial-baseline    preset=baseline  threads=1 gate=false
run serial-optimized   preset=optimized threads=1 gate=false
for t in 4 8 16 32; do
  run conc-baseline-t$t   preset=baseline  threads=$t
  run conc-optimized-t$t  preset=optimized threads=$t
  run conc-opt+share-t$t  preset=optimized threads=$t shareScans=true
done
