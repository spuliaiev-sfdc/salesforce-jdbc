package com.ascendix.jdbc.salesforce.metadata;

import com.ascendix.jdbc.salesforce.ForceDriver;
import com.ascendix.jdbc.salesforce.connection.ForceConnection;
import com.ascendix.jdbc.salesforce.delegates.PartnerService;
import com.ascendix.jdbc.salesforce.resultset.CachedResultSet;
import com.ascendix.jdbc.salesforce.statement.ForcePreparedStatement;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ForceDatabaseMetaData implements DatabaseMetaData, Serializable {

    private static final String SF_JDBC_DRIVER_NAME = "SF JDBC driver";
    private static final Logger logger = Logger.getLogger(SF_JDBC_DRIVER_NAME);

    public static final String DEFAULT_SCHEMA = "Salesforce";
    public static final String DEFAULT_CATALOG = "database";
    public static final String DEFAULT_TABLE_TYPE = "TABLE";

    private transient PartnerService partnerService;
    private transient ForceConnection connection;
    private List<Table> tablesCache;
    private int counter;

    public ForceDatabaseMetaData(ForceConnection connection) {
        this.connection = connection;
        this.partnerService = new PartnerService(connection.getPartnerConnection());
    }

    private ForceDatabaseMetaData() {
        this.connection = connection;
        this.partnerService = null;
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) {
        logger.info("[Meta] getTables catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern);
        List<ColumnMap<String, Object>> rows = new ArrayList<>();
        ColumnMap<String, Object> firstRow = null;
        for (Table table : getTables()) {
            if(tableNamePattern == null || "%".equals(tableNamePattern.trim()) || table.getName().equalsIgnoreCase(tableNamePattern)) {
                ColumnMap<String, Object> map = new ColumnMap<>();
                map.put("TABLE_CAT", DEFAULT_CATALOG);
                map.put("TABLE_SCHEM", DEFAULT_SCHEMA);
                map.put("TABLE_NAME", table.getName());
                map.put("TABLE_TYPE", DEFAULT_TABLE_TYPE);
                map.put("REMARKS", table.getComments());
                map.put("TYPE_CAT", null);
                map.put("TYPE_SCHEM", null);
                map.put("TYPE_NAME", null);
                map.put("SELF_REFERENCING_COL_NAME", null);
                map.put("REF_GENERATION", null);
                rows.add(map);
                if (firstRow == null) {
                    firstRow = map;
                }
            }
        }
        logger.info("[Meta] getTables RESULT catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern+
                "\n  firstRowFound="+(firstRow!=null?"yes":"no")+ " TablesFound="+rows.size());
        return new CachedResultSet(rows, ForcePreparedStatement.dummyMetaData(firstRow));
    }

    private List<Table> getTables() {
        if (tablesCache == null) {
            logger.info("[Meta] getTables requested - fetching");
            tablesCache = partnerService.getTables();
        } else {
            logger.info("[Meta] getTables requested - from cache");
        }
        return tablesCache;
    }

    public Table findTableInfo(String tableName) {
        return getTables().stream()
                .filter(table -> table.getName().equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) {
        AtomicInteger ordinal = new AtomicInteger(1);
        logger.info("[Meta] getColumns catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern+" column="+columnNamePattern );
        List<ColumnMap<String, Object>> rows = getTables().stream()
                .filter(table -> tableNamePattern == null || "%".equals(tableNamePattern.trim()) || table.getName().equalsIgnoreCase(tableNamePattern))
                .flatMap(table -> table.getColumns().stream())
                .filter(column -> columnNamePattern == null || "%".equals(columnNamePattern.trim())|| column.getName().equalsIgnoreCase(columnNamePattern))
                .map(column -> new ColumnMap<String, Object>() {{
                    TypeInfo typeInfo = lookupTypeInfo(column.getType());
                    put("TABLE_CAT", DEFAULT_CATALOG);
                    put("TABLE_SCHEM", DEFAULT_SCHEMA);
                    put("TABLE_NAME", column.getTable().getName());
                    put("COLUMN_NAME", column.getName());
                    put("DATA_TYPE", typeInfo != null ? typeInfo.sqlDataType : Types.OTHER);
                    put("TYPE_NAME", column.getType());
                    put("COLUMN_SIZE", column.getLength());
                    put("BUFFER_LENGTH", 0);
                    put("DECIMAL_DIGITS", 0);
                    put("NUM_PREC_RADIX", typeInfo != null ? typeInfo.radix : 10);
                    put("NULLABLE", 0);
                    put("REMARKS", column.getComments());
                    put("COLUMN_DEF", null);
                    put("SQL_DATA_TYPE", null);
                    put("SQL_DATETIME_SUB", null);
                    put("CHAR_OCTET_LENGTH", 0);
                    put("ORDINAL_POSITION", ordinal.getAndIncrement());
                    put("IS_NULLABLE", "");
                    put("SCOPE_CATLOG", null);
                    put("SCOPE_SCHEMA", null);
                    put("SCOPE_TABLE", null);
                    put("SOURCE_DATA_TYPE", column.getType());
                    put("CASE_SENSITIVE", 0);
                    put("NULLABLE",
                            column.isNillable() ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);

                }})
                .collect(Collectors.toList());
        ColumnMap<String, Object> firstRow = rows.size() > 0 ? rows.get(0) : null;
        logger.info("[Meta] getColumns RESULT catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern+" column="+columnNamePattern +
                "\n  firstRowFound="+(firstRow!=null?"yes":"no")+ " ColumnsFound="+rows.size());
        return new CachedResultSet(rows, ForcePreparedStatement.dummyMetaData(firstRow));
    }

    public static TypeInfo lookupTypeInfo(String forceTypeName) {
        String typeName = forceTypeName.replaceFirst("\\A_+", "");
        return Arrays.stream(TYPE_INFO_DATA)
                .filter(entry -> typeName.equals(entry.typeName))
                .findAny()
                .orElse(OTHER_TYPE_INFO);
    }

    public static TypeInfo lookupTypeInfoFromJavaType(String javaTypeName) {
        if (javaTypeName == null) {
            javaTypeName = "string";
        }
        if (javaTypeName.equals("java.lang.Boolean")) {
            javaTypeName = "boolean";
        }
        if (javaTypeName.equals("java.lang.String")) {
            javaTypeName = "string";
        }
        String typeName = javaTypeName;
        return Arrays.stream(TYPE_INFO_DATA)
                .filter(entry -> typeName.equals(entry.typeName))
                .findAny()
                .orElse(OTHER_TYPE_INFO);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        ColumnMap<String, Object> row = new ColumnMap<>();
        row.put("TABLE_SCHEM", DEFAULT_SCHEMA);
        row.put("TABLE_CATALOG", DEFAULT_CATALOG);
        row.put("IS_DEFAULT", true);
        return new CachedResultSet(row, ForcePreparedStatement.dummyMetaData(row));
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String tableName) throws SQLException {
        logger.info("[Meta] getPrimaryKeys RESULT catalog="+catalog+" schema="+schema+" table="+tableName);
        List<ColumnMap<String, Object>> maps = new ArrayList<>();
        ColumnMap<String, Object> firstRow = null;
        for (Table table : getTables()) {
            if (tableName == null || "%".equals(tableName.trim()) || table.getName().equalsIgnoreCase(tableName)) {
                for (Column column : table.getColumns()) {
                    if (column.getName().equalsIgnoreCase("Id")) {
                        ColumnMap<String, Object> map = new ColumnMap<>();
                        map.put("TABLE_CAT", DEFAULT_CATALOG);
                        map.put("TABLE_SCHEM", DEFAULT_SCHEMA);
                        map.put("TABLE_NAME", table.getName());
                        map.put("COLUMN_NAME", "" + column.getName());
                        map.put("KEY_SEQ", 0);
                        map.put("PK_NAME", "FakePK" + counter);
                        maps.add(map);
                        if (firstRow == null) {
                            firstRow = map;
                        }
                    }
                }
            }
        }
        logger.info("[Meta] getPrimaryKeys RESULT catalog="+catalog+" schema="+schema+" table="+tableName+
                "\n  firstRowFound="+(firstRow!=null?"yes":"no")+ " KeysFound="+maps.size());
        return new CachedResultSet(maps, ForcePreparedStatement.dummyMetaData(firstRow));
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String tableName) throws SQLException {
        List<ColumnMap<String, Object>> maps = new ArrayList<>();
        ColumnMap<String, Object> firstRow = null;
        for (Table table : getTables()) {
            if (tableName == null || "%".equals(tableName.trim()) || table.getName().equalsIgnoreCase(tableName)) {
                for (Column column : table.getColumns()) {
                    if (column.getReferencedTable() != null && column.getReferencedColumn() != null) {
                        ColumnMap<String, Object> map = new ColumnMap<>();
                        map.put("PKTABLE_CAT", null);
                        map.put("PKTABLE_SCHEM", null);
                        map.put("PKTABLE_NAME", column.getReferencedTable());
                        map.put("PKCOLUMN_NAME", column.getReferencedColumn());
                        map.put("FKTABLE_CAT", null);
                        map.put("FKTABLE_SCHEM", null);
                        map.put("FKTABLE_NAME", tableName);
                        map.put("FKCOLUMN_NAME", column.getName());
                        map.put("KEY_SEQ", counter);
                        map.put("UPDATE_RULE", 0);
                        map.put("DELETE_RULE", 0);
                        map.put("FK_NAME", "FakeFK" + counter);
                        map.put("PK_NAME", "FakePK" + counter);
                        map.put("DEFERRABILITY", 0);
                        counter++;
                        maps.add(map);
                        if (firstRow == null) {
                            firstRow = map;
                        }
                    }
                }
            }
        }
        return new CachedResultSet(maps, ForcePreparedStatement.dummyMetaData(firstRow));
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String tableName, boolean unique, boolean approximate) {
        List<ColumnMap<String, Object>> maps = new ArrayList<>();
        ColumnMap<String, Object> firstRow = null;
        for (Table table : getTables()) {
            if (tableName == null || "%".equals(tableName.trim()) || table.getName().equalsIgnoreCase(tableName)) {
                for (Column column : table.getColumns()) {
                    if (column.getName().equalsIgnoreCase("Id")) {
                        ColumnMap<String, Object> map = new ColumnMap<>();
                        map.put("TABLE_CAT", DEFAULT_CATALOG);
                        map.put("TABLE_SCHEM", DEFAULT_SCHEMA);
                        map.put("TABLE_NAME", table.getName());
                        map.put("NON_UNIQUE", true);
                        map.put("INDEX_QUALIFIER", null);
                        map.put("INDEX_NAME", "FakeIndex" + counter++);
                        map.put("TYPE", DatabaseMetaData.tableIndexOther);
                        map.put("ORDINAL_POSITION", counter);
                        map.put("COLUMN_NAME", "Id");
                        map.put("ASC_OR_DESC", "A");
                        map.put("CARDINALITY", 1);
                        map.put("PAGES", 1);
                        map.put("FILTER_CONDITION", null);

                        maps.add(map);

                        if (firstRow == null) {
                            firstRow = map;
                        }
                    }
                }
            }
        }
        return new CachedResultSet(maps, ForcePreparedStatement.dummyMetaData(firstRow));
    }

    @SuppressWarnings("unchecked")
    @Override
    public ResultSet getCatalogs() throws SQLException {
        ColumnMap<String, Object> row = new ColumnMap<>();
        row.put("TABLE_CAT", DEFAULT_CATALOG);
        return new CachedResultSet(row, ForcePreparedStatement.dummyMetaData(row));
    }

    public static class TypeInfo {
        public TypeInfo(String typeName, int sqlDataType, int precision, int minScale, int maxScale, int radix) {
            this.typeName = typeName;
            this.sqlDataType = sqlDataType;
            this.precision = precision;
            this.minScale = minScale;
            this.maxScale = maxScale;
            this.radix = radix;
        }

        public String typeName;
        public int sqlDataType;
        public int precision;
        public int minScale;
        public int maxScale;
        public int radix;
    }

    private static TypeInfo OTHER_TYPE_INFO = new TypeInfo("other", Types.OTHER, 0x7fffffff, 0, 0, 0);

    private static TypeInfo TYPE_INFO_DATA[] = {
            new TypeInfo("id", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("masterrecord", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("reference", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("string", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("encryptedstring", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("email", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("phone", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("url", Types.VARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("textarea", Types.LONGVARCHAR, 0x7fffffff, 0, 0, 0),
            new TypeInfo("base64", Types.BLOB, 0x7fffffff, 0, 0, 0),
            new TypeInfo("boolean", Types.BOOLEAN, 1, 0, 0, 0),
            new TypeInfo("_boolean", Types.BOOLEAN, 1, 0, 0, 0),
            new TypeInfo("byte", Types.VARBINARY, 10, 0, 0, 10),
            new TypeInfo("_byte", Types.VARBINARY, 10, 0, 0, 10),
            new TypeInfo("int", Types.INTEGER, 10, 0, 0, 10),
            new TypeInfo("_int", Types.INTEGER, 10, 0, 0, 10),
            new TypeInfo("decimal", Types.DECIMAL, 17, -324, 306, 10),
            new TypeInfo("double", Types.DOUBLE, 17, -324, 306, 10),
            new TypeInfo("_double", Types.DOUBLE, 17, -324, 306, 10),
            new TypeInfo("percent", Types.DOUBLE, 17, -324, 306, 10),
            new TypeInfo("currency", Types.DOUBLE, 17, -324, 306, 10),
            new TypeInfo("date", Types.DATE, 10, 0, 0, 0),
            new TypeInfo("time", Types.TIME, 10, 0, 0, 0),
            new TypeInfo("datetime", Types.TIMESTAMP, 10, 0, 0, 0),
            new TypeInfo("picklist", Types.ARRAY, 0, 0, 0, 0),
            new TypeInfo("multipicklist", Types.ARRAY, 0, 0, 0, 0),
            new TypeInfo("combobox", Types.ARRAY, 0, 0, 0, 0),
            new TypeInfo("anyType", Types.OTHER, 0, 0, 0, 0),
    };

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        ColumnMap<String, Object> firstRow = null;
        List<ColumnMap<String, Object>> rows = new ArrayList<>();
        for (TypeInfo typeInfo : TYPE_INFO_DATA) {
            ColumnMap<String, Object> row = new ColumnMap<>();
            row.put("TYPE_NAME", typeInfo.typeName);
            row.put("DATA_TYPE", typeInfo.sqlDataType);
            row.put("PRECISION", typeInfo.precision);
            row.put("LITERAL_PREFIX", null);
            row.put("LITERAL_SUFFIX", null);
            row.put("CREATE_PARAMS", null);
            row.put("NULLABLE", 1);
            row.put("CASE_SENSITIVE", 0);
            row.put("SEARCHABLE", 3);
            row.put("UNSIGNED_ATTRIBUTE", false);
            row.put("FIXED_PREC_SCALE", false);
            row.put("AUTO_INCREMENT", false);
            row.put("LOCAL_TYPE_NAME", typeInfo.typeName);
            row.put("MINIMUM_SCALE", typeInfo.minScale);
            row.put("MAXIMUM_SCALE", typeInfo.maxScale);
            row.put("SQL_DATA_TYPE", typeInfo.sqlDataType);
            row.put("SQL_DATETIME_SUB", null);
            row.put("NUM_PREC_RADIX", typeInfo.radix);
            row.put("TYPE_SUB", 1);

            rows.add(row);
            if (firstRow == null) {
                firstRow = row;
            }
        }
        return new CachedResultSet(rows, ForcePreparedStatement.dummyMetaData(firstRow));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.info("[Meta] unwrap requested NOT_IMPLEMENTED ifaceType="+iface.getName());
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public String getURL() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getUserName() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return "Salesforce";
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return String.valueOf(getDatabaseMajorVersion());
    }

    @Override
    public String getDriverName() throws SQLException {
        return "Ascendix JDBC driver for Salesforce";
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return getDriverMajorVersion()+"."+getDriverMinorVersion()+".1";
    }

    @Override
    public int getDriverMajorVersion() {
        return 1;
    }

    @Override
    public int getDriverMinorVersion() {
        return 4;
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        // Retrieves whether this database treats mixed case unquoted SQL identifiers as case insensitive and stores them in mixed case.
        return true;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        // Retrieves whether this database treats mixed case unquoted SQL identifiers as case insensitive and stores them in mixed case.
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getStringFunctions() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        // TODO Auto-generated method stub
        return DEFAULT_SCHEMA;
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        // TODO Auto-generated method stub
        return "";
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        // TODO Auto-generated method stub
        return DEFAULT_CATALOG;
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        // TODO Auto-generated method stub
        return ".";
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxConnections() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxStatements() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getProcedures requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" proc="+procedureNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
                                         String columnNamePattern) throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getProcedureColumns requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" procs="+procedureNamePattern+" col="+columnNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        logger.info("[Meta] getTableTypes requested IMPLEMENTED");
        ColumnMap<String, Object> row = new ColumnMap<>();
        row.put("TABLE_TYPE", DEFAULT_TABLE_TYPE);
        return new CachedResultSet(row, ForcePreparedStatement.dummyMetaData(row));
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        logger.info("[Meta] getColumnPrivileges requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schema+" table="+table+" column="+columnNamePattern);
        // TODO Auto-generated method stub
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        logger.info("[Meta] getTablePrivileges requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern);
        // TODO Auto-generated method stub
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        logger.info("[Meta] getBestRowIdentifier requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schema+" table="+table);
        // TODO Auto-generated method stub
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        logger.info("[Meta] getVersionColumns requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schema+" table="+table);
        // TODO Auto-generated method stub
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        logger.info("[Meta] getExportedKeys requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schema+" table="+table);
        // TODO Auto-generated method stub
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        logger.info("[Meta] getCrossReference requested NOT_IMPLEMENTED parentCat="+parentCatalog+" parentSc="+parentSchema+" parentTable="+parentTable+" catalog="+foreignCatalog+" schema="+foreignSchema+" table="+foreignTable);

        // TODO Auto-generated method stub
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getUDTs requested NOT_IMPLEMENTED");
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public Connection getConnection() throws SQLException {
        // TODO Auto-generated method stub
        return connection;
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getSuperTypes requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" type="+typeNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getSuperTables requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
                                   String attributeNamePattern) throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getAttributes requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" type="+typeNamePattern+" attr="+attributeNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return 52;
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getSQLStateType() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getRowIdLifetime requested NOT_IMPLEMENTED");
        return null;
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return getSchemas();
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getClientInfoProperties requested NOT_IMPLEMENTED");
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getSuperTables requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" func="+functionNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
                                        String columnNamePattern) throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getSuperTables requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" func="+functionNamePattern+" column="+columnNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
                                      String columnNamePattern) throws SQLException {
        // TODO Auto-generated method stub
        logger.info("[Meta] getPseudoColumns requested NOT_IMPLEMENTED catalog="+catalog+" schema="+schemaPattern+" table="+tableNamePattern+" column="+columnNamePattern);
        return new CachedResultSet(Collections.EMPTY_LIST, null);
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    public static void main(String[] args) throws SQLException {
        ForceDatabaseMetaData metadata = new ForceDatabaseMetaData();
        System.out.println(metadata.getDriverName() + " version "+metadata.getDriverVersion()+ " for API "+metadata.getDatabaseProductVersion());

        if (args.length > 0) {
            System.out.println("Test the tables from the url ");
            ForceDriver driver = new ForceDriver();
            ForceConnection connection = (ForceConnection)driver.connect(args[0], new Properties());

            ForceDatabaseMetaData metaData = new ForceDatabaseMetaData(connection);
            ResultSet schemas = metaData.getSchemas();
            ResultSet catalogs = metaData.getCatalogs();
            String[] types = null;
            ResultSet tables = metaData.getTables("catalog", "", "%", types);
            int count = 0;
            while(tables.next()) {
                System.out.println(" "+tables.getString("TABLE_NAME"));
                count++;
            }
            System.out.println(count+" Tables total");
        }
    }


}
