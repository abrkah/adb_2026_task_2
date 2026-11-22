// Builds Homework3_Slides.pptx from the benchmark results (results/summary.json,
// results/demo-*.log, results/profile/*). Style follows Homework2_Slides.pptx.
// Usage: node slides/build_slides.js   (needs: npm install pptxgenjs@3.12.0)
const fs = require("fs");
const path = require("path");
const pptxgen = require("pptxgenjs");

const ROOT = path.join(__dirname, "..");
const R = (f) => path.join(ROOT, "results", f);
const data = JSON.parse(fs.readFileSync(R("summary.json"), "utf8"));
const OUT = process.argv[2] || path.join(ROOT, "Homework3_Slides.pptx");

// ------------------------------------------------------------------ helpers
const find = (set, label, threads) =>
  data[set].find((r) => r.label === label && (threads === undefined || r.threads === threads));
const fmt = (n) => Math.round(n).toLocaleString("en-US");
const f1 = (n) => (Math.round(n * 10) / 10).toFixed(1);

function perSecond(logFile) {           // "[  5s] committed   12345/s  ... in-flight  16"
  if (!fs.existsSync(R(logFile))) return null;
  const pts = [];
  for (const line of fs.readFileSync(R(logFile), "utf8").split("\n")) {
    const m = line.match(/^\[\s*(\d+)s\] committed\s+(\d+)\/s.*in-flight\s+(\d+)/);
    if (m) pts.push({ s: +m[1], tps: +m[2], inflight: +m[3] });
  }
  return pts;
}

// sweep
const sweepLabels = ["baseline", "optimized", "optimized+share"];
const threadsList = [...new Set(data.sweep.map((r) => r.threads))].sort((a, b) => a - b);
const peak = (l) => data.sweep.filter((r) => r.label === l).reduce((a, b) => (b.tps > a.tps ? b : a));
const knee = (l) => {                    // smallest thread count reaching 90% of the peak
  const p = peak(l).tps;
  return data.sweep.filter((r) => r.label === l && r.tps >= 0.9 * p).sort((a, b) => a.threads - b.threads)[0];
};
const one = (l) => find("sweep", l, 1).tps;

// serial vs concurrent
const svc = data.serial_vs_concurrent;
const serialBase = find("serial_vs_concurrent", "serial-baseline");
const svcBest = (prefix) => svc.filter((r) => r.label.startsWith(prefix)).reduce((a, b) => (b.seconds < a.seconds ? b : a));
const totalDeadlocks = ["ablation", "sweep", "serial_vs_concurrent"].reduce(
  (s, k) => s + data[k].reduce((a, r) => a + r.deadlocks, 0), 0);

// ablation
const ab = (l) => find("ablation", l);
const abBase = ab("baseline").tps;

// ------------------------------------------------------------------ theme
const THEME = {
  name: "Homework 3",
  headFontFace: "Georgia",
  bodyFontFace: "Arial",
  colors: {
    dk1: "1F2937", lt1: "FFFFFF", dk2: "6B7280", lt2: "F3F4F6",
    accent1: "2563EB", accent2: "93C5FD", accent3: "DC2626", accent4: "9CA3AF",
    accent5: "059669", accent6: "E5E7EB", hlink: "2563EB", folHlink: "1E40AF",
  },
};
const HEX = THEME.colors;
const pres = new pptxgen();
pres.layout = "LAYOUT_16x9";            // 10 x 5.625 in, as Homework 2
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "Homework 3 - Homework 2 revisited";
const C = pres.SchemeColor;

