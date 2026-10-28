import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * NoLiveLocks.java
 *
 * Fair-lock strategy for the PostgreSQL deadlock assignment.
 *
 * Configuration:
 * - 30 tasks
 * - 3 UPDATE statements per task
 * - 50,000 database rows
 * - 4 worker threads
 * - READ_COMMITTED isolation
 *
 * FAIR_LOCK allows only one task at a time to execute its transaction.
 * This prevents circular waits between tasks inside this Java process.
 *
 * Trade-off: database transactions are serialized, so concurrency is reduced.
 * The lock does not coordinate with other applications or database clients.
 */
public class NoLiveLocks {

    public static int num_taks = 30;
    public static int num_queries_per_task = 3;

    Random rand = new Random(123456);
    int num_tuple = 50000;
    double max_value = 50.0;

    AtomicInteger tasks_finished;

    // true requests fair, approximately first-come-first-served lock acquisition.
    private static final ReentrantLock FAIR_LOCK = new ReentrantLock(true);

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
            (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

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
            program.tasks_finished.get() + " of " + num_taks
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
            // Fair lock serializes each task's entire transaction.
            FAIR_LOCK.lock();
            try {
                executeTaskWithLock();
            } finally {
                FAIR_LOCK.unlock();
            }
        }

        private void executeTaskWithLock() {
            try (Connection con =
                     ConnectionFactory.getDefaultParameterConnection(
                         ConnectionFactory.POSTGRESQL)) {

                con.setAutoCommit(false);
                con.setTransactionIsolation(
                    Connection.TRANSACTION_READ_COMMITTED
                );

                String sql =
                    "UPDATE blocking_data " +
                    "SET data_value = ?, modified_by = " + task +
                    " WHERE data_value >= ? AND data_value <= ?;";

                try {
                    for (int q = 0; q < num_queries_per_task; q++) {
                        try (PreparedStatement ps =
                                 con.prepareStatement(
                                     sql,
                                     ResultSet.TYPE_SCROLL_SENSITIVE,
                                     ResultSet.CONCUR_UPDATABLE)) {
                            ps.setDouble(1, update_values[q]);
                            ps.setDouble(2, start_range[q]);
                            ps.setDouble(3, stop_range[q]);
                            ps.executeUpdate();
                        }
                    }

                    con.commit();

                    int finished = tasks_finished.incrementAndGet();
                    System.out.println(
                        "Done task " + task +
                        ". I am finisher number " + finished
                    );

                } catch (SQLException e) {
                    try {
                        con.rollback();
                    } catch (SQLException rollbackError) {
                        System.err.println(
                            "Rollback failed for task " + task +
                            ": " + rollbackError.getMessage()
                        );
                    }

                    System.err.println(
                        "Task " + task + " failed (SQLSTATE " +
                        e.getSQLState() + "): " + e.getMessage()
                    );
                }

            } catch (SQLException e) {
                System.err.println(
                    "Could not run task " + task + ": " + e.getMessage()
                );
            }
        }
    }
}
