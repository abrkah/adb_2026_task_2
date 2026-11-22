#!/usr/bin/env python3
"""Mean / stdev per label for the benchmark CSV files in results/."""
import csv, statistics as st, sys, os
os.chdir(os.path.join(os.path.dirname(__file__), '..'))
def load(f):
    by = {}
    for r in csv.DictReader(open(f)):
        by.setdefault((r['label'], int(r['threads'])), []).append(r)
    return by
def m(v, k): return st.mean(float(r[k]) for r in v)
def sd(v, k): return st.pstdev(float(r[k]) for r in v)
if os.path.exists('results/ablation.csv'):
    print('== Ablation (8 threads, 10 s, 3 runs)')
    by = load('results/ablation.csv'); base = m(by[('baseline', 8)], 'tps')
    for (l, t), v in by.items():
        print(f"{l:18s} {m(v,'tps'):7.0f} ±{sd(v,'tps'):5.0f} tx/s  x{m(v,'tps')/base:4.2f}  "
              f"lat ms: read {m(v,'lat_read_ms'):.3f} ins {m(v,'lat_insert_ms'):.3f} scan {m(v,'lat_scan_ms'):.2f} write {m(v,'lat_write_ms'):.3f}")
if os.path.exists('results/sweep.csv'):
    print('\n== Thread sweep (10 s, 3 runs): tx/s')
    by = load('results/sweep.csv')
    labels = sorted({l for l, _ in by}); threads = sorted({t for _, t in by})
    print('threads ' + ''.join(f'{l:>22s}' for l in labels))
    for t in threads:
        print(f'{t:7d} ' + ''.join(f"{m(by[(l,t)],'tps'):14.0f} ±{sd(by[(l,t)],'tps'):5.0f}" if (l,t) in by else ' '*22 for l in labels))
if os.path.exists('results/serial_vs_concurrent.csv'):
    by = load('results/serial_vs_concurrent.csv'); ser = m(by[('serial-baseline', 1)], 'seconds')
    n = int(by[('serial-baseline', 1)][0]['committed'])
    print(f'\n== Same stream of {n:,} transactions (seed 42): time until all committed, 3 runs')
    for (l, t), v in by.items():
        print(f"{l:22s} {m(v,'seconds'):7.2f} s ±{sd(v,'seconds'):5.2f}  {m(v,'tps'):7.0f} tx/s  speed-up vs serial-baseline x{ser/m(v,'seconds'):4.2f}  deadlocks {sum(int(r['deadlocks']) for r in v)}")

# ---- machine-readable export for the slide generator (slides/build_slides.js)
import json
out = {}
for name in ('ablation', 'sweep', 'serial_vs_concurrent'):
    f = f'results/{name}.csv'
    if not os.path.exists(f): continue
    by = load(f)
    out[name] = [{'label': l, 'threads': t, 'runs': len(v),
                  **{k: m(v, k) for k in ('tps', 'seconds', 'lat_read_ms', 'lat_insert_ms', 'lat_scan_ms', 'lat_write_ms')},
                  'tps_sd': sd(v, 'tps'), 'seconds_sd': sd(v, 'seconds'),
                  'deadlocks': sum(int(r['deadlocks']) for r in v)} for (l, t), v in by.items()]
json.dump(out, open('results/summary.json', 'w'), indent=1)