pres.defineSlideMaster({
  title: "TITLE",
  background: { color: C.background1 },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 1.2, w: 8.8, h: 1.0,
        fontFace: "Georgia", fontSize: 40, bold: true, color: C.text1, valign: "bottom", align: "left", margin: 0 }, text: "" } },
    { placeholder: { options: { name: "sub", type: "body", x: 0.6, y: 2.3, w: 8.8, h: 0.9,
        fontSize: 18, color: C.text2, valign: "top", margin: 0 }, text: "" } },
  ],
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: C.background1 },
  slideNumber: { x: 9.3, y: 5.2, w: 0.4, h: 0.3, fontSize: 10, color: C.text2, align: "right" },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.5, y: 0.3, w: 9.0, h: 0.7,
        fontFace: "Georgia", fontSize: 28, bold: true, color: C.text1, valign: "middle", align: "left", margin: 0 }, text: "" } },
  ],
});

const T = (slide, text, o) => slide.addText(text, { isTextBox: true, fontSize: 14, color: C.text1, margin: 0, ...o });
const card = (slide, x, y, w, h, name) => slide.addShape(pres.shapes.ROUNDED_RECTANGLE,
  { x, y, w, h, rectRadius: 0.08, fill: { color: C.background2 }, line: { color: HEX.accent6, width: 0.75 }, objectName: name });
const stat = (slide, x, y, w, big, small, color) => {
  T(slide, big, { x, y, w, h: 0.6, fontFace: "Georgia", fontSize: 30, bold: true, color: color || C.accent1 });
  T(slide, small, { x, y: y + 0.62, w, h: 0.5, fontSize: 12, color: C.text2, valign: "top" });
};
const caption = (slide, text, y = 5.05) =>
  T(slide, text, { x: 0.5, y, w: 8.6, h: 0.4, fontSize: 10, color: C.text2, valign: "top" });
const chartText = { catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt", dataLabelFontFace: "+mn-lt",
  legendFontFace: "+mn-lt", titleFontFace: "+mn-lt", catAxisLabelColor: HEX.dk2, valAxisLabelColor: HEX.dk2,
  catAxisLabelFontSize: 10, valAxisLabelFontSize: 10, dataLabelFontSize: 10, legendFontSize: 10,
  dataLabelColor: HEX.dk1, valGridLine: { color: HEX.accent6, size: 0.75 }, catGridLine: { style: "none" } };

// ------------------------------------------------------------------ 1 title
pres.addSection({ title: "Introduction" });
let s = pres.addSlide({ masterName: "TITLE", sectionTitle: "Introduction" });
s.addText("Homework 3", { placeholder: "title" });
s.addText("Homework 2 revisited: throughput of a realistic transaction stream on PostgreSQL", { placeholder: "sub" });
T(s, [
  { text: "Tekletsadik Tesfa  12545770", options: { breakLine: true } },
  { text: "Behaylu Gebrekidan  12557517", options: { breakLine: true } },
  { text: "Abirha Berihe  12542738" },
], { x: 0.6, y: 3.6, w: 6, h: 1.0, fontSize: 14, color: C.text1, valign: "top", paraSpaceAfter: 4 });
s.addNotes("Homework 2 made deadlocks easy. Homework 3: a realistic mix of transactions, a continuous stream, " +
  "and the question how much faster than a serial execution we can get. About 8 minutes, roughly 40 s per slide.");

// ------------------------------------------------------------------ 2 workload
pres.addSection({ title: "Part 1: benchmark" });
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 1: benchmark" });
s.addText("A realistic transaction mix", { placeholder: "title" });
const kinds = [
  ["60%", "Multi-point read", "3 random distinct tuples, one SELECT ... WHERE data_id = ? each, print the sum", "p < 0.6"],
  ["10%", "Insert", "one new tuple (ids from 50,000 up), removed when the benchmark restarts", "0.6 ≤ p < 0.7"],
  ["10%", "Full table scan", "SELECT sum(data_value) FROM blocking_data, print the result", "0.7 ≤ p < 0.8"],
  ["20%", "Write", "3 single-tuple UPDATEs by primary key, in generated order", "p ≥ 0.8"],
];
kinds.forEach(([pct, name, desc, cond], i) => {
  const x = 0.5 + i * 2.27;
  card(s, x, 1.25, 2.1, 2.75, "card-" + name);
  T(s, pct, { x: x + 0.15, y: 1.4, w: 1.8, h: 0.6, fontFace: "Georgia", fontSize: 30, bold: true, color: C.accent1 });
  T(s, name, { x: x + 0.15, y: 2.05, w: 1.8, h: 0.35, fontSize: 14, bold: true });
  T(s, desc, { x: x + 0.15, y: 2.45, w: 1.8, h: 1.1, fontSize: 11, color: C.text2, valign: "top" });
  T(s, cond, { x: x + 0.15, y: 3.6, w: 1.8, h: 0.3, fontSize: 10, color: C.text2, fontFace: "Courier New" });
});
T(s, "p is drawn i.i.d. per transaction. Same table and schema as Homework 2: 50,000 tuples, not increased. " +
  "Ordered locking from Homework 2 is removed: write order is never changed.",
  { x: 0.5, y: 4.25, w: 9.0, h: 0.7, fontSize: 13, valign: "top" });
