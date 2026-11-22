import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark.java  (Homework 3 - "Homework 2 revisited")
 *
 * Realistic transaction mix on blocking_data (50,000 tuples, schema unchanged).
 * For every transaction a value p in [0,1) is drawn i.i.d.:
 *   p < 0.6        READ   - 3 distinct random tuples, one
 *                           "SELECT data_value FROM blocking_data WHERE data_id = ?"
 *                           per tuple, prints the sum
 *   0.6 <= p < 0.7 INSERT - one new tuple (ids >= 50,000; removed on next start)
 *   0.7 <= p < 0.8 SCAN   - "SELECT sum(data_value) FROM blocking_data", prints result
 *   p >= 0.8       WRITE  - 3 single-tuple UPDATEs (by primary key), order unchanged
 *
 * Two ways to feed the workers:
 *   duration=S  continuous stream (closed loop): every worker starts a new
 *               transaction as soon as its previous one committed, so the number of
 *               transactions in the system is constant (= threads) and the arrival
 *               rate equals the commit rate (equilibrium).
 *   count=N     a fixed, pre-generated stream of N transactions (same seed = same
 *               stream); the run ends when all N have committed. Used to compare
 *               serial and concurrent execution on exactly the same work.
 *
 * Every transaction commits eventually: deadlock victims (40P01) are rolled back
 * and retried; after maxOptimistic aborts a WRITE escalates and takes an exclusive
 * advisory "gate" lock, which all other WRITEs hold shared, so it runs without
 * concurrent writers and cannot be a deadlock victim again. Write order inside a
 * transaction is never changed (no canonical locking).
 *
 * Usage: java -cp out:lib/postgresql-42.2.18.jar Benchmark key=value ...
 *   threads=8 duration=30 | count=20000   warmup=2   seed=42
 *   preset=baseline|optimized   (individual switches below override the preset)
 *   out=sync|buffered|none      stmtCache=   batchWrites=   autocommitSingle=
 *   syncCommit=on|off  fillfactor=100  avTune=  gate=true maxOptimistic=5
 *   csv=results/file.csv label=name  setup=true  print=true
 */
public class Benchmark {

    // ---------------------------------------------------------------- constants
    static final int NUM_TUPLE = 50_000;               // never increased (hint)
    static final int NUM_QUERIES_PER_TASK = 3;
    static final double MAX_VALUE = 50.0;
    static final long GATE_KEY = 4242L;

    static final String SQL_READ   = "SELECT data_value FROM blocking_data WHERE data_id = ?";
    static final String SQL_INSERT = "INSERT INTO blocking_data (data_id, data_value, modified_by) VALUES (?, ?, ?)";
    static final String SQL_SCAN   = "SELECT sum(data_value) FROM blocking_data";
    static final String SQL_WRITE  = "UPDATE blocking_data SET data_value = ?, modified_by = ? WHERE data_id = ?";
    static final String SQL_GATE_SHARED    = "SELECT pg_advisory_xact_lock_shared(" + GATE_KEY + ")";
    static final String SQL_GATE_EXCLUSIVE = "SELECT pg_advisory_xact_lock(" + GATE_KEY + ")";

    // Pipelined variants: the same statements, sent in ONE network round trip.
    // In autocommit mode the PostgreSQL driver sends them with a single Sync, so
    // the server runs them as ONE implicit transaction (atomic; verified: a failing
    // statement rolls back the earlier ones, now() is identical for all of them).
    static final String SQL_READ3 = String.join("; ", SQL_READ, SQL_READ, SQL_READ);
    static final String SQL_WRITE3 = String.join("; ", SQL_WRITE, SQL_WRITE, SQL_WRITE);
    static final String SQL_GATE_S_WRITE3 = SQL_GATE_SHARED + "; " + SQL_WRITE3;
    static final String SQL_GATE_X_WRITE3 = SQL_GATE_EXCLUSIVE + "; " + SQL_WRITE3;

    enum Type { READ, INSERT, SCAN, WRITE }
    static final Type[] TYPES = Type.values();

