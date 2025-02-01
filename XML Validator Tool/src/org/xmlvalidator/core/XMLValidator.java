package org.xmlvalidator.core;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xmlvalidator.config.SqlMapping;
import org.xmlvalidator.config.ValidationConfig;
import org.xmlvalidator.config.ValidationMapping;
import org.xmlvalidator.db.DatabaseConnector;
import org.xmlvalidator.exception.ValidationException;
import org.xmlvalidator.model.ValidationResult;
import org.xmlvalidator.model.ValidationSummary;
import org.xmlvalidator.util.CSVReportWriter;

import javax.naming.ConfigurationException;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Core class for validating XML documents against database views.
 * Handles XML parsing, XPath evaluation, and database validation.
 */
public class XMLValidator implements AutoCloseable {
    private final ValidationConfig config;
    private final DatabaseConnector dbConnector;
    private final XPath xpath;
    private static final Logger logger = Logger.getLogger(XMLValidator.class.getName());

    public XMLValidator(String propertiesFile) throws IOException, SQLException, ConfigurationException {
        this.config         = new ValidationConfig(propertiesFile);     // Load configuration from properties file
        this.dbConnector    = new DatabaseConnector(propertiesFile);    // Initialize database connection
        this.xpath          = this.createXPath();                       // Create XPath processor with name space support
    }