s.addNotes("Benchmark.java, method generate(). Inserted tuples are removed on restart: setupDatabase() reports how many and recreates the table. " +
  "Deadlock handling kept from Homework 2: restart on 40P01 and escalation after 5 aborts (advisory gate lock); the ordered-locking step was dropped as required.");

// ------------------------------------------------------------------ 3 equilibrium
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 1: benchmark" });
s.addText("A continuous stream in equilibrium", { placeholder: "title" });
const kOpt = knee("optimized+share");
const kBase = knee("baseline");
T(s, [
  { text: "Closed loop: ", options: { bold: true } },
  { text: "every worker starts a new transaction as soon as its last one committed.", options: { breakLine: true } },
  { text: "So new = committed: ", options: { bold: true } },
  { text: "transactions in the system stay constant (= threads), arrival rate = commit rate.", options: { breakLine: true } },
  { text: "Finding the right load: ", options: { bold: true } },
  { text: `we raise the thread count until throughput stops growing (Little's law: X = N / R). ` +
      `The knee is at ${kBase.threads} threads (baseline) and ${kOpt.threads} threads (best version).` },
], { x: 0.5, y: 1.2, w: 3.9, h: 3.6, fontSize: 13, valign: "top", paraSpaceAfter: 10 });
const ts = perSecond("demo-30s-optimized.log");
if (ts) {
  s.addChart(pres.charts.LINE, [
    { name: "committed / s", labels: ts.map((p) => p.s + "s"), values: ts.map((p) => p.tps) },
  ], { x: 4.6, y: 1.1, w: 5.0, h: 3.6, ...chartText, chartColors: [HEX.accent1], lineSize: 2, lineDataSymbol: "none",
    showTitle: true, title: `Committed transactions per second, 30 s run, ${ts[0].inflight} threads`, titleFontSize: 11,
    titleColor: HEX.dk1, showLegend: false, valAxisMinVal: 0, catAxisLabelFrequency: 5 });
  const pages = (fs.readFileSync(R("demo-30s-optimized.log"), "utf8").match(/pages=(\d+)/g) || []).map((x) => x.split("=")[1]);
  caption(s, `In-flight stays at ${ts[0].inflight} every second, but throughput drifts down ` +
    `(${fmt(Math.max(...ts.map((p) => p.tps)))} → ${fmt(ts[ts.length - 1].tps)}/s): ` +
    `the inserts grow the table that every scan reads (${pages[0]} → ${pages[1]} pages in 30 s).`, 4.75);
}
s.addNotes("Equilibrium: in a closed system with N clients, throughput X = N / R (R = response time). Below the knee, X grows with N; " +
  "above it, R grows linearly and X stays flat - extra threads only queue. The monitor line printed every second shows " +
  "committed/s and in-flight; in-flight is constant = threads. We pick the thread count at the knee (90% of peak).");