    // ------------------------------------------------------------ configuration
    final Map<String, String> cfg = new LinkedHashMap<>();
    int threads, count, warmup, fillfactor, maxOptimistic;
    double duration;
    long seed;
    String out, syncCommit, label, csv;
    boolean stmtCache, batchWrites, autocommitSingle, avTune, gate, setup, print, pipeline, shareScans;
    int scanLanes;
    ScanSharer scanSharer;

    // -------------------------------------------------------------- statistics
    final LongAdder[] committed = new LongAdder[TYPES.length];
    final LongAdder[] latencyNs = new LongAdder[TYPES.length];
    final LongAdder started = new LongAdder();
    final LongAdder deadlocks = new LongAdder();
    final LongAdder otherRetries = new LongAdder();
    final LongAdder escalations = new LongAdder();
    final AtomicInteger maxAttempts = new AtomicInteger(1);
    final AtomicInteger nextInsertId = new AtomicInteger(NUM_TUPLE);
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicLong txSeq = new AtomicLong();
    final LongAdder scanExecutions = new LongAdder();   // actual sum() queries sent

    Benchmark() {
        for (int i = 0; i < TYPES.length; i++) {
            committed[i] = new LongAdder();
            latencyNs[i] = new LongAdder();
        }
    }

    // ------------------------------------------------------------- the workload

    /** One generated transaction. The type is decided by p, drawn i.i.d. */
    static final class Tx {
        final long id;
        final Type type;
        final int[] ids;        // READ / WRITE: tuple ids, INSERT: new id
        final double[] values;  // WRITE: new values, INSERT: value
        Tx(long id, Type type, int[] ids, double[] values) {
            this.id = id; this.type = type; this.ids = ids; this.values = values;
        }
    }

    Tx generate(Random r) {
        long id = txSeq.incrementAndGet();
        double p = r.nextDouble();
        if (p < 0.6) {
            return new Tx(id, Type.READ, distinctIds(r), null);
        } else if (p < 0.7) {
            return new Tx(id, Type.INSERT, new int[] { nextInsertId.getAndIncrement() },
                          new double[] { r.nextDouble() * MAX_VALUE });
        } else if (p < 0.8) {
            return new Tx(id, Type.SCAN, null, null);
        } else {
            double[] v = new double[NUM_QUERIES_PER_TASK];
            for (int i = 0; i < v.length; i++) v[i] = r.nextDouble() * MAX_VALUE;
            return new Tx(id, Type.WRITE, distinctIds(r), v);
        }
    }

    static int[] distinctIds(Random r) {
        int[] ids = new int[NUM_QUERIES_PER_TASK];
        for (int i = 0; i < ids.length; i++) {
            int candidate;
            boolean dup;
            do {
                candidate = r.nextInt(NUM_TUPLE);
                dup = false;
                for (int j = 0; j < i; j++) dup |= ids[j] == candidate;
            } while (dup);
            ids[i] = candidate;   // random order: NOT sorted (no canonical locking)
        }
        return ids;
    }

    /** Supplies transactions to the workers: a fixed list or an endless stream. */
    interface Source { Tx next(Random workerRandom); }

    // ---------------------------------------------------------- console output

    /** Where per-transaction result lines go. */
    interface Sink { void line(String s); void flush(); }

    /** Baseline: System.out.println per transaction (global lock + write per line). */
    static final class SyncSink implements Sink {
        public void line(String s) { System.out.println(s); }
        public void flush() { System.out.flush(); }
    }

    /** Optimized: each worker collects lines and writes them in large chunks. */
    static final class BufferedSink implements Sink {
        static final OutputStream STDOUT =
            new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        final StringBuilder sb = new StringBuilder(1 << 15);
        public void line(String s) {
            sb.append(s).append('\n');
            if (sb.length() > 16_000) flush();
        }
        public void flush() {
            if (sb.length() == 0) return;
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            sb.setLength(0);
            synchronized (STDOUT) {
                try { STDOUT.write(bytes); STDOUT.flush(); } catch (IOException ignored) { }
            }
        }
    }

    static final class NoSink implements Sink {
        public void line(String s) { }
        public void flush() { }
    }

    Sink newSink() {
        switch (out) {
            case "buffered": return new BufferedSink();
            case "none":     return new NoSink();
            default:         return new SyncSink();
        }
    }

