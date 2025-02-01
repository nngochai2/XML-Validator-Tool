package org.xmlvalidator.config;

/**
 * Represents a custom SQL validation configuration. This record holds the mapping between an XML element and a custom
 * SQL query used for complex validation scenarios that cannot be handled by simple value matching.
 *
 * @param xpath XPath expression to locate XML element in the document
 * @param query Custom SQL query for validation, defined in the properties file
 */
public record SqlMapping(String xpath, String query) {
}
