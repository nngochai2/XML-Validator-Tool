# XML to Database Validator

A Java-based command-line tool that validates XML files against database records using configurable mapping rules. Perfect for validating SOAP API responses or any XML data that needs to be verified against database values.

## Features

* XML field-by-field validation against database values
* Configurable validation rules via properties file
* Support for custom SQL validations
* Multi-path validation with priority handling
* Document-level caching for optimal performance
* Detailed CSV reporting
* Comprehensive error handling and logging

## Requirements
* Java 23+
* Access to a relational database
* Maven (for building from source)

## Getting Started

### Installation

1. Download the latest release JAR file
2. Create a `config.properties` file in the same directory
3. Configure your database connection and validation rules

### Usage

```bash
java -jar xml-validator.jar <path-to-xml-file>
```

### Configuration Rules

#### View Mappings
- View names and column names are case-sensitive
- Column names can contain dots (e.g., Item.SalesOrderLineID)

#### Special Cases Handling
1. **Document Key Configuration**
   - Must define xpath, view, and column for document identification
   - Used for linking XML document with database records
   - Example:
   ```properties
   document.key.xpath=<xpath>
   document.key.view=<VIEW_NAME>
   document.key.column=<ColumnName>
   ```
2. **XML Namespaces**
   - All required namespaces must be defined
   - Format: xmlns.prefix=uri

3. **Special SQL Validations**
   - For values that cannot be mapped directly to database columns (e.g., calculated totals)
   - Each validation needs both an xpath and query definition
   ```properties
   sql.validation.<name>.xpath=<XPath>
   sql.validation.<name>.query=<SQL query>
   ```

4. **Multi-path Validations**
   Two types of multi-path validations are supported:
   **Priority Paths**:
   - Used when validation depends on database state
   - Example:
     
   ```properties
   # Higer priority column with their XPaths
   view.VIEW_NAME_1.ColumnName1.paths=1,xpath1,xpath2,xpath3,...

   # Lower priority column with their XPaths
   view.VIEW_NAME_1.ColumnName2.paths=2,xpath1,xpath2,xpath3,...
   ```
   
   **Standalone Paths**:
   - Always validated independently of other rules
   - Not affected by priority logic
   - Example:
   ```properties
   view.VIEW_NAME.ColumnName.standalone.xpath=0,xpath1,xpath2,xpath3,...
   ```
   