    /**
     * Creates an XPath processor with namespace awareness.
     * Use namespaces defined in the properties file
     * @return xpath
     */
    private XPath createXPath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        Map<String, String> namespaces = config.getNamespaces();

        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return namespaces.getOrDefault(prefix, "");
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return namespaces.entrySet().stream()
                        .filter(e -> e.getValue().equals(namespaceURI))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                return namespaces.entrySet().stream()
                        .filter(e -> e.getValue().equals(namespaceURI))
                        .map(Map.Entry::getKey)
                        .iterator();
            }
        });

        return xpath;
    }

    /**
     * Validates an XML file against database views based on configuration
     *
     * @param xmlFile path to the XML file to validate
     * @return ValidationSummary containing validation results
     */
    public ValidationSummary validate(String xmlFile) {
        try {
            // Parse XML document
            Document doc = this.parseXMLFile(xmlFile);
            List<ValidationResult> results = new ArrayList<>();
            int successful = 0;
            int failed = 0;

            // Extract document key value first
            String documentKey = this.validateDocumentKey(doc);
            Set<String> processedPaths = new HashSet<>(); // Track processed paths

            // Process SQL validations first
            logger.info("Processing SQL validations...");
            for (Map.Entry<String, SqlMapping> entry : config.getSqlValidations().entrySet()) {
                logger.info("Processing SQL validation for XPath: " + entry.getValue().xpath());
                ValidationResult result = this.processSqlValidation(doc, entry.getValue(), documentKey);
                if (result != null) {
                    results.add(result);
                    if (result.match()) {
                        successful++;
                    } else {
                        failed++;
                    }
                    processedPaths.add(entry.getValue().xpath());
                    logger.info("Added to processed paths: " + entry.getValue().xpath());
                }
            }

            // Handle multi-path fields
            logger.info("Processing multi-path validations...");

            // Process priority paths first
            List<String> priorityKeys = config.getProperties().stringPropertyNames().stream()
                    .filter(key -> key.endsWith(".paths") && !key.endsWith(".standalone.paths"))
                    .sorted((key1, key2) -> {
                        String config1 = config.getProperty(key1);
                        String config2 = config.getProperty(key2);
                        int priority1 = Integer.parseInt(config1.split(",")[0]);
                        int priority2 = Integer.parseInt(config2.split(",")[0]);
                        return Integer.compare(priority1, priority2);
                    })
                    .toList();

            Set<String> processedColumns = new HashSet<>();

            // Process priority paths first
            for (String key : priorityKeys) {
                String[] parts = key.split("\\.");
                if (parts.length >= 3) {
                    String viewName = parts[1];
                    String column = parts[2];

                    // Skip if column already processed
                    if (processedColumns.contains(viewName + "." + column)) {
                        continue;
                    }

                    ValidationResult result = validateMultiPathField(doc, viewName, column, documentKey);
                    if (result != null) {
                        results.add(result);
                        if (result.match()) successful++;
                        else failed++;
                        processedColumns.add(viewName + "." + column);
                        String[] xpaths = config.getProperty(key).split(",", 2)[1].split(",");
                        processedPaths.addAll(Arrays.stream(xpaths)
                                .map(String::trim)
                                .collect(Collectors.toSet()));
                    }
                }
            }

            // Process standalone paths
            List<String> standaloneKeys = config.getProperties().stringPropertyNames().stream()
                    .filter(key -> key.endsWith(".standalone.paths"))
                    .toList();

            for (String key : standaloneKeys) {
                String[] parts = key.split("\\.");
                if (parts.length >= 3) {
                    String viewName = parts[1];
                    String column = parts[2];

                    // Skip if this column was already processed in priority group
                    if (processedColumns.contains(viewName + "." + column)) {
                        logger.info("Skipping standalone validation for already processed column: " + column);
                        continue;
                    }

                    ValidationResult result = validateMultiPathField(doc, viewName, column, documentKey);
                    if (result != null) {
                        results.add(result);
                        if (result.match()) successful++;
                        else failed++;
                        String[] xpaths = config.getProperty(key).split(",", 2)[1].split(",");
                        processedPaths.addAll(Arrays.stream(xpaths)
                                .map(String::trim)
                                .collect(Collectors.toSet()));
                    }
                }
            }

            // Process each mapping for standard validation, skipping processed paths
            logger.info("Processing standard validations...");
            for (ValidationMapping mapping : config.getMappings()) {
                logger.info("Checking mapping: " + mapping.xmlPath());
                if (processedPaths.contains(mapping.xmlPath())) {
                    logger.info("Skipping already processed path: " + mapping.xmlPath());
                    continue;
                }

                ValidationResult result = processMapping(doc, mapping, documentKey);
                if (result != null) {
                    results.add(result);
                    if (result.match()) successful++;
                    else failed++;
                }
            }

            return new ValidationSummary(successful, failed, this.writeReport(results));
        } catch (Exception e) {
            logger.severe("Validation failed: " + e.getMessage());
            throw new ValidationException("Validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validates the document key from the XML against the database.
     * The function extracts the document key from XML using configured XPath, verifies the key is not empty,
     * and validates the key exists in the specified database view
     *
     * @param doc The XML document to extract the key from
     * @return The validated document key
     * @throws Exception if an error occurs during XPath evaluation or database access
     */
    private String validateDocumentKey(Document doc) throws Exception {
        String documentKey = extractXmlValue(doc, config.getDocumentKey().xpath());
        if (documentKey == null || documentKey.trim().isEmpty()) {
            throw new ValidationException("Document key not found or empty in XML");
        }

        logger.info("Validating document with key: " + documentKey);

        try {
            boolean documentKeyExists = dbConnector.validateDocumentKey(
                    config.getDocumentKey().view(),
                    documentKey
            );

            if (!documentKeyExists) {
                throw new ValidationException("Document key " + documentKey +
                        " not found in view " + config.getDocumentKey().view());
            }

            return documentKey;

        } catch (SQLException e) {
            throw new ValidationException("Failed to validate document key: " + e.getMessage(), e);
        }
    }

    /**
     * Processes a single validation mapping.
     *
     * @param doc XML file which was parsed into a DOM tree
     * @param documentKey document key modified in properties file
     * @return ValidationResult Java class
     */
    private ValidationResult processMapping(Document doc, ValidationMapping mapping, String documentKey)
            throws Exception {
        try {
            // Check if tag exists first
            NodeList nodes = (NodeList) xpath.evaluate(mapping.xmlPath(), doc, XPathConstants.NODESET);

            // Case 1: Tag doesn't exist in XML - skip validation
            if (nodes.getLength() == 0) {
                logger.fine(String.format("Tag not found in XML: %s - Skipping validation",
                        mapping.xmlPath()));
                return null;
            }

            // Tag exists, get its value
            String xmlValue = nodes.item(0).getTextContent();

            // Case 2: Tag exists but is empty
            if (xmlValue != null && xmlValue.trim().isEmpty()) {
                logger.info(String.format("Empty tag found for xpath %s - Marking as FALSE",
                        mapping.xmlPath()));
                return new ValidationResult(
                        mapping.xmlPath(),
                        mapping.viewName(),
                        mapping.columnName(),
                        false,
                        "empty tag",
                        null
                );
            }

            // Check for SQL validation first
            Optional<String> sqlQuery = config.getSqlValidation(mapping.xmlPath());

            if (sqlQuery.isPresent()) {
                logger.info("Using custom SQL validation: " + sqlQuery.get());
                DatabaseConnector.ValidationData validationData = dbConnector.executeSqlValidation(
                        sqlQuery.get(),
                        documentKey,
                        xmlValue
                );

                return new ValidationResult(
                        mapping.xmlPath(),
                        mapping.viewName(),
                        mapping.columnName(),
                        validationData.matches(),
                        xmlValue,
                        validationData.dbValue()
                );
            }

            // Normal validation for non-empty values
            logger.info("Using standard validation for " + mapping.xmlPath());
            DatabaseConnector.ValidationData validationData = dbConnector.valueExistsInView(
                    mapping.viewName(),
                    mapping.columnName(),
                    xmlValue,
                    documentKey
            );

            logger.info(String.format("Validated %s.%s: %s",
                    mapping.viewName(),
                    mapping.columnName(),
                    validationData.matches() ? "MATCH" : "NO MATCH"));

            return new ValidationResult(
                    mapping.xmlPath(),
                    mapping.viewName(),
                    mapping.columnName(),
                    validationData.matches(),
                    xmlValue,
                    validationData.dbValue()
            );

        } catch (XPathException e) {
            logger.warning(String.format("XPath evaluation failed for %s: %s",
                    mapping.xmlPath(), e.getMessage()));
            return null;
        }
    }

    /**
     * Processes SQL-based validation for a specific XML path. Handles custom SQL validations defined in the
     * configuration.
     * The function extracts the vale from XML using the specified XPath. If the tag exists but empty, returns a failed
     * validation result, and if the tag has a value, executes custom SQL validation
     *
     * @param doc The XML document to validate
     * @param sqlMapping The SQL mapping configuration containing XPath and query
     * @param documentKey The document identifier used in SQL validation
     * @return ValidationResult containing the validation outcome, or null if the tag doesn't exist
     */
    private ValidationResult processSqlValidation(Document doc, SqlMapping sqlMapping, String documentKey) {
        try {
            NodeList nodes = (NodeList) xpath.evaluate(sqlMapping.xpath(), doc, XPathConstants.NODESET);

            if (nodes.getLength() == 0) {
                return null;
            }

            String xmlValue = nodes.item(0).getTextContent();
            if (xmlValue != null && xmlValue.trim().isEmpty()) {
                return new ValidationResult(
                        sqlMapping.xpath(),
                        "SQL_VALIDATION",
                        "SQL_FIELD",
                        false,
                        "empty tag",
                        null
                );
            }

            DatabaseConnector.ValidationData validationData =
                    dbConnector.executeSqlValidation(sqlMapping.query(), documentKey, xmlValue);

            return new ValidationResult(
                    sqlMapping.xpath(),
                    "SQL_VALIDATION",
                    "SQL_FIELD",
                    validationData.matches(),
                    xmlValue,
                    validationData.dbValue()
            );
        } catch (Exception e) {
            logger.severe("Error processing SQL validation: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses XML file into a DOM Document. Configures parser to be namespace aware
     * @param xmlFile path to the XML file
     * @return parsed XML document
     * @throws Exception if the parse fails
     */
    private Document parseXMLFile(String xmlFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new File(xmlFile));
    }

    /**
     * Extracts value from XML using XPath expression.
     * Handles namespace-aware XPath evaluation.
     *
     * @param doc XML file which was parsed into a DOM tree.
     * @param xpathExpression the XPath expression used to locate desired node
     * @return the text content of the first node matching the XPath expression
     *          or {@code null} if no matching node is found
     * @throws Exception if an error occurs during XPath evaluation or processing
     */
    private String extractXmlValue(Document doc, String xpathExpression) throws Exception {
        NodeList nodes = (NodeList) xpath.evaluate(xpathExpression, doc, XPathConstants.NODESET);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    /**
     * Writes report to a CSV file
     * @param results results from the validation
     *
     * @return report file in CSV format
     */
    private String writeReport(List<ValidationResult> results) {
        String reportFile = "validation_results_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";

        try (CSVReportWriter writer = new CSVReportWriter(reportFile)) {
            writer.writeResults(results);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return reportFile;
    }

    /**
     * Validates fields that can be found in multiple paths within the XML document.
     * Handle both priority-based validation and standalone path validation.
     *
     * @param doc The XML document to validate
     * @param viewName The database view name to validate against
     * @param column The column name in the database view
     * @param documentKey The document identifier used for database lookups
     * @return ValidateResult containing the validation outcome, or null if validation was skipped
     * @throws Exception if an error occurs during validation
     */
    private ValidationResult validateMultiPathField(Document doc, String viewName, String column,
                                                    String documentKey) throws Exception {
        // Check priority config
        String priorityConfig = config.getProperty("view." + viewName + "." + column + ".paths");
        String standalonePaths = config.getProperty("view." + viewName + "." + column + ".standalone.paths");

        logger.info("Processing " + column + ": priority config exists: " + (priorityConfig != null) +
                ", standalone config exists: " + (standalonePaths != null));

        // Check if TaxCurrencyCode exists in database
        boolean taxCurrencyExists = dbConnector.checkValueExists(viewName, "TaxCurrencyCode", documentKey);
        logger.info("TaxCurrencyCode exists in Database: " + taxCurrencyExists);

        ValidationResult result;

        // Handle priority paths first
        if (priorityConfig != null) {
            if (column.equals("TaxCurrencyCode")) {
                if (taxCurrencyExists) {
                    // Process TaxCurrencyCode priority paths when it exists in DB
                    result = validatePriorityPaths(doc, viewName, column, documentKey, priorityConfig);
                    if (result != null) {
                        return result;
                    }
                }
            } else if (column.equals("DocumentCurrencyCode")) {
                if (!taxCurrencyExists) {
                    // Process DocumentCurrencyCode priority paths only when TaxCurrencyCode doesn't exist in DB
                    result = validatePriorityPaths(doc, viewName, column, documentKey, priorityConfig);
                    if (result != null) {
                        return result;
                    }
                } else {
                    logger.info("TaxCurrencyCode exists in database, skipping DocumentCurrencyCode priority validation");
                }
            }
        }

        // Always process standalone paths for both columns, regardless of database state
        if (standalonePaths != null) {
            return validateStandalonePaths(doc, viewName, column, documentKey, standalonePaths);
        }

        return null;
    }

    /**
     * Validates XML paths according to their priority configuration.
     * Processes a group of related XPaths and ensures consistent values across them.
     * The priority configuration string format is: "priority,xpath1,xpath2,..."
     * where priority is an integer determining processing order.
     * The method:
     * 1. Collects values from all specified XPaths
     * 2. Verifies consistency across all paths (all values must match)
     * 3. Validates the value against the database
     *
     * @param doc The XML document to validate
     * @param viewName The database view name to validate against
     * @param column The column name in the database view
     * @param documentKey The document identifier used for database lookups
     * @param config The priority configuration string containing priority and XPaths
     * @return ValidationResult containing the validation outcome, or null if no values found
     * @throws Exception if an error occurs during validation
     */
    private ValidationResult validatePriorityPaths(Document doc, String viewName, String column,
                                                   String documentKey, String config) throws Exception {
        String[] parts = config.split(",", 2);
        String[] xpaths = parts[1].split(",");

        // Collect values for this priority group
        Set<String> values = new HashSet<>();
        Map<String, String> pathValues = new HashMap<>();

        for (String path : xpaths) {
            NodeList nodes = (NodeList) xpath.evaluate(path.trim(), doc, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                String value = nodes.item(i).getTextContent().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                    pathValues.put(path, value);
                }
            }
        }

        // No values found in any of the priority paths
        if (values.isEmpty()) {
            logger.info("No values found in priority paths for " + column);
            return null;
        }

        // Check value consistency within the group
        if (values.size() > 1) {
            logger.info("Inconsistent values found in priority paths for " + column + ": " + String.join(", ", values));
            return new ValidationResult(
                    String.join(", ", pathValues.keySet()),
                    viewName,
                    column,
                    false,
                    String.join(", ", values),
                    null
            );
        }

        // Validate single value against database
        String value = values.iterator().next();
        DatabaseConnector.ValidationData validationData =
                dbConnector.valueExistsInView(viewName, column, value, documentKey);

        logger.info("Validated priority paths for " + column + ": " + value +
                " - Match: " + validationData.matches());

        return new ValidationResult(
                String.join(", ", pathValues.keySet()),
                viewName,
                column,
                validationData.matches(),
                value,
                validationData.dbValue()
        );
    }

    /**
     * Validates XML paths that are processed independently of priority rules.
     * These paths are always validated regardless of database state or priority path results.
     * The standalone configuration string format is: "0,xpath1,xpath2,..."
     * where 0 is a placeholder priority (not used) and followed by XPaths.
     * The method:
     * 1. Collects values from all specified XPaths
     * 2. Verifies consistency across all paths (all values must match)
     * 3. Validates the value against the database
     *
     * @param doc The XML document to validate
     * @param viewName The database view name to validate against
     * @param column The column name in the database view
     * @param documentKey The document identifier used for database lookups
     * @param config The standalone configuration string containing XPaths
     * @return ValidationResult containing the validation outcome, or null if no values found
     * @throws Exception if an error occurs during validation
     */
    private ValidationResult validateStandalonePaths(Document doc, String viewName, String column,
                                                     String documentKey, String config) throws Exception {
        String[] parts = config.split(",", 2);
        String[] paths = parts[1].split(",");
        Set<String> values = new HashSet<>();
        Map<String, String> pathValues = new HashMap<>();

        // Collect all values from standalone paths
        for (String path : paths) {
            NodeList nodes = (NodeList) xpath.evaluate(path.trim(), doc, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                String value = nodes.item(i).getTextContent().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                    pathValues.put(path, value);
                }
            }
        }

        if (values.isEmpty()) {
            logger.info("No values found in standalone paths for " + column);
            return null;
        }

        // Check for consistency across all standalone paths
        if (values.size() > 1) {
            logger.info("Inconsistent values found in standalone paths for " + column + ": " + String.join(", ", values));
            return new ValidationResult(
                    String.join(", ", pathValues.keySet()),
                    viewName,
                    column,
                    false,
                    String.join(", ", values),
                    null
            );
        }

        // Single value validation
        String value = values.iterator().next();
        DatabaseConnector.ValidationData validationData =
                dbConnector.valueExistsInView(viewName, column, value, documentKey);

        logger.info("Validated standalone paths for " + column + ": " + value +
                " - Match: " + validationData.matches());

        return new ValidationResult(
                String.join(", ", pathValues.keySet()),
                viewName,
                column,
                validationData.matches(),
                value,
                validationData.dbValue()
        );
    }

    @Override
    public void close() throws Exception {
        if (dbConnector != null) {
            dbConnector.close();
        }
    }
}
