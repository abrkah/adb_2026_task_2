
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * ConnectionFactory.java
 *
 * Opens JDBC connections to the local PostgreSQL database.
 *
 * Database:
 *   deadlock_demo
 *
 * Host:
 *   localhost
 *
 * Port:
 *   5432
 *
 * User:
 *   ab
 *
 * This example assumes that this PostgreSQL role accepts an empty password.
 */
public class ConnectionFactory {

    // Constant identifying the PostgreSQL database type.
    public static final int POSTGRESQL = 1;

    /**
     * Creates and returns a PostgreSQL JDBC connection.
     *
     * @param database database type identifier
     * @return an open JDBC connection
     * @throws SQLException if the connection cannot be established
     */
    public static Connection getDefaultParameterConnection(int database)
            throws SQLException {

        // JDBC URL for your local PostgreSQL server and assignment database.
        String url =
            "jdbc:postgresql://localhost:5432/deadlock_demo";

        // Connect using your local PostgreSQL role.
        // If authentication fails, update the credentials appropriately.
        return DriverManager.getConnection(url, "ab", "");
    }
}
