package org.xmlvalidator.model;

public record ValidationResult(String xmlPath, String view, String field, boolean match, String xmlValue, String dbValue) {
    public ValidationResult(String xmlPath, String view, String field,
                            boolean match, String xmlValue, String dbValue) {
        this.xmlPath = xmlPath != null ? xmlPath : "";
        this.view = view != null ? view : "";
        this.field = field != null ? field : "";
        this.match = match;
        this.xmlValue = xmlValue != null ? xmlValue : "";
        this.dbValue = dbValue != null ? dbValue : "";
    }
}
