/**
 * Copyright 2016 Otto (GmbH & Co KG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.schedoscope.export.outputschema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;

public class DerbySchema extends AbstractSchema implements Schema {

	protected static final String JDBC_DRIVER_NAME = "org.apache.derby.jdbc.EmbeddedDriver";

	public DerbySchema(Configuration conf) {

		this.conf = conf;
	}

	private String buildCreateTableStatement(String table,
			String[] columnNames, String[] columnTypes) {

		StringBuilder createTableStatement = new StringBuilder();

		createTableStatement.append("CREATE TABLE ");
		createTableStatement.append(table);
		createTableStatement.append(" \n");
		createTableStatement.append("(");
		createTableStatement.append("\n");

		for (int i = 0; i < columnNames.length; i++) {
			createTableStatement.append(columnNames[i]);
			createTableStatement.append(" ");
			createTableStatement.append(columnTypes[i]);
			if (i != columnNames.length - 1) {
				createTableStatement.append(",");
			}
			createTableStatement.append("\n");
		}

		createTableStatement = createTableStatement.append(")");

		return createTableStatement.toString();
	}

	@Override
	public void setOutput(String connectionString,
			String username, String password, String outputTable,
			String inputFilter, int outputNumberOfPartitions,
			int outputCommitSize, String[] columnNames, String[] columnTypes) {

		conf.set(Schema.JDBC_DRIVER_CLASS, JDBC_DRIVER_NAME);
		conf.set(Schema.JDBC_CONNECTION_STRING, connectionString);
		if (username != null) {
			conf.set(Schema.JDBC_USERNAME, username);
		}
		if (password != null) {
			conf.set(Schema.JDBC_PASSWORD, password);
		}
		if (inputFilter != null && !inputFilter.isEmpty()) {
			conf.set(Schema.JDBC_INPUT_FILTER, inputFilter);
		}

		conf.set(Schema.JDBC_OUTPUT_TABLE, outputTable);
		conf.setStrings(Schema.JDBC_OUTPUT_COLUMN_NAMES, columnNames);
		conf.setStrings(Schema.JDBC_OUTPUT_COLUMN_TYPES, columnTypes);
		conf.setInt(Schema.JDBC_NUMBER_OF_PARTITIONS, outputNumberOfPartitions);
		conf.setInt(Schema.JDBC_COMMIT_SIZE, outputCommitSize);

		conf.set(
				Schema.JDBC_CREATE_TABLE_QUERY,
				buildCreateTableStatement(outputTable, columnNames, columnTypes));
	}

	@Override
	public Connection getConnection() throws ClassNotFoundException,
			SQLException {
		Class.forName(conf.get(Schema.JDBC_DRIVER_CLASS));

		if (conf.get(Schema.JDBC_USERNAME) == null
				|| conf.get(Schema.JDBC_PASSWORD) == null) {
			return DriverManager.getConnection(conf
					.get(Schema.JDBC_CONNECTION_STRING));
		} else {
			return DriverManager.getConnection(
					conf.get(Schema.JDBC_CONNECTION_STRING),
					conf.get(Schema.JDBC_USERNAME),
					conf.get(Schema.JDBC_PASSWORD));
		}

	}

	@Override
	public Map<String, String> getColumnTypeMapping() {
		Map<String, String> dataTypes = new HashMap<String, String>();
		dataTypes.put("string", "varchar(100)");
		dataTypes.put("boolean", "boolean");
		dataTypes.put("int", "int");
		dataTypes.put("long", "bigint");
		dataTypes.put("bigint", "bigint");
		dataTypes.put("double", "double");
		dataTypes.put("float", "float");
		dataTypes.put("tinyint", "int");
		return dataTypes;
	}

	@Override
	public Map<String, String> getPreparedStatementTypeMapping() {
		Map<String, String> dataTypes = new HashMap<String, String>();
		dataTypes.put("varchar(100)", "string");
		dataTypes.put("boolean", "boolean");
		dataTypes.put("int", "int");
		dataTypes.put("bigint", "long");
		dataTypes.put("double", "double");
		dataTypes.put("float", "float");
		dataTypes.put("tinyint", "int");
		return dataTypes;
	}

}
