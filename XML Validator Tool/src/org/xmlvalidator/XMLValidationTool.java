package org.xmlvalidator;

import org.xmlvalidator.core.XMLValidator;
import org.xmlvalidator.model.ValidationSummary;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

public class XMLValidationTool {
    private static final Logger logger = Logger.getLogger(XMLValidationTool.class.getName());
    private static final String CONFIG_FILE = "configs.properties";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar xml-validator.jar <path-to-xml-file>");
            System.out.println("Example: java -jar xml-validator.jar invoice.xml");
            return;
        }

        try {
            String xmlFile = args[0]; // Requires XML file

            // Get current working directory instead of JAR location
            String currentDir = System.getProperty("user.dir");
            Path configPath = Paths.get(currentDir, CONFIG_FILE);
            String propertiesFile = configPath.toString();

            // Check if files exist
            if (!new File(xmlFile).exists()) {
                System.err.println("XML file does not exist: " + xmlFile);
                return;
            }
            if (!new File(propertiesFile).exists()) {
                System.err.println("Configuration file not found in application directory: " + propertiesFile);
                System.err.println("Please ensure config.properties is in the same directory as the JAR file.");
                return;
            }

            System.out.println("Validating XML file: " + xmlFile);
            System.out.println("Validation properties file: " + propertiesFile);

            // Initialize validator with properties
            try (XMLValidator validator = new XMLValidator(propertiesFile)) {

                // Run validation
                ValidationSummary summary = validator.validate(xmlFile);

                // Print summary
                System.out.println("\nValidation Summary:");
                System.out.println("Total validations: " + summary.getTotal());
                System.out.println("Successful: " + summary.successful());
                System.out.println("Failed: " + summary.failed());
                System.out.println("\nResults written to: " + summary.reportFile());
            } catch (Exception e) {
                System.err.println("Error during validation: " + e.getMessage());
                logger.severe("Error during validation: " + e.getMessage());
            }


        } catch (Exception e) {
            System.err.println("Error during validation: " + e.getMessage());
            logger.severe("Error during validation: " + e.getMessage());
        }
    }
}