    // ------------------------------------------------------------ scan sharing

    /**
     * Shared scans (cf. "cooperative scans"): SCAN transactions that are waiting at
     * the same time are answered by ONE execution of SELECT sum(data_value).
     *
     * At most `lanes` scans run concurrently. A SCAN arriving while all lanes are busy
     * joins the open (not yet started) round; the first one of a round is its leader.
     * The leader closes the round right before it sends the query, so the query's
     * snapshot is taken AFTER every member of the round started and the result is
     * delivered BEFORE any of them finishes: each SCAN sees the database state at a
     * point inside its own lifetime, exactly as if it had run the query itself.
     * Without contention (a free lane) a scan runs immediately - no added latency.
     */
    final class ScanSharer {
        final java.util.concurrent.Semaphore lanes;
        Round open;                       // guarded by this

        final class Round {
            final Thread leader = Thread.currentThread();
            final java.util.concurrent.CompletableFuture<Double> result =
                new java.util.concurrent.CompletableFuture<>();
        }

        ScanSharer(int lanes) { this.lanes = new java.util.concurrent.Semaphore(lanes, true); }

        double scan(Worker w) throws SQLException {
            Round r;
            synchronized (this) {
                if (open == null) open = new Round();
                r = open;
            }
            if (r.leader != Thread.currentThread()) {
                try {
                    return r.result.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof SQLException) throw (SQLException) e.getCause();
                    throw new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            lanes.acquireUninterruptibly();
            try {
                synchronized (this) { if (open == r) open = null; }    // close the round
                double v = w.scanOnce();
                r.result.complete(v);
                return v;
            } catch (SQLException | RuntimeException e) {
                r.result.completeExceptionally(e);
                throw e;
            } finally {
                lanes.release();
            }
        }
    }

    // ------------------------------------------------------------------ worker

    final class Worker implements Runnable {
        final int wid;
        final Source source;
        final Random random;
        final Sink sink;
        Connection con;
        PreparedStatement psRead, psInsert, psScan, psWrite, psGateS, psGateX;
        PreparedStatement psRead3, psWrite3S, psWrite3X;

        Worker(int wid, Source source) {
            this.wid = wid;
            this.source = source;
            this.random = new Random(seed * 1_000_003L + wid);
            this.sink = newSink();
        }

        /** Connections are opened before the clock starts (not measured). */
        void open() throws SQLException {
            con = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            con.setAutoCommit(false);
            if ("off".equals(syncCommit)) {
                try (Statement st = con.createStatement()) { st.execute("SET synchronous_commit = off"); }
                con.commit();
            }
            if (pipeline) con.setAutoCommit(true);   // every transaction = one implicit transaction
        }

        /** Baseline: prepare every statement anew (as in Homework 2). */
        PreparedStatement ps(String sql, PreparedStatement cached) throws SQLException {
            return stmtCache ? cached : con.prepareStatement(sql);
        }

        void prepareAll() throws SQLException {
            if (!stmtCache) return;
            psRead = con.prepareStatement(SQL_READ);
            psInsert = con.prepareStatement(SQL_INSERT);
            psScan = con.prepareStatement(SQL_SCAN);
            psWrite = con.prepareStatement(SQL_WRITE);
            psGateS = con.prepareStatement(SQL_GATE_SHARED);
            psGateX = con.prepareStatement(SQL_GATE_EXCLUSIVE);
            psRead3 = con.prepareStatement(SQL_READ3);
            psWrite3S = con.prepareStatement(gate ? SQL_GATE_S_WRITE3 : SQL_WRITE3);
            psWrite3X = con.prepareStatement(SQL_GATE_X_WRITE3);
        }

        /** Executes a (possibly multi-statement) prepared statement, sums all result sets. */
        double executeAll(PreparedStatement p) throws SQLException {
            double sum = 0;
            boolean isResult = p.execute();
            while (true) {
                if (isResult) {
                    try (ResultSet rs = p.getResultSet()) {
                        // skip the void result of the advisory-lock SELECT
                        if (rs.next() && rs.getMetaData().getColumnType(1) == java.sql.Types.DOUBLE)
                            sum += rs.getDouble(1);
                    }
                } else if (p.getUpdateCount() == -1) {
                    return sum;
                }
                isResult = p.getMoreResults();
            }
        }

