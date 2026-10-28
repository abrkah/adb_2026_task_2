
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
 * Blocking.java
 *
 * Purpose:
 * Demonstrate concurrent database updates and possible deadlocks
 * using PostgreSQL transactions.
 *
 * Configuration:
 * - 30 tasks
 * - 3 UPDATE statements per task
 * - 50,000 database rows
 * - 4 concurrent worker threads
 * - READ_COMMITTED transaction isolation
 *
 * Each task uses its own database connection and executes all its
 * UPDATE statements within one transaction.
 */
public class Blocking {

    // Number of independent tasks submitted to the thread pool.
    public static int num_taks = 30;

    // Number of UPDATE statements executed by each task.
    public static int num_queries_per_task = 3;

    // Random generator used to create initial values and update ranges.
    Random rand = new Random(123456);

    // Number of rows inserted into the database table.
    int num_tuple = 50000;

    // Maximum possible value stored in data_value.
    double max_value = 50.0;

    // Thread-safe counter of tasks that committed successfully.
    AtomicInteger tasks_finished;

    /**
     * Constructor.
     * Initializes the counter of successfully completed tasks.
     */
    public Blocking() {
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

        // Create the main Blocking object.
        Blocking b = new Blocking();

        // Initialize the database before starting any worker tasks.
        b.cerateDB();

        // Build the list of 30 tasks.
        ArrayList<Blocker> myTasks = new ArrayList<>(num_taks);

        for (int task = 0; task < num_taks; task++) {
            // Each task gets its own update ranges.
            myTasks.add(b.new Blocker(task));
        }

        // IMPORTANT: The assignment requires exactly four worker threads.
        ThreadPoolExecutor executor =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

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
            b.tasks_finished.get() + " of " + num_taks
        );
    }

    /**
     * Blocker represents one concurrent database transaction.
     *
     * Every Blocker:
     * 1. Has a unique task ID.
     * 2. Generates three update ranges.
     * 3. Opens its own database connection.
     * 4. Executes three UPDATE statements in one transaction.
     * 5. Commits if all statements succeed.
     *
     * If PostgreSQL detects a deadlock, the transaction fails.
     * This baseline version reports the error but does not retry.
     */
    public class Blocker implements Runnable {

        // Unique ID identifying this task.
        final int task;

        // Lower bound for each UPDATE query.
        final double[] start_range;

        // Upper bound for each UPDATE query.
        final double[] stop_range;

        /**
         * Creates a task and generates its three update ranges.
         *
         * @param id task ID
         */
        public Blocker(int id) {
            this.task = id;

            // One lower and upper bound for each query.
            this.start_range = new double[num_queries_per_task];
            this.stop_range = new double[num_queries_per_task];

            for (int q = 0; q < num_queries_per_task; q++) {

                // Generate an upper bound between 0 and max_value.
                stop_range[q] = rand.nextDouble() * max_value;

                // Generate a lower bound between zero and that upper bound.
                start_range[q] =
                    rand.nextDouble() * stop_range[q];
            }
        }

        /**
         * Executes this task in a worker thread.
         */
        @Override
        public void run() {

            // Each task must have a separate connection.
            Connection con = null;

            try {
                // Open the connection inside the try block so SQL exceptions
                // are caught if connecting to PostgreSQL fails.
                con = ConnectionFactory.getDefaultParameterConnection(
                    ConnectionFactory.POSTGRESQL
                );

                // Disable auto-commit: all three updates form one transaction.
                con.setAutoCommit(false);

                // Use the isolation level required by the assignment.
                con.setTransactionIsolation(
                    Connection.TRANSACTION_READ_COMMITTED
                );

                // Update rows whose current data_value falls within a range.
                // The task ID is an integer generated by this program.
                String sql =
                    "UPDATE blocking_data " +
                    "SET data_value = ?, modified_by = " + task +
                    " WHERE data_value >= ? AND data_value <= ?;";

                // Automatically close the prepared statement afterward.
                try (PreparedStatement ps = con.prepareStatement(
                        sql,
                        ResultSet.TYPE_SCROLL_SENSITIVE,
                        ResultSet.CONCUR_UPDATABLE)) {

                    // Execute the three updates in the same transaction.
                    for (int q = 0; q < num_queries_per_task; q++) {

                        // New value assigned to the matching rows.
                        ps.setDouble(
                            1,
                            rand.nextDouble() * max_value
                        );

                        // Lower bound of the current query.
                        ps.setDouble(2, start_range[q]);

                        // Upper bound of the current query.
                        ps.setDouble(3, stop_range[q]);

                        // Execute the UPDATE.
                        ps.executeUpdate();
                    }
                }

                // Commit only after all three updates have succeeded.
                con.commit();

                // Increment the counter only for a successful commit.
                int finished = tasks_finished.incrementAndGet();

                System.out.println(
                    "Done task " + task +
                    ". I am finisher number " + finished
                );

            } catch (SQLException e) {

                // SQLSTATE 40P01 indicates a PostgreSQL deadlock.
                System.err.println(
                    "Task " + task +
                    " failed (SQLSTATE " + e.getSQLState() +
                    "): " + e.getMessage()
                );

                // Roll back the failed transaction if possible.
                if (con != null) {
                    try {
                        con.rollback();
                    } catch (SQLException rollbackError) {
                        System.err.println(
                            "Rollback failed for task " + task +
                            ": " + rollbackError.getMessage()
                        );
                    }
                }

            } finally {

                // Always close the connection to release resources.
                if (con != null) {
                    try {
                        con.close();
                    } catch (SQLException closeError) {
                        System.err.println(
                            "Connection close failed: " +
                            closeError.getMessage()
                        );
                    }
                }
            }
        }
    }
}