// ------------------------------------------------------------------ 4 demo
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 1: benchmark" });
s.addText("Live demo: 30 seconds", { placeholder: "title" });
card(s, 0.5, 1.2, 9.0, 1.0, "demo-command");
T(s, "scripts/demo.sh best " + kOpt.threads + "          scripts/demo.sh baseline " + kBase.threads,
  { x: 0.75, y: 1.3, w: 8.6, h: 0.8, fontFace: "Courier New", fontSize: 14, valign: "middle" });
const watch = [
  ["1", "Committed per second", "one line per second; stays flat = equilibrium"],
  ["2", "In-flight", "constant, equal to the thread count"],
  ["3", "Sample output", "sums of reads and scans, every 2000th transaction"],
];
watch.forEach(([n, h, d], i) => {
  const x = 0.5 + i * 3.05;
  s.addShape(pres.shapes.OVAL, { x, y: 2.6, w: 0.5, h: 0.5, fill: { color: C.accent1 }, line: { color: HEX.accent1 } });
  T(s, n, { x, y: 2.6, w: 0.5, h: 0.5, fontSize: 16, bold: true, color: C.background1, align: "center", valign: "middle" });
  T(s, h, { x: x + 0.65, y: 2.6, w: 2.25, h: 0.5, fontSize: 14, bold: true, valign: "middle" });
  T(s, d, { x: x + 0.65, y: 3.15, w: 2.25, h: 0.9, fontSize: 12, color: C.text2, valign: "top" });
});
caption(s, "Each transaction prints its result (sum) to the console; the demo shows a sample so the screen stays readable. " +
  "Recordings: results/demo-30s-*.log", 4.4);
s.addNotes("Run the optimized version first, then the baseline for contrast. Point at: committed/s per second line, in-flight = threads, 0 deadlocks.");

// ------------------------------------------------------------------ 5 scaling
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 1: benchmark" });
s.addText("More threads help, but not linearly", { placeholder: "title" });
const names = { baseline: "Baseline", optimized: "One round trip", "optimized+share": "+ shared scans" };
s.addChart(pres.charts.LINE, sweepLabels.map((l) => ({
  name: names[l], labels: threadsList.map(String),
  values: threadsList.map((t) => (find("sweep", l, t) || {}).tps || 0),
})), { x: 0.4, y: 1.1, w: 6.0, h: 3.8, ...chartText, chartColors: [HEX.accent4, HEX.accent2, HEX.accent1],
  lineSize: 2, lineDataSymbolSize: 5, showLegend: true, legendPos: "b", valAxisMinVal: 0,
  showTitle: true, title: "Committed transactions per second vs. client threads (mean of 3 x 10 s)", titleFontSize: 11,
  titleColor: HEX.dk1, showCatAxisTitle: true, catAxisTitle: "client threads", catAxisTitleFontSize: 10, catAxisTitleColor: HEX.dk2 });
const lin = (l, t) => (find("sweep", l, t).tps / (one(l) * t));
stat(s, 6.8, 1.2, 2.7, f1(peak("baseline").tps / one("baseline")) + "×",
  `baseline: peak at ${peak("baseline").threads} threads vs. 1 thread`, C.text2);
stat(s, 6.8, 2.4, 2.7, Math.round(100 * lin("baseline", 4)) + "%",
  "of linear speed-up at 4 threads (baseline)");
stat(s, 6.8, 3.6, 2.7, "39% idle",
  "CPU at the plateau (one round trip, 16 threads): not CPU-bound");
s.addNotes("Speed-up is close to linear only up to about 4 threads. At the plateau the CPU is NOT saturated (top: 39% idle at 16 threads, " +
  "optimized). Instead the scans slow down from 2.5 ms (alone) to 8 ms: scans and writes compete for the same ~400 pages " +
  "(buffer content locks, visibility / hint-bit work on freshly updated tuples). That is why sharing scans lifts the plateau. " +
  "10 cores (8 performance + 2 efficiency) shared by Java and PostgreSQL.");