        /** One real execution of the full-table scan. */
        double scanOnce() throws SQLException {
            scanExecutions.increment();
            boolean ac = autocommitSingle || pipeline;
            if (ac && !con.getAutoCommit()) con.setAutoCommit(true);
            try {
                PreparedStatement p = ps(SQL_SCAN, psScan);
                double sum;
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    sum = rs.getDouble(1);
                }
                release(p);
                if (!ac) con.commit();
                return sum;
            } finally {
                if (ac && !pipeline) con.setAutoCommit(false);
            }
        }

        void release(PreparedStatement p) throws SQLException {
            if (!stmtCache) p.close();
        }

        @Override
        public void run() {
            try {
                Tx tx;
                while ((tx = source.next(random)) != null) {
                    started.increment();
                    long t0 = System.nanoTime();
                    String result = executeUntilCommit(tx);
                    long dt = System.nanoTime() - t0;
                    committed[tx.type.ordinal()].increment();
                    latencyNs[tx.type.ordinal()].add(dt);
                    if (print) sink.line(result);
                }
            } finally {
                sink.flush();
            }
        }

        /** Runs tx; on deadlock: roll back, back off, retry; escalate after K aborts. */
        String executeUntilCommit(Tx tx) {
            for (int attempt = 1; ; attempt++) {
                try {
                    String r = executeOnce(tx, attempt > maxOptimistic);
                    if (attempt > 1) maxAttempts.accumulateAndGet(attempt, Math::max);
                    if (attempt > maxOptimistic) escalations.increment();
                    return r;
                } catch (SQLException e) {
                    try {
                        if (!con.getAutoCommit()) con.rollback();
                    } catch (SQLException ignored) { }
                    String state = e.getSQLState();
                    if ("40P01".equals(state)) deadlocks.increment();
                    else if ("40001".equals(state) || "55P03".equals(state)) otherRetries.increment();
                    else throw new IllegalStateException("tx " + tx.id + " failed: " + state + " " + e.getMessage(), e);
                    try {
                        Thread.sleep(Math.min(attempt, 10) * 2L + random.nextInt(5));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ie);
                    }
                }
            }
        }

