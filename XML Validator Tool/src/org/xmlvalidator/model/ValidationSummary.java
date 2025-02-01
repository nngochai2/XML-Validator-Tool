package org.xmlvalidator.model;

public record ValidationSummary(int successful, int failed, String reportFile) {
    public int getTotal() {
        return successful + failed;
    }
}