// ------------------------------------------------------------------ 6 bottlenecks
pres.addSection({ title: "Part 2: bottlenecks" });
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 2: bottlenecks" });
s.addText("Where does the time go?", { placeholder: "title" });
const cols = [
  ["94%", "of PostgreSQL execution time is the full table scan", "pg_stat_statements, 8 threads: scan 4.2 ms per call, 10% of transactions"],
  ["7.5 µs", "server time of a point SELECT, but ~0.2 ms per round trip", "4 round trips per read transaction: the network path dominates"],
  ["12", "CPU samples in the Java client in 15 s", "Java Flight Recorder: threads wait in socket reads; the client is not the bottleneck"],
];
cols.forEach(([big, h, d], i) => {
  const x = 0.5 + i * 3.05;
  card(s, x, 1.25, 2.85, 3.1, "bottleneck-" + i);
  T(s, big, { x: x + 0.2, y: 1.4, w: 2.5, h: 0.7, fontFace: "Georgia", fontSize: 32, bold: true, color: i === 0 ? C.accent3 : C.accent1 });
  T(s, h, { x: x + 0.2, y: 2.15, w: 2.5, h: 0.9, fontSize: 14, bold: true, valign: "top" });
  T(s, d, { x: x + 0.2, y: 3.1, w: 2.5, h: 1.1, fontSize: 11, color: C.text2, valign: "top" });
});
caption(s, "Also checked: EXPLAIN (ANALYZE, BUFFERS) - Seq Scan, all ~400 pages in shared buffers, no I/O; an index on data_value is not used. " +
  "0 deadlocks with 3 of 50,000 tuples per write.", 4.55);
s.addNotes("Tools: pg_stat_statements (shared_preload_libraries), auto_explain, log_lock_waits, EXPLAIN ANALYZE, pgbench micro-benchmarks, " +
  "Java Flight Recorder (java -XX:StartFlightRecording). Files: results/profile/. The trivial result: the scan is the expensive query, " +
  "and short transactions are dominated by round trips, not by the database work.");

// ------------------------------------------------------------------ 7 fix 1
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 2: bottlenecks" });
s.addText("Fix 1: one round trip per transaction", { placeholder: "title" });
T(s, [
  { text: "Before: ", options: { bold: true } },
  { text: "BEGIN + 3 SELECTs + COMMIT = 4 round trips; writes 5 with the gate lock.", options: { breakLine: true } },
  { text: "After: ", options: { bold: true } },
  { text: "the same statements in one message (\"SELECT ...; SELECT ...; SELECT ...\").", options: { breakLine: true } },
  { text: "Still a transaction: ", options: { bold: true } },
  { text: "PostgreSQL runs all messages up to one Sync as one implicit transaction. Checked: identical now() and full rollback on error.", options: { breakLine: true } },
  { text: "Unchanged: ", options: { bold: true } },
  { text: "one SQL statement per tuple, write order, isolation level." },
], { x: 0.5, y: 1.2, w: 4.4, h: 3.7, fontSize: 13, valign: "top", paraSpaceAfter: 8 });
const opt = ab("optimized"), base = ab("baseline");
s.addChart(pres.charts.BAR, [
  { name: "Baseline", labels: ["Read", "Insert", "Write"], values: [base.lat_read_ms, base.lat_insert_ms, base.lat_write_ms] },
  { name: "One round trip", labels: ["Read", "Insert", "Write"], values: [opt.lat_read_ms, opt.lat_insert_ms, opt.lat_write_ms] },
], { x: 5.1, y: 1.1, w: 4.5, h: 3.5, barDir: "col", ...chartText, chartColors: [HEX.accent4, HEX.accent1],
  showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", showLegend: true, legendPos: "b",
  showTitle: true, title: "Mean latency in ms, 8 threads", titleFontSize: 11, titleColor: HEX.dk1, valAxisMinVal: 0 });
caption(s, `Throughput at 8 threads: ${fmt(base.tps)} → ${fmt(opt.tps)} tx/s (×${f1(opt.tps / base.tps)}). ` +
  "Kernel CPU time at 16 threads: 35% → 14% (fewer socket calls).", 4.75);
