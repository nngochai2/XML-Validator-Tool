package org.xmlvalidator.util;

import org.xmlvalidator.model.ValidationResult;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CSVReportWriter implements AutoCloseable {
    private static final String[] HEADERS = {"XML_Path", "View", "Field", "Match", "XML_Value", "DB_Value"};
    private final FileWriter writer;

    public CSVReportWriter(String fileName) throws IOException {
        this.writer = new FileWriter(fileName);
        this.writeHeaders();
    }

    private void writeHeaders() throws IOException {
        writer.write(String.join(",", HEADERS) + "\n");
    }

    public void writeResults(List<ValidationResult> results) throws IOException {
        for (ValidationResult result : results) {
            // Skip null results (indicating missing tags)
            if (result != null) {
                String value = result.xmlValue();
                // Handle empty tags
                if (value != null && value.trim().isEmpty()) {
                    value = "empty tag";
                }

                writer.write(String.format("%s,%s,%s,%s,%s,%s\n",
                        this.escapeCsv(result.xmlPath()),
                        this.escapeCsv(result.view()),
                        this.escapeCsv(result.field()),
                        result.match(),
                        this.escapeCsv(value),
                        this.escapeCsv(result.dbValue())
                ));
            }
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public void close() throws Exception {
        if (writer != null) {
            writer.close();
        }
    }
}