        String executeOnce(Tx tx, boolean escalated) throws SQLException {
            if (pipeline && tx.type == Type.READ) {
                for (int i = 0; i < 3; i++) psRead3.setInt(i + 1, tx.ids[i]);
                double sum = executeAll(psRead3);
                return "Tx " + tx.id + " READ " + tx.ids[0] + "," + tx.ids[1] + "," + tx.ids[2] + " sum=" + sum;
            }
            if (pipeline && tx.type == Type.WRITE) {
                PreparedStatement p = escalated ? psWrite3X : psWrite3S;
                for (int q = 0; q < 3; q++) {                    // original order kept
                    p.setDouble(3 * q + 1, tx.values[q]);
                    p.setInt(3 * q + 2, (int) tx.id);
                    p.setInt(3 * q + 3, tx.ids[q]);
                }
                executeAll(p);
                return "Tx " + tx.id + " WRITE " + tx.ids[0] + "," + tx.ids[1] + "," + tx.ids[2]
                       + (escalated ? " (escalated)" : "");
            }
            switch (tx.type) {
                case READ: {
                    double sum = 0;
                    for (int id : tx.ids) {
                        PreparedStatement p = ps(SQL_READ, psRead);
                        p.setInt(1, id);
                        try (ResultSet rs = p.executeQuery()) {
                            if (rs.next()) sum += rs.getDouble(1);
                        }
                        release(p);
                    }
                    con.commit();
                    return "Tx " + tx.id + " READ " + tx.ids[0] + "," + tx.ids[1] + "," + tx.ids[2] + " sum=" + sum;
                }
                case INSERT: {
                    boolean ac = autocommitSingle || pipeline;
                    if (ac && !pipeline) con.setAutoCommit(true);
                    try {
                        PreparedStatement p = ps(SQL_INSERT, psInsert);
                        p.setInt(1, tx.ids[0]);
                        p.setDouble(2, tx.values[0]);
                        p.setInt(3, (int) tx.id);
                        p.executeUpdate();
                        release(p);
                        if (!ac) con.commit();
                    } finally {
                        if (ac && !pipeline) con.setAutoCommit(false);
                    }
                    return "Tx " + tx.id + " INSERT id=" + tx.ids[0];
                }
                case SCAN: {
                    double sum = shareScans ? scanSharer.scan(this) : scanOnce();
                    return "Tx " + tx.id + " SCAN sum=" + sum;
                }
                case WRITE: {
                    if (gate) {
                        PreparedStatement g = escalated ? ps(SQL_GATE_EXCLUSIVE, psGateX)
                                                        : ps(SQL_GATE_SHARED, psGateS);
                        g.execute();
                        release(g);
                    }
                    PreparedStatement p = ps(SQL_WRITE, psWrite);
                    for (int q = 0; q < tx.ids.length; q++) {   // original order kept
                        p.setDouble(1, tx.values[q]);
                        p.setInt(2, (int) tx.id);
                        p.setInt(3, tx.ids[q]);
                        if (batchWrites) p.addBatch(); else p.executeUpdate();
                    }
                    if (batchWrites) p.executeBatch();          // same order, one round trip
                    release(p);
                    con.commit();
                    return "Tx " + tx.id + " WRITE " + tx.ids[0] + "," + tx.ids[1] + "," + tx.ids[2]
                           + (escalated ? " (escalated)" : "");
                }
                default:
                    throw new IllegalStateException();
            }
        }
    }

    // ------------------------------------------------------------------- setup

    /** Recreates the table (removing tuples inserted by earlier runs). Not timed. */
    void setupDatabase() throws SQLException {
        try (Connection con = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL);
             Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM blocking_data WHERE data_id >= " + NUM_TUPLE)) {
                if (rs.next()) log("Removing " + rs.getLong(1) + " tuples inserted by a previous run");
            } catch (SQLException noTable) { /* first run */ }
            st.execute("DROP TABLE IF EXISTS blocking_data");
            st.execute("CREATE TABLE blocking_data (data_id INTEGER PRIMARY KEY, "
                     + "data_value FLOAT, modified_by INTEGER)");
            List<String> opts = new ArrayList<>();
            if (fillfactor < 100) opts.add("fillfactor = " + fillfactor);
            if (avTune) {
                opts.add("autovacuum_vacuum_scale_factor = 0");
                opts.add("autovacuum_vacuum_threshold = 2000");
                opts.add("autovacuum_vacuum_insert_scale_factor = 0");
                opts.add("autovacuum_vacuum_insert_threshold = 2000");
                opts.add("autovacuum_vacuum_cost_delay = 0");
            }
            if (!opts.isEmpty()) st.execute("ALTER TABLE blocking_data SET (" + String.join(", ", opts) + ")");

            con.setAutoCommit(false);
            Random r = new Random(123456);
            try (PreparedStatement ps = con.prepareStatement(SQL_INSERT)) {
                for (int i = 0; i < NUM_TUPLE; i++) {
                    ps.setInt(1, i);
                    ps.setDouble(2, r.nextDouble() * MAX_VALUE);
                    ps.setInt(3, 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            con.commit();
            con.setAutoCommit(true);
            st.execute("VACUUM ANALYZE blocking_data");
            log("Loaded " + NUM_TUPLE + " tuples" + (opts.isEmpty() ? "" : " with " + opts));
        }
    }

    String tableStats() {
        try (Connection con = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL);
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT pg_relation_size('blocking_data') / 8192, n_live_tup, n_dead_tup, "
               + "n_tup_upd, n_tup_hot_upd, autovacuum_count "
               + "FROM pg_stat_user_tables WHERE relname = 'blocking_data'")) {
            rs.next();
            return String.format("pages=%d live=%d dead=%d upd=%d hot_upd=%d autovacuums=%d",
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6));
        } catch (SQLException e) {
            return "n/a (" + e.getMessage() + ")";
        }
    }

    // -------------------------------------------------------------------- run

    static void log(String s) { System.err.println(s); }

    long totalCommitted() {
        long s = 0;
        for (LongAdder a : committed) s += a.sum();
        return s;
    }

    long[] snapshot() {
        long[] s = new long[TYPES.length * 2];
        for (int i = 0; i < TYPES.length; i++) {
            s[i] = committed[i].sum();
            s[TYPES.length + i] = latencyNs[i].sum();
        }
        return s;
    }

    void run() throws Exception {
        if (setup) setupDatabase();
        if (shareScans) scanSharer = new ScanSharer(scanLanes);

        // Build the source of transactions.
        Source source;
        if (count > 0) {
            Random r = new Random(seed);
            Tx[] stream = new Tx[count];
            for (int i = 0; i < count; i++) stream[i] = generate(r);   // the "given stream"
            AtomicInteger idx = new AtomicInteger();
            source = wr -> {
                int i = idx.getAndIncrement();
                return i < stream.length ? stream[i] : null;
            };
        } else {
            source = wr -> running.get() ? generate(wr) : null;
        }

        List<Worker> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            Worker wk = new Worker(w, source);
            wk.open();
            wk.prepareAll();
            workers.add(wk);
        }
        log("Config: " + cfg);
        log("Before: " + tableStats());

        List<Thread> ts = new ArrayList<>();
        for (Worker wk : workers) ts.add(new Thread(wk, "worker-" + wk.wid));

        long t0 = System.nanoTime();
        for (Thread t : ts) t.start();

        // Progress / equilibrium monitor: once per second, on stderr.
        long[] atWarmup = null;
        long tWarm = t0;
        long lastCommitted = 0;
        long deadline = count > 0 ? Long.MAX_VALUE : t0 + (long) (duration * 1e9);
        int sec = 0;
        while (true) {
            long now = System.nanoTime();
            long nextTick = t0 + (sec + 1) * 1_000_000_000L;
            long until = Math.min(nextTick, deadline);
            boolean alive = false;
            for (Thread t : ts) {
                long waitMs = Math.max(0, (until - System.nanoTime()) / 1_000_000);
                if (waitMs > 0) t.join(waitMs);
                alive |= t.isAlive();
            }
            now = System.nanoTime();
            if (now >= nextTick || !alive) {
                sec++;
                long c = totalCommitted();
                long inflight = started.sum() - c;
                log(String.format("[%3ds] committed %7d/s  total %8d  in-flight %3d  deadlocks %d  escalations %d",
                    sec, c - lastCommitted, c, inflight, deadlocks.sum(), escalations.sum()));
                lastCommitted = c;
                if (atWarmup == null && count == 0 && sec >= warmup) { atWarmup = snapshot(); tWarm = now; }
            }
            if (!alive || now >= deadline) break;
        }
        long t1 = System.nanoTime();
        long[] atEnd = snapshot();
        running.set(false);
        for (Thread t : ts) t.join();
        for (Worker wk : workers) wk.con.close();
        BufferedSink.STDOUT.flush();

        if (count > 0 || atWarmup == null) { atWarmup = new long[atEnd.length]; tWarm = t0; }
        double secs = (t1 - tWarm) / 1e9;
        long total = 0;
        double[] meanMs = new double[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) {
            long n = atEnd[i] - atWarmup[i];
            long ns = atEnd[TYPES.length + i] - atWarmup[TYPES.length + i];
            total += n;
            meanMs[i] = n == 0 ? 0 : ns / 1e6 / n;
        }
        double tps = total / secs;
        log("==================================================================");
        log(String.format("RESULT %s: %d committed in %.2f s  ->  %.0f tx/s  (threads=%d)",
            label, total, secs, tps, threads));
        for (int i = 0; i < TYPES.length; i++) {
            long n = atEnd[i] - atWarmup[i];
            log(String.format("  %-6s %8d  (%4.1f%%)  mean latency %.3f ms",
                TYPES[i], n, 100.0 * n / Math.max(1, total), meanMs[i]));
        }
        log(String.format("  deadlocks retried %d, other retries %d, escalations %d, worst attempts %d",
            deadlocks.sum(), otherRetries.sum(), escalations.sum(), maxAttempts.get()));
        if (shareScans) log(String.format("  shared scans: %d SCAN transactions served by %d sum() executions",
            committed[Type.SCAN.ordinal()].sum(), scanExecutions.sum()));
        if (count > 0) log("  all " + count + " transactions committed: " + (totalCommitted() == count));
        log("After:  " + tableStats());

        if (csv != null) {
            java.io.File f = new java.io.File(csv);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            boolean header = !f.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                if (header) pw.println("label,threads,mode,seconds,committed,tps,read,insert,scan,write,"
                    + "lat_read_ms,lat_insert_ms,lat_scan_ms,lat_write_ms,deadlocks,escalations,max_attempts,scan_exec,config");
                pw.printf(java.util.Locale.ROOT, "%s,%d,%s,%.3f,%d,%.1f,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,\"%s\"%n",
                    label, threads, count > 0 ? "count" : "duration", secs, total, tps,
                    atEnd[0] - atWarmup[0], atEnd[1] - atWarmup[1], atEnd[2] - atWarmup[2], atEnd[3] - atWarmup[3],
                    meanMs[0], meanMs[1], meanMs[2], meanMs[3],
                    deadlocks.sum(), escalations.sum(), maxAttempts.get(), scanExecutions.sum(), cfg.toString().replace("\"", "'"));
            }
        }
    }

    // ------------------------------------------------------------------ config

    void configure(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (String s : args) {
            int i = s.indexOf('=');
            if (i < 0) throw new IllegalArgumentException("expected key=value, got " + s);
            a.put(s.substring(0, i), s.substring(i + 1));
        }
        boolean opt = "optimized".equals(a.getOrDefault("preset", "baseline"));
        cfg.put("preset", opt ? "optimized" : "baseline");
        threads = Integer.parseInt(get(a, "threads", "8"));
        count = Integer.parseInt(get(a, "count", "0"));
        duration = Double.parseDouble(get(a, "duration", "30"));
        warmup = Integer.parseInt(get(a, "warmup", count > 0 ? "0" : "2"));
        seed = Long.parseLong(get(a, "seed", "42"));
        out = get(a, "out", opt ? "buffered" : "sync");
        stmtCache = Boolean.parseBoolean(get(a, "stmtCache", String.valueOf(opt)));
        batchWrites = Boolean.parseBoolean(get(a, "batchWrites", String.valueOf(opt)));
        autocommitSingle = Boolean.parseBoolean(get(a, "autocommitSingle", String.valueOf(opt)));
        pipeline = Boolean.parseBoolean(get(a, "pipeline", String.valueOf(opt)));
        if (pipeline) { stmtCache = true; cfg.put("stmtCache", "true"); }
        shareScans = Boolean.parseBoolean(get(a, "shareScans", "false"));
        scanLanes = Integer.parseInt(get(a, "scanLanes", "2"));
        syncCommit = get(a, "syncCommit", opt ? "off" : "on");
        fillfactor = Integer.parseInt(get(a, "fillfactor", "100"));
        avTune = Boolean.parseBoolean(get(a, "avTune", "false"));
        gate = Boolean.parseBoolean(get(a, "gate", "true"));
        maxOptimistic = Integer.parseInt(get(a, "maxOptimistic", "5"));
        setup = Boolean.parseBoolean(get(a, "setup", "true"));
        print = Boolean.parseBoolean(get(a, "print", "true"));
        label = get(a, "label", cfg.get("preset") + "-t" + threads);
        csv = a.get("csv");
        for (String k : a.keySet())
            if (!cfg.containsKey(k) && !k.equals("csv") && !k.equals("preset"))
                throw new IllegalArgumentException("unknown option " + k);
    }

    String get(Map<String, String> a, String key, String def) {
        String v = a.getOrDefault(key, def);
        cfg.put(key, v);
        return v;
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("FATAL in " + t.getName() + ": " + e);
            e.printStackTrace();
            Runtime.getRuntime().halt(1);           // never report numbers of a broken run
        });
        Benchmark b = new Benchmark();
        b.configure(args);
        b.run();
        System.exit(0);
    }
}
