package org.xmlvalidator.db;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles database connections and query execution for XML validation
 * This class manages the connection to the Oracle database and provides methods to validate XML values against
 * database views
 */
public class DatabaseConnector implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(DatabaseConnector.class.getName());
    private final Connection connection;
    private final String documentKeyColumn;
    private final Map<String, Map<String, String>> viewData; // Cache for current document
    private String currentDocumentKey;

    /**
     * Creates a new DatabaseConnector and establishes database connection.
     *
     * @param propertiesFile path to the properties file containing database configuration
     * @throws SQLException if connection cannot be established
     * @throws IOException if properties file cannot be read
     */
    public DatabaseConnector(String propertiesFile) throws SQLException, IOException {
        try {
            Properties props = this.loadProperties(propertiesFile);
            this.connection = this.createConnection(props);
            this.documentKeyColumn = props.getProperty("document.key.column");
            this.connection.setAutoCommit(true);
            this.viewData = new HashMap<>();
            logger.info("Database connection established successfully.");
        } catch (SQLException e) {
            throw new SQLException("Failed to establish database connection.", e);
        }
    }

    /**
     * Stores validation data
     * @param matches validation result
     * @param dbValue value queried from the database
     */
    public record ValidationData(boolean matches, String dbValue) {}

    /**
     * Checks if a value exists in specific column of a database view.
     *
     * @param viewName name of the database view
     * @param columnName name of the column to check
     * @throws SQLException if the query fails
     */
    public ValidationData valueExistsInView(String viewName, String columnName, String value, String documentKey)
            throws SQLException {
        String cacheKey = viewName + ":" + documentKey;

        if (!viewData.containsKey(cacheKey)) {
            loadViewData(viewName, documentKey);
        }

        Map<String, String> data = viewData.get(cacheKey);
        String dbValue = (data != null) ? data.get(columnName) : null;

        boolean matches = isMatches(value, dbValue);

        return new ValidationData(matches, dbValue);
    }

    /**
     * Executes special validation query specified in properties file
     * @param query query in properties file
     * @param documentKey document identifier
     * @param value value to validate
     * @throws SQLException if the query fails
     */
    @SuppressWarnings("SqlSourceToSinkFlow")
    public ValidationData executeSqlValidation(String query, String documentKey, String value) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            if (isNumeric(value)) {
                stmt.setBigDecimal(1, new BigDecimal(value));
            } else {
                stmt.setString(1, value);
            }
            stmt.setString(2, documentKey);

            logger.info(String.format("Executing query: %s", query));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // Get the actual sum from database
                    String dbValue = rs.getString(1);

                    // Compare values numerically
                    double xmlValue = Double.parseDouble(value);
                    double dbSum = Double.parseDouble(dbValue);

                    return new ValidationData(
                            Math.abs(xmlValue - dbSum) < 0.001, // Use small epsilon for floating point comparison
                            dbValue
                    );
                }
                return new ValidationData(false, null);
            }

        }
    }

    /**
     * Validates that the document key exists in the specified view.
     * in the main header view before proceeding with other validations.
     *
     * @param viewName the name of the view
     * @param documentKey the invoice number to validate
     * @return true if the document key exists, false otherwise
     * @throws SQLException if there's a database error
     */
    @SuppressWarnings("SqlSourceToSinkFlow")
    public boolean validateDocumentKey(String viewName, String documentKey) throws SQLException {
        String query = String.format(
                "SELECT 1 FROM %s WHERE INV_NO = ? AND ROWNUM = 1",
                viewName
        );

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, documentKey);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to validate document key.", e);
            throw new SQLException("Failed to validate document key.", e);
        }
    }

    /**
     * Checks if a value exists in the specified column for a given document key.
     * Unlike valueExistsInView, this method only checks for existence and not value matching.
     *
     * @param viewName name of the database view
     * @param columnName name of the column to check
     * @param documentKey document identifier
     * @return true if a non-empty value exists, false otherwise
     * @throws SQLException if query execution fails
     */
    @SuppressWarnings("SqlSourceToSinkFlow")
    public boolean checkValueExists(String viewName, String columnName, String documentKey)
            throws SQLException {
        String query = String.format(
                "SELECT \"%s\" FROM %s WHERE %s = ?",
                columnName, viewName, documentKeyColumn
        );

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, documentKey);
            logger.info("Existence check query: " + query);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    // Return true if value exists and is not empty
                    return value != null && !value.trim().isEmpty();
                }
            }
        }
        return false;
    }

    /**
     * Loads and cache all columns from a specific view for a given document key.
     * This method optimizes database access by fetching all column values at once and storing them in memory
     * for subsequent validations.
     * Automatically clears previous document's cache when processing a new document.
     *
     * @param viewName name of the database view to load
     * @param documentKey document identifier
     * @throws SQLException if database query fails
     */
    private void loadViewData(String viewName, String documentKey) throws SQLException {
        // Clear cache if starting a new document
        if (currentDocumentKey == null || !currentDocumentKey.equals(documentKey)) {
            viewData.clear();
            currentDocumentKey = documentKey;
            logger.info("Cleared cached for previous document. Starting new document: " + documentKey);
        }

        String cacheKey = viewName + ":" + documentKey;
        String query = String.format("SELECT * FROM %s WHERE %s = ?", viewName, documentKeyColumn);

        try (PreparedStatement stmt = this.connection.prepareStatement(query)) {
            stmt.setString(1, documentKey);
            logger.info(String.format("Loading data from view %s for document %s", viewName, documentKey));

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int columnCount = rsmd.getColumnCount();

                Map<String, String> rowData = new HashMap<>();

                // Store column names and their values
                if (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = rsmd.getColumnName(i);
                        // Properly handle NULL values from database
                        String value = rs.getString(i);
                        rowData.put(columnName, value); // If null, store null as null

                        // Debugging
                        logger.fine(String.format("Column %s = %s", columnName, value));
                    }
                }

                viewData.put(cacheKey, rowData);
                logger.info(String.format("Loaded %d columns from %s", columnCount, viewName));
            }
        }
    }

    /**
     * Loads properties file
     * @param file path to properties file
     * @return properties
     * @throws IOException if error in reading properties file occurs
     */
    private Properties loadProperties(String file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        return props;
    }

    /**
     * Creates database connection using provided properties.
     *
     * @param props Properties containing database connection settings
     * @return active database Connection
     * @throws SQLException if connection cannot be established
     */
    private Connection createConnection(Properties props) throws  SQLException {
        String url = props.getProperty("db.url");
        String username = props.getProperty("db.username");
        String password = props.getProperty("db.password");

        logger.info("Connecting to database: " + url);
        return DriverManager.getConnection(url, username, password);
    }


    /**
     * Compares two values for equality, handling both string and numeric comparisons.
     * For numeric values, performs precise decimal comparison.
     * For string values, performs exact match comparison.
     *
     * @param value first value to compare
     * @param dbValue second value to compare
     * @return true if values match, false otherwise
     */
    private boolean isMatches(String value, String dbValue) {
        boolean matches = false;
        if (value != null && dbValue != null) {
            if (isNumeric(value)) {
                try {
                    BigDecimal valNum = new BigDecimal(value);
                    BigDecimal dbNum = new BigDecimal(dbValue);
                    matches = valNum.compareTo(dbNum) == 0;
                } catch (NumberFormatException e) {
                    matches = value.equals(dbValue);
                }
            } else {
                matches = value.equals(dbValue);
            }
        }
        return matches;
    }

    /**
     * Checks if a string can be parsed as a valid number.
     * Used to determine whether to perform numeric or string comparison.
     *
     * @param value string to check
     * @return true if the string represents a valid number, false otherwise
     */
    private boolean isNumeric(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Closes the database connection and releases resources.
     * This method should be called when the connector is no longer needed.
     *
     * @throws Exception if an error occurs while closing the connection
     */
    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
