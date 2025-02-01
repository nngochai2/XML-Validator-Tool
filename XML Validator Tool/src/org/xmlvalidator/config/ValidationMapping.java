package org.xmlvalidator.config;

/**
 * Maps XML paths to database view columns for validation
 *
 * @param xmlPath    XPath expression to locate XML element
 * @param viewName   Database view name
 * @param columnName Database column name
 */
public record ValidationMapping(String xmlPath, String viewName, String columnName) {
}
