package org.xmlvalidator.config;

import javax.naming.ConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages XML Validation configuration loaded from a properties file.
 * This class handles the parsing and access of validation rules, database setting, XML namespaces, and SQL validations
 * defined in the properties file
 *
 */
public class ValidationConfig {
    private final Properties properties;                    // Raw properties from file
    private final Map<String, ValidationMapping> mappings;  // XML-to-DB mappings
    private final Map<String, String> namespaces;           // XML namespace definitions
    private final Map<String, SqlMapping> sqlValidation;    // Custom SQL validations
    private final DocumentKeyConfig documentKey;            // Document key configuration

    /**
     * Constructs a new ValidationConfig by loading and parsing the specified properties file
     *
     * @param propertiesFile path to the properties file containing validation configuration
     * @throws IOException if there is an error reading the properties file
     * @throws ConfigurationException if the configuration is invalid or incomplete
     */
    public ValidationConfig(String propertiesFile) throws IOException, ConfigurationException {
        this.properties     = this.loadProperties(propertiesFile);
        this.namespaces     = this.parseNamespaces();
        this.sqlValidation  = this.parseSqlValidations();
        this.documentKey    = this.parseDocumentKey();
        this.mappings       = this.parseMappings();
    }

    /**
     * Loads properties from the specified file.
     *
     * @param file path to the properties file.
     * @return Properties object containing the file contents
     * @throws IOException if the file cannot be read
     */
    private Properties loadProperties(String file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        return props;
    }

    /**
     * Parses document key configuration.
     * Document key is used to link XML document with database records
     * @return DocumentKeyConfig containing the parsed configuration
     * @throws ConfigurationException if there is an error in the configuration of document key.
     */
    private DocumentKeyConfig parseDocumentKey() throws ConfigurationException {
        String xpath = properties.getProperty("document.key.xpath");
        String view = properties.getProperty("document.key.view");
        String column = properties.getProperty("document.key.column");

        if (xpath == null || view == null || column == null) {
            throw new ConfigurationException("Document key configuration in incomplete.");
        }

        return new DocumentKeyConfig(xpath, view, column);
    }

    /**
     * Parse XML namespaces definitions.
     * Format: xmlns.prefix=uri
     * @return Map where key is namespace prefix and value is namespace URI
     */
    private Map<String, String> parseNamespaces() {
        return properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("xmlns."))
                .collect(Collectors.toMap(
                        key -> key.substring("xmlns.".length()),
                        properties::getProperty
                ));
    }

    /**
     * Extracts custom SQL validation queries from properties.
     * Process all properties matching pattern "sql.validation.*.query" and their corresponding XPath properties.
     *
     * @return Map where the key is XPath and value is SqlMapping containing query and configuration
     */
    private Map<String, SqlMapping> parseSqlValidations() {
        Map<String, SqlMapping> validations = new HashMap<>();

        properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith("sql.validation.") && key.endsWith(".xpath"))
            .forEach(key -> {
                String name = key.substring("sql.validation.".length(), key.length() - ".xpath".length());
                String xpath = properties.getProperty(key);
                String queryKey = "sql.validation." + name + ".query";
                String query = properties.getProperty(queryKey);

                if (xpath != null && query != null) {
                    validations.put(xpath, new SqlMapping(xpath, query));
                }
            });

        return validations;
    }

    /**
     * Parse validation mappings from properties, excluding document key mappings.
     *
     * @return Map where key is "VIEWNAME.ColumnName" and value is ValidationMapping.
     * @throws ConfigurationException if document key is not initialized
     */
    private Map<String, ValidationMapping> parseMappings() throws ConfigurationException {
        if (documentKey == null) {
            throw new ConfigurationException("Document key must be initialized before parsing mappings");
        }

        Map<String, ValidationMapping> mappings = new HashMap<>();
        String documentKeyColumn = documentKey.column(); // "INV_NO"

        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("view.") && key.endsWith(".xpath"))
                .forEach(key -> {
                    String[] parts = this.splitPropertyKey(key);
                    if(parts.length >= 4) {
                        String viewName = parts[1];
                        String columnName = this.getColumnName(key);
                        String xpath = properties.getProperty(key);

                        // Exclude document key column for all views
                        if (!documentKeyColumn.equals(columnName)) {
                            mappings.put(viewName + "." + columnName,
                                    new ValidationMapping(xpath, viewName, columnName));
                        }
                    }
                });
        return mappings;
    }

    /** Extract column name from property key
     *
     * @param propertyName property attribute in properties file
     * @return property name as String
     */
    private String getColumnName(String propertyName) {
        // Remove the "view.{viewName}." prefix and ".xpath" suffix
        int startIndex = propertyName.indexOf('.', propertyName.indexOf('.') + 1) + 1;
        int endIndex = propertyName.lastIndexOf('.');
        return propertyName.substring(startIndex, endIndex);
    }

    /**
     * Carefully splits property key preserving dots in column names
     * Example: "view.VIEW_NAME.ColumnName.xpath" ->
     *          ["view", "VIEW_NAME", "ColumnName", "xpath"]
     *
     * @param key the property key to split
     * @return array of split parts preserving dots in column names
     */
    private String[] splitPropertyKey(String key) {
        List<String> parts = new ArrayList<>();

        // Split by dot
        String[] rawParts = key.split("\\.");

        // Always add the first part (view)
        parts.add(rawParts[0]);

        // Always add the second part (view name)
        if (rawParts.length > 1) {
            parts.add(rawParts[1]);
        }

        // Handle the column name part (might contain dots)
        if (rawParts.length > 3) {
            StringBuilder columnName = new StringBuilder();
            // Combine all parts between view name and xpath
            for (int i = 2; i < rawParts.length - 1; i++) {
                if (!columnName.isEmpty()) {
                    columnName.append(".");
                }
                columnName.append(rawParts[i]);
            }
            parts.add(columnName.toString());
        }

        // Always add the last part (xpath)
        parts.add(rawParts[rawParts.length - 1]);

        return parts.toArray(new String[0]);
    }

    /**
     * Gets the document key configuration
     *
     * @return DocumentKeyConfig containing document key settings
     */
    public DocumentKeyConfig getDocumentKey() {
        return documentKey;
    }

    /**
     * Gets all validation mappings.
     *
     * @return List of all ValidationMapping objects
     */
    public List<ValidationMapping> getMappings() {
        return new ArrayList<>(mappings.values());
    }

    /**
     * Gets XML namespace definitions.
     *
     * @return Unmodifiable map of XML namespace prefixes to URIs
     */
    public Map<String, String> getNamespaces() {
        return Collections.unmodifiableMap(namespaces);
    }

    /**
     * Gets SQL validation query for a specific XPath.
     *
     * @param xpath the XPath to get SQL validation for
     * @return Optional containing SQL query if found, empty Optional otherwise
     */
    public Optional<String> getSqlValidation(String xpath) {
        SqlMapping mapping = this.sqlValidation.get(xpath);
        return mapping != null ? Optional.of(mapping.query()) : Optional.empty();
    }

    /**
     * Gets all SQL validations.
     *
     * @return Unmodifiable map of XPath to SqlMapping objects
     */
    public Map<String, SqlMapping> getSqlValidations() {
        return Collections.unmodifiableMap(sqlValidation);
    }

    /**
     * Gets a property value by key.
     *
     * @param key the property key to look up
     * @return property value if found, null if not found
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Gets all properties.
     *
     * @return Properties object containing all configuration properties
     */
    public Properties getProperties() {
        return this.properties;
    }
}