s.addNotes("JDBC: with autocommit on, pgjdbc sends a multi-statement PreparedStatement with a single Sync message. " +
  "Verified in a small test: three statements see the same now() (= transaction start); a failing third INSERT rolls back the first two. " +
  "Deadlock victims are still detected (40P01) and retried. Source: PostgreSQL docs, 'Extended Query' protocol (55.2.3) and pgjdbc docs.");

// ------------------------------------------------------------------ 8 fix 2
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 2: bottlenecks" });
s.addText("Fix 2: concurrent scans share one execution", { placeholder: "title" });
const steps = [
  ["Scan arrives", "free lane: run now. All lanes busy: join the open round"],
  ["Round closes", "right before its leader sends SELECT sum(data_value)"],
  ["Result to all", "every member started before the snapshot and gets the result before it ends"],
];
steps.forEach(([h, d], i) => {
  const y = 1.2 + i * 1.1;
  s.addShape(pres.shapes.OVAL, { x: 0.5, y, w: 0.5, h: 0.5, fill: { color: C.accent1 }, line: { color: HEX.accent1 } });
  T(s, String(i + 1), { x: 0.5, y, w: 0.5, h: 0.5, fontSize: 16, bold: true, color: C.background1, align: "center", valign: "middle" });
  T(s, h, { x: 1.15, y: y - 0.02, w: 3.6, h: 0.35, fontSize: 14, bold: true });
  T(s, d, { x: 1.15, y: y + 0.33, w: 3.6, h: 0.6, fontSize: 12, color: C.text2, valign: "top" });
});
const sh = ab("opt+shareScans");
stat(s, 5.4, 1.2, 4.1, `${f1(sh.tps / opt.tps)}×`, `throughput vs. Fix 1 at 8 threads (${fmt(opt.tps)} → ${fmt(sh.tps)} tx/s)`);
stat(s, 5.4, 2.45, 4.1, fmt(peak("optimized+share").tps), `tx/s peak at ${peak("optimized+share").threads} threads (Fix 1 alone: ${fmt(peak("optimized").tps)})`);
card(s, 5.4, 3.75, 4.1, 0.95, "caveat");
T(s, "Each scan still sees the table at a moment inside its own lifetime, but one query answers several transactions. " +
  "Shown as a separate variant.", { x: 5.55, y: 3.82, w: 3.85, h: 0.85, fontSize: 11, color: C.text2, valign: "middle" });
caption(s, "Idea: shared / cooperative scans (Zukowski et al., VLDB 2007; PostgreSQL synchronize_seqscans). Lanes: 2 was best of 2, 4, 8.", 4.95);
s.addNotes("Without contention a scan runs immediately, so no extra latency. Correctness argument: the leader removes the round " +
  "from 'open' before sending the query; the query's snapshot is therefore taken after every member started, and the result " +
  "is handed out before any member finishes - like linearizability for a read. Fewer lanes = more sharing; 2 lanes was best.");

// ------------------------------------------------------------------ 9 ablation
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 2: bottlenecks" });
s.addText("What helped, and what did not", { placeholder: "title" });
const abRows = [
  ["Shared scans", "opt+shareScans"], ["One round trip (all)", "optimized"], ["Pipelining only", "base+pipeline"],
  ["Autocommit 1-stmt", "base+autocommit1"], ["synchronous_commit off", "base+syncOff"], ["Batched writes", "base+batchWrites"],
  ["Statement cache", "base+stmtCache"], ["Buffered console", "base+out"], ["Autovacuum tuning", "base+avTune"],
  ["fillfactor 70", "base+fillfactor70"], ["No gate lock", "base-gate"],
].filter(([, l]) => ab(l));
s.addChart(pres.charts.BAR, [{ name: "speed-up vs. baseline", labels: abRows.map((r) => r[0]),
  values: abRows.map(([, l]) => Math.round(100 * ab(l).tps / abBase) / 100) }],
  { x: 0.4, y: 1.05, w: 5.9, h: 4.0, barDir: "bar", ...chartText, chartColors: [HEX.accent1], catAxisOrientation: "maxMin",
    showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00\"×\"", showLegend: false, valAxisMinVal: 0,
    showTitle: true, title: "Throughput relative to baseline, 8 threads (mean of 3 x 10 s)", titleFontSize: 11, titleColor: HEX.dk1 });
