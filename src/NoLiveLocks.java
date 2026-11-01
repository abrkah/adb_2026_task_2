import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * NoLiveLocks.java
 *
 * Deadlock- and starvation-free execution of the Blocker tasks.
 *
 * Concept (three layers):
 * 1. Ordered locking: before its UPDATEs, a transaction locks all rows it
 *    will touch with one "SELECT ... ORDER BY data_id FOR UPDATE". Everybody
 *    acquires row locks in the same global order, so cycles (deadlocks)
 *    almost never form, while tasks still run concurrently.
 * 2. Restart: the UPDATE predicates are re-evaluated under READ COMMITTED,
 *    so rare deadlocks (SQLSTATE 40P01) remain possible. These are retried.
 * 3. Starvation guard: after MAX_OPTIMISTIC_ATTEMPTS aborts, a task runs
 *    exclusively (write side of a FAIR read/write lock; all other tasks hold
 *    the read side). No concurrent transaction exists then, so it cannot be
 *    a deadlock victim and is guaranteed to commit. Fairness of the lock
 *    prevents the exclusive waiter itself from starving.
 */
public class NoLiveLocks {

    public static int num_taks = 30;
    public static int num_queries_per_task = 3;

    Random rand = new Random(123456);
    int num_tuple = 50000;
    double max_value = 50.0;

    AtomicInteger tasks_finished;

    public static int num_threads = 4;

    // Aborts tolerated before a task escalates to exclusive execution.
    private static final int MAX_OPTIMISTIC_ATTEMPTS = 5;

    // Fair: waiting writers (escalated tasks) are not overtaken forever.
    private static final ReentrantReadWriteLock GATE =
        new ReentrantReadWriteLock(true);

    final AtomicInteger deadlocks = new AtomicInteger(0);
    final AtomicInteger escalations = new AtomicInteger(0);

    public NoLiveLocks() {
        tasks_finished = new AtomicInteger(0);
    }

    void executeSQL(String sql, Connection con) {
        try (java.sql.Statement stmt = con.createStatement()) {
            stmt.execute(sql);
            System.out.println("Executing " + sql);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Recreates blocking_data and inserts the initial rows.
     * WARNING: this drops the existing blocking_data table.
     */
    void cerateDB() {
        try (Connection con =
                 ConnectionFactory.getDefaultParameterConnection(
                     ConnectionFactory.POSTGRESQL)) {

            executeSQL("DROP TABLE IF EXISTS blocking_data;", con);

            executeSQL(
                "CREATE TABLE blocking_data (" +
                "data_id INTEGER PRIMARY KEY, " +
                "data_value FLOAT, " +
                "modified_by INTEGER);",
                con
            );

            con.setAutoCommit(false);

            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blocking_data " +
                    "(data_id, data_value, modified_by) VALUES (?, ?, ?)")) {

                for (int i = 0; i < num_tuple; i++) {
                    ps.setInt(1, i);
                    ps.setDouble(2, rand.nextDouble() * max_value);
                    ps.setInt(3, 0);
                    ps.addBatch();
                }

                int[] results = ps.executeBatch();
                con.commit();
                System.out.println("Inserted rows: " + results.length);
            }

        } catch (SQLException e) {
            System.err.println("Database setup failed.");
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        NoLiveLocks program = new NoLiveLocks();
        program.cerateDB();

        ArrayList<Blocker> tasks = new ArrayList<>(num_taks);
        for (int task = 0; task < num_taks; task++) {
            tasks.add(program.new Blocker(task));
        }

        ThreadPoolExecutor executor =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(num_threads);

        long start = System.currentTimeMillis();

        for (Blocker task : tasks) {
            executor.execute(task);
        }

        executor.shutdown();

        while (!executor.isTerminated()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                return;
            }
        }

        long stop = System.currentTimeMillis();

        System.out.println(
            "[DONE] Task execution after roughly " +
            (stop - start) + " ms finished: " +
            program.tasks_finished.get() + " of " + num_taks +
            " (deadlocks retried: " + program.deadlocks.get() +
            ", escalated to exclusive: " + program.escalations.get() + ")"
        );
    }

