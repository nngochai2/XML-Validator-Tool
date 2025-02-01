package org.xmlvalidator.config;

/**
 * Represents document key configuration for XML-to-database mapping.
 * The document key is used to uniquely identify and link XML documents with their corresponding records in the database
 * views in the database views during validation.
 *
 * @param xpath XPath expression to locate the document key element XML
 * @param view Database view name containing the document key
 * @param column Database column name containing the document key value
 */
public record DocumentKeyConfig(String xpath, String view, String column) {
}