T(s, [
  { text: "Only round trips and the scan matter.", options: { bold: true, breakLine: true } },
  { text: "Everything else is within ±3% (noise): the database work of a short transaction is tiny.", options: { breakLine: true } },
  { text: "Textbook tuning did not help.", options: { bold: true, breakLine: true } },
  { text: "synchronous_commit off, fillfactor 70, autovacuum tuning: 98% of updates are already HOT without them." },
], { x: 6.5, y: 1.2, w: 3.0, h: 3.6, fontSize: 12, valign: "top", paraSpaceAfter: 6 });
s.addNotes("Each bar switches ONE option on, starting from the baseline (shared scans and 'one round trip (all)' start from the optimized preset). " +
  "Data: results/ablation.csv, scripts/ablation.sh. The deadlock gate (one extra statement per write) costs little; " +
  "we keep it because it guarantees that every transaction commits.");

// ------------------------------------------------------------------ 10 serial
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Part 2: bottlenecks" });
s.addText("Serial vs. concurrent on the same stream", { placeholder: "title" });
const svcRows = [
  ["Serial, baseline", "serial-baseline"], ["Serial, one round trip", "serial-optimized"],
  [`Concurrent baseline (${svcBest("conc-baseline").threads} thr.)`, svcBest("conc-baseline").label],
  [`One round trip (${svcBest("conc-optimized").threads} thr.)`, svcBest("conc-optimized").label],
  [`+ shared scans (${svcBest("conc-opt+share").threads} thr.)`, svcBest("conc-opt+share").label],
];
s.addChart(pres.charts.BAR, [{ name: "seconds", labels: svcRows.map((r) => r[0]),
  values: svcRows.map(([, l]) => Math.round(10 * find("serial_vs_concurrent", l).seconds) / 10) }],
  { x: 0.4, y: 1.05, w: 5.9, h: 3.8, barDir: "bar", ...chartText, chartColors: [HEX.accent1], catAxisOrientation: "maxMin",
    showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.0\" s\"", showLegend: false, valAxisMinVal: 0,
    showTitle: true, title: "Time until all transactions committed (mean of 3 runs)", titleFontSize: 11, titleColor: HEX.dk1 });
const bestAll = svcBest("conc-opt+share"), bestFix1 = svcBest("conc-optimized");
stat(s, 6.6, 1.2, 2.9, `${f1(serialBase.seconds / bestFix1.seconds)}×`, "faster than serial: one round trip, concurrent");
stat(s, 6.6, 2.4, 2.9, `${f1(serialBase.seconds / bestAll.seconds)}×`, "with shared scans");
stat(s, 6.6, 3.6, 2.9, String(totalDeadlocks), "deadlocks in all benchmark runs; every transaction committed", C.text2);
caption(s, `Fixed stream of ${fmt(Math.round(serialBase.tps * serialBase.seconds / 1000) * 1000)} transactions, seed 42, generated before the clock starts. ` +
  "Serial = 1 thread, 1 connection, no gate.", 4.95);
s.addNotes("Same pre-generated stream for every variant (scripts/serial_vs_concurrent.sh). Grouping/pre-processing: none needed, " +
  "so nothing to add to the run time. Connections are opened before the clock starts, as allowed.");

