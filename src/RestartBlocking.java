
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RestartBlocking.java
 *
 * Purpose:
 * Demonstrate concurrent database updates and possible deadlocks
 * using PostgreSQL transactions.
 *
 * Configuration:
 * - 30 tasks
 * - 3 UPDATE statements per task
 * - 50,000 database rows
 * - 4 concurrent worker threads (num_threads)
 * - READ_COMMITTED transaction isolation
 *
 * Each task uses its own database connection and executes all its
 * UPDATE statements within one transaction.
 */
public class RestartBlocking {

    // Number of independent tasks submitted to the thread pool.
    public static int num_taks = 30;

    // Number of UPDATE statements executed by each task.
    public static int num_queries_per_task = 3;

    // Number of concurrent worker threads.
    public static int num_threads = 4;

    // Random generator used to create initial values and update ranges.
    Random rand = new Random(123456);

    // Number of rows inserted into the database table.
    int num_tuple = 50000;

    // Maximum possible value stored in data_value.
    double max_value = 50.0;

    // Thread-safe counter of tasks that committed successfully.
    AtomicInteger tasks_finished;

    // Thread-safe counter of deadlock aborts that were rolled back and retried.
    final AtomicInteger deadlocks = new AtomicInteger(0);

    /**
     * Constructor.
     * Initializes the counter of successfully completed tasks.
     */
    public RestartBlocking() {
        tasks_finished = new AtomicInteger(0);
    }

    /**
     * Executes a SQL statement, for example CREATE TABLE or DROP TABLE.
     *
     * @param sql SQL statement to execute
     * @param con database connection to use
     */
    void executeSQL(String sql, Connection con) {
        // try-with-resources automatically closes the SQL statement.
        try (java.sql.Statement stmt = con.createStatement()) {
            stmt.execute(sql);
            System.out.println("Executing " + sql);
        } catch (SQLException e) {
            // Report database setup errors.
            e.printStackTrace();
        }
    }

    /**
     * Creates and initializes the database table.
     *
     * WARNING:
     * This method drops the existing blocking_data table before
     * recreating it. Do not store important data in this table.
     */
    void cerateDB() {

        // Open a database connection and close it automatically afterward.
        try (Connection con =
                 ConnectionFactory.getDefaultParameterConnection(
                     ConnectionFactory.POSTGRESQL)) {

            // Remove the previous table if it exists.
            executeSQL("DROP TABLE IF EXISTS blocking_data;", con);

            // Create a table with a primary key, a value, and a task ID.
            executeSQL(
                "CREATE TABLE blocking_data (" +
                "data_id INTEGER PRIMARY KEY, " +
                "data_value FLOAT, " +
                "modified_by INTEGER);",
                con
            );

            // Disable automatic commits so the inserts form one transaction.
            con.setAutoCommit(false);

            // A prepared statement safely inserts values into the table.
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blocking_data " +
                    "(data_id, data_value, modified_by) VALUES (?, ?, ?)")) {

                // Generate 50,000 rows.
                for (int i = 0; i < num_tuple; i++) {

                    // Unique primary key for this row.
                    ps.setInt(1, i);

                    // Random initial value between 0 and max_value.
                    ps.setDouble(2, rand.nextDouble() * max_value);

                    // Zero means no task has modified this row yet.
                    ps.setInt(3, 0);

                    // Add the insert to the batch.
                    ps.addBatch();
                }

                // Execute all queued inserts.
                int[] results = ps.executeBatch();

                // Make the inserted rows permanent.
                con.commit();

                System.out.println(
                    "Inserted rows: " + results.length
                );
            }

        } catch (SQLException e) {
            // Database setup must succeed before the workload can run.
            System.err.println("Database setup failed.");
            e.printStackTrace();

            // Stop instead of running tasks against an uninitialized table.
            throw new RuntimeException(e);
        }
    }

    /**
     * Program entry point.
     * Creates the table, creates tasks, runs them concurrently,
     * and measures workload execution time.
     */
    public static void main(String[] args) {
        // Optional: <tasks> <queriesPerTask> <threads>
        if (args.length > 0) num_taks = Integer.parseInt(args[0]);
        if (args.length > 1) num_queries_per_task = Integer.parseInt(args[1]);
        if (args.length > 2) num_threads = Integer.parseInt(args[2]);

        // Create the main Blocking object.
        RestartBlocking b = new RestartBlocking();

        // Initialize the database before starting any worker tasks.
        b.cerateDB();

        // Build the list of 30 tasks.
        ArrayList<Blocker> myTasks = new ArrayList<>(num_taks);

        for (int task = 0; task < num_taks; task++) {
            // Each task gets its own update ranges.
            myTasks.add(b.new Blocker(task));
        }

        ThreadPoolExecutor executor =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(num_threads);

        // Start timing the task workload, excluding table initialization.
        long start = System.currentTimeMillis();

        // Submit every task to the thread pool.
        for (Blocker task : myTasks) {
            executor.execute(task);
        }

        // No more tasks will be submitted.
        executor.shutdown();

        // Wait until all submitted tasks have finished.
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Preserve the interrupt status if this thread is interrupted.
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                return;
            }
        }

        // Stop timing after all tasks finish.
        long stop = System.currentTimeMillis();

        // Print elapsed time and the number of successful commits.
        System.out.println(
            "[DONE] Task execution after roughly " +
            (stop - start) + " ms finished: " +
            b.tasks_finished.get() + " of " + num_taks +
            " (deadlocks retried: " + b.deadlocks.get() + ")"
        );
    }

    /**
     * Blocker represents one concurrent database transaction.
     *
     * Unlike Blocking.java, this version retries a transaction if PostgreSQL
     * aborts it because of a deadlock (SQLSTATE 40P01).
     */
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

            // Generate the ranges and update values once.
            // Every retry therefore repeats the same logical workload.
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

                String sql =
                    "UPDATE blocking_data " +
                    "SET data_value = ?, modified_by = " + task +
                    " WHERE data_value >= ? AND data_value <= ?;";

                for (int attempt = 1; ; attempt++) {
                    try {
                        // Run every UPDATE in the same transaction attempt.
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

                        // The task is successful only after the commit succeeds.
                        con.commit();

                        int finished = tasks_finished.incrementAndGet();
                        System.out.println(
                            "Done task " + task +
                            " after " + attempt +
                            " attempt(s). I am finisher number " + finished
                        );
                        return;

                    } catch (SQLException e) {
                        // A failed transaction must be rolled back before retry.
                        try {
                            con.rollback();
                        } catch (SQLException rollbackError) {
                            System.err.println(
                                "Rollback failed for task " + task +
                                ": " + rollbackError.getMessage()
                            );
                            return;
                        }

                        // PostgreSQL SQLSTATE 40P01 means deadlock detected.
                        if (!"40P01".equals(e.getSQLState())) {
                            System.err.println(
                                "Task " + task +
                                " failed (SQLSTATE " + e.getSQLState() +
                                "): " + e.getMessage()
                            );
                            return;
                        }

                        deadlocks.incrementAndGet();
                        System.out.println(
                            "Deadlock for task " + task +
                            " on attempt " + attempt +
                            "; rolled back, will retry."
                        );

                        // Randomized backoff helps reduce repeated collisions.
                        try {
                            Thread.sleep(
                                20L * Math.min(attempt, 50) +
                                new Random().nextInt(51)
                            );
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }

            } catch (SQLException e) {
                System.err.println(
                    "Could not run task " + task + ": " + e.getMessage()
                );
            }
        }
    }
}
