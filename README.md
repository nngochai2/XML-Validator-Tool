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