// ------------------------------------------------------------------ 11 unexpected
pres.addSection({ title: "Wrap-up" });
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Wrap-up" });
s.addText("Unexpected results", { placeholder: "title" });
const surprises = [
  ["Deadlocks vanished", `${totalDeadlocks} in all runs. With single-tuple writes on 50,000 tuples the Homework 2 problem disappears; the restart path is only insurance.`],
  ["10% of queries, 94% of the work", "The full scan dominates the database; point queries cost almost nothing on the server."],
  ["The stream is not stationary", "10% inserts grow the table from 50,000 to ~139,000 rows in 30 s, so every scan gets ~3× dearer and throughput sinks by a third."],
  ["Not CPU-bound, and serial barely gains", "At the plateau 39% of the CPU is idle; scans slow from 2.5 to 8 ms. With 1 thread, one round trip gains only " +
    `×${f1(serialBase.seconds / find("serial_vs_concurrent", "serial-optimized").seconds)}: idle round trips are cheap.`],
];
surprises.forEach(([h, d], i) => {
  const x = 0.5 + (i % 2) * 4.6, y = 1.2 + Math.floor(i / 2) * 1.85;
  card(s, x, y, 4.4, 1.65, "surprise-" + i);
  T(s, h, { x: x + 0.2, y: y + 0.15, w: 4.0, h: 0.4, fontSize: 15, bold: true });
  T(s, d, { x: x + 0.2, y: y + 0.6, w: 4.0, h: 0.95, fontSize: 12, color: C.text2, valign: "top" });
});
s.addNotes("Also unexpected: concurrent speed-up is far from linear - not because of locks, but because client and server " +
  "compete for the same 10 cores and the scans are CPU-bound.");

// ------------------------------------------------------------------ 12 issues + team
s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Wrap-up" });
s.addText("Issues, team and sources", { placeholder: "title" });
T(s, "Issues we faced", { x: 0.5, y: 1.15, w: 4.3, h: 0.4, fontSize: 15, bold: true });
T(s, [
  { text: "Ordered locking had to go: no reordering of writes allowed.", options: { bullet: true, breakLine: true } },
  { text: "Multi-statement results: the void advisory-lock result broke parsing.", options: { bullet: true, breakLine: true } },
  { text: "Background load (an Android emulator) made first runs noisy: logged system load, re-ran on an idle machine, repeats interleaved.", options: { bullet: true, breakLine: true } },
  { text: "Shared scans: proving they keep transaction semantics.", options: { bullet: true } },
], { x: 0.5, y: 1.6, w: 4.3, h: 2.2, fontSize: 12, valign: "top", paraSpaceAfter: 6 });
T(s, "Contribution", { x: 5.2, y: 1.15, w: 4.3, h: 0.4, fontSize: 15, bold: true });
s.addTable([
  [{ text: "Tekletsadik Tesfa", options: {} }, { text: "33%", options: { align: "right" } }],
  [{ text: "Behaylu Gebrekidan", options: {} }, { text: "33%", options: { align: "right" } }],
  [{ text: "Abirha Berihe", options: {} }, { text: "33%", options: { align: "right" } }],
], { x: 5.2, y: 1.6, w: 4.3, colW: [3.2, 1.1], fontSize: 12, fontFace: "Arial", color: HEX.dk1,
  border: { type: "solid", pt: 0.75, color: HEX.accent6 }, fill: { color: HEX.lt2 }, rowH: 0.36 });
T(s, "Sources", { x: 0.5, y: 3.85, w: 9, h: 0.35, fontSize: 13, bold: true });
T(s, "PostgreSQL 14 docs: frontend/backend protocol (extended query, Sync), pg_stat_statements, auto_explain, advisory locks · " +
  "pgjdbc docs · M. Zukowski et al., Cooperative Scans, VLDB 2007 · Java Flight Recorder (JDK 17) · " +
  "AI assistant (Claude) used for code, measurements and slides; all results reviewed and reproduced by the team.",
  { x: 0.5, y: 4.2, w: 9.0, h: 0.8, fontSize: 10, color: C.text2, valign: "top" });
s.addNotes("Fill in the real contribution percentages before submitting.");

pres.writeFile({ fileName: OUT }).then(async (f) => {
  const skillTheme = process.env.APPLY_THEME;
  if (skillTheme) await require(skillTheme).applyTheme(f, THEME);
  console.log("wrote " + f);
});