    public class Blocker implements Runnable {
        final int task;
        final double[] start_range;
        final double[] stop_range;
        final double[] update_values;

        public Blocker(int id) {
            this.task = id;
            this.start_range = new double[num_queries_per_task];
            this.stop_range = new double[num_queries_per_task];
            this.update_values = new double[num_queries_per_task];

            for (int q = 0; q < num_queries_per_task; q++) {
                stop_range[q] = rand.nextDouble() * max_value;
                start_range[q] = rand.nextDouble() * stop_range[q];
                update_values[q] = rand.nextDouble() * max_value;
            }
        }

        @Override
        public void run() {
            try (Connection con =
                     ConnectionFactory.getDefaultParameterConnection(
                         ConnectionFactory.POSTGRESQL)) {

                con.setAutoCommit(false);
                con.setTransactionIsolation(
                    Connection.TRANSACTION_READ_COMMITTED
                );

                for (int attempt = 1; ; attempt++) {
                    boolean exclusive = attempt > MAX_OPTIMISTIC_ATTEMPTS;
                    Lock gate = exclusive
                        ? GATE.writeLock() : GATE.readLock();
                    if (exclusive && attempt == MAX_OPTIMISTIC_ATTEMPTS + 1) {
                        escalations.incrementAndGet();
                    }

                    gate.lock();
                    try {
                        runTransaction(con);
                        int finished = tasks_finished.incrementAndGet();
                        System.out.println(
                            "Done task " + task + " after " + attempt +
                            " attempt(s). I am finisher number " + finished
                        );
                        return;
                    } catch (SQLException e) {
                        try {
                            con.rollback();
                        } catch (SQLException rollbackError) {
                            System.err.println(
                                "Rollback failed for task " + task +
                                ": " + rollbackError.getMessage()
                            );
                            return;
                        }
                        // 40P01 = deadlock detected; 40001 = serialization.
                        if (!"40P01".equals(e.getSQLState())
                                && !"40001".equals(e.getSQLState())) {
                            System.err.println(
                                "Task " + task + " failed (SQLSTATE " +
                                e.getSQLState() + "): " + e.getMessage()
                            );
                            return;
                        }
                        deadlocks.incrementAndGet();
                    } finally {
                        gate.unlock();
                    }

                    // Backoff outside the gate so others can proceed.
                    try {
                        Thread.sleep(10L * Math.min(attempt, 20)
                            + new Random().nextInt(31));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

            } catch (SQLException e) {
                System.err.println(
                    "Could not run task " + task + ": " + e.getMessage()
                );
            }
        }

        /** One transaction attempt: ordered row locks, updates, commit. */
        private void runTransaction(Connection con) throws SQLException {
            // 1. Lock every row of all ranges in ascending primary-key order.
            StringBuilder lockSql = new StringBuilder(
                "SELECT data_id FROM blocking_data WHERE ");
            for (int q = 0; q < num_queries_per_task; q++) {
                if (q > 0) lockSql.append(" OR ");
                lockSql.append("(data_value >= ? AND data_value <= ?)");
            }
            lockSql.append(" ORDER BY data_id FOR UPDATE");

            try (PreparedStatement ps = con.prepareStatement(lockSql.toString())) {
                for (int q = 0; q < num_queries_per_task; q++) {
                    ps.setDouble(2 * q + 1, start_range[q]);
                    ps.setDouble(2 * q + 2, stop_range[q]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* drain: locks are taken */ }
                }
            }

            // 2. The original updates, now on already locked rows.
            String sql =
                "UPDATE blocking_data " +
                "SET data_value = ?, modified_by = " + task +
                " WHERE data_value >= ? AND data_value <= ?;";
            for (int q = 0; q < num_queries_per_task; q++) {
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setDouble(1, update_values[q]);
                    ps.setDouble(2, start_range[q]);
                    ps.setDouble(3, stop_range[q]);
                    ps.executeUpdate();
                }
            }

            con.commit();
        }
    }
}
