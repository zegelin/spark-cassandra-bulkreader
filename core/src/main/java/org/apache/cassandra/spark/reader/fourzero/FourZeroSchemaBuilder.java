package org.apache.cassandra.spark.reader.fourzero;

import org.apache.cassandra.spark.data.CqlField;
import org.apache.cassandra.spark.data.CqlSchema;
import org.apache.cassandra.spark.data.ReplicationFactor;
import org.apache.cassandra.spark.data.fourzero.complex.CqlFrozen;
import org.apache.cassandra.spark.data.fourzero.complex.CqlUdt;
import org.apache.cassandra.spark.data.partitioner.Partitioner;
import org.apache.cassandra.spark.reader.CassandraBridge;
import org.apache.cassandra.spark.shaded.fourzero.antlr.runtime.RecognitionException;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.cql3.CQL3Type;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.cql3.CQLFragmentParser;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.cql3.CqlParser;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.cql3.statements.schema.CreateTypeStatement;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.Keyspace;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.ListType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.MapType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.SetType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.TupleType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.db.marshal.UserType;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.Schema;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.TableMetadata;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.spark.shaded.fourzero.cassandra.schema.Types;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

public class FourZeroSchemaBuilder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FourZeroSchemaBuilder.class);

    public static final Pattern OSS_PACKAGE_NAME = Pattern.compile("\\borg\\.apache\\.cassandra\\.(?!spark\\.shaded\\.)");
    public static final String SHADED_PACKAGE_NAME = "org.apache.cassandra.spark.shaded.fourzero.cassandra.";

    private final TableMetadata metadata;
    private final KeyspaceMetadata keyspaceMetadata;
    private final String createStmt, keyspace;
    private final ReplicationFactor rf;
    private final CassandraBridge fourZero;

    public FourZeroSchemaBuilder(final CqlSchema schema,
                                 final Partitioner partitioner)
    {
        this(schema.createStmt(), schema.keyspace(), schema.replicationFactor(), partitioner, schema.udtCreateStmts());
    }

    public FourZeroSchemaBuilder(final String createStmt,
                                 final String keyspace,
                                 final ReplicationFactor rf)
    {
        this(createStmt, keyspace, rf, Partitioner.Murmur3Partitioner, Collections.emptySet());
    }

    public FourZeroSchemaBuilder(final String createStmt,
                                 final String keyspace,
                                 final ReplicationFactor rf,
                                 final Partitioner partitioner)
    {
        this(createStmt, keyspace, rf, partitioner, Collections.emptySet());
    }

    public FourZeroSchemaBuilder(final String createStmt,
                                 final String keyspace,
                                 final ReplicationFactor rf,
                                 final Partitioner partitioner,
                                 final Set<String> udtStmts)
    {
        this.createStmt = convertToShadedPackages(createStmt);
        this.keyspace = keyspace;
        this.rf = rf;
        this.fourZero = CassandraBridge.get(CassandraBridge.CassandraVersion.FOURZERO);

        // parse UDTs and include when parsing table schema
        final List<CreateTypeStatement.Raw> typeStatements = new ArrayList<>(udtStmts.size());
        for (final String udt : udtStmts)
        {
            try
            {
                typeStatements.add((CreateTypeStatement.Raw) CQLFragmentParser.parseAnyUnhandled(CqlParser::query, udt));
            }
            catch (final RecognitionException e)
            {
                LOGGER.error("Failed to parse type expression '{}'", udt);
                throw new IllegalStateException(e);
            }
        }
        final Types.RawBuilder typesBuilder = Types.rawBuilder(keyspace);
        for (CreateTypeStatement.Raw st : typeStatements)
        {
            st.addToRawBuilder(typesBuilder);
        }
        final Types types = typesBuilder.build();

        final TableMetadata tableMetadata = CQLFragmentParser.parseAny(CqlParser::createTableStatement, this.createStmt, "CREATE TABLE")
                                                             .keyspace(keyspace)
                                                             .prepare(null)
                                                             .builder(types)
                                                             .partitioner(FourZero.getPartitioner(partitioner))
                                                             .build();
        tableMetadata.columns().forEach(this::validateColumnMetaData);

        if (!keyspaceExists(keyspace))
        {
            setupKeyspaceTable(keyspace, rf, tableMetadata);
        }
        if (!tableExists(keyspace, tableMetadata.name))
        {
            setupTable(keyspace, tableMetadata);
        }

        if (!keyspaceExists(keyspace))
        {
            throw new IllegalStateException("Keyspace does not exist after SchemaBuilder: " + keyspace);
        }
        if (!tableExists(keyspace, tableMetadata.name))
        {
            throw new IllegalStateException("Table does not exist after SchemaBuilder: " + keyspace + "." + tableMetadata.name);
        }
        KeyspaceMetadata keyspaceMetadata = Schema.instance.getKeyspaceMetadata(this.keyspace);
        if (keyspaceMetadata == null)
        {
            throw new IllegalStateException("KeyspaceMetadata does not exist after SchemaBuilder: " + keyspace);
        }
        if (!udtStmts.isEmpty())
        {
            // update Schema instance with any user-defined types built
            keyspaceMetadata = keyspaceMetadata.withSwapped(types);
            Schema.instance.load(keyspaceMetadata);
        }

        // will throw IllegalArgumentException if table doesn't exist
        Schema.instance.getKeyspaceInstance(keyspace).getColumnFamilyStore(tableMetadata.name);

        this.metadata = keyspaceMetadata.getTableOrViewNullable(tableMetadata.name);
        if (this.metadata == null)
        {
            throw new IllegalStateException("TableMetadata does not exist after SchemaBuilder: " + keyspace);
        }
        this.keyspaceMetadata = keyspaceMetadata;
    }

    private void validateColumnMetaData(@NotNull final ColumnMetadata column)
    {
        validateType(column.type);
    }

    private void validateType(final AbstractType<?> type)
    {
        validateType(type.asCQL3Type());
    }

    private void validateType(final CQL3Type cqlType)
    {
        if (!(cqlType instanceof CQL3Type.Native) && !(cqlType instanceof CQL3Type.Collection) && !(cqlType instanceof CQL3Type.UserDefined) && !(cqlType instanceof CQL3Type.Tuple))
        {
            throw new UnsupportedOperationException("Only native, collection, tuples or UDT data types are supported, unsupported data type: " + cqlType.toString());
        }

        if (cqlType instanceof CQL3Type.Native)
        {
            final CqlField.CqlType type = fourZero.parseType(cqlType.toString());
            if (!type.isSupported())
            {
                throw new UnsupportedOperationException(type.name() + " data type is not supported");
            }
        }
        else if (cqlType instanceof CQL3Type.Collection)
        {
            // validate collection inner types
            final CQL3Type.Collection collection = (CQL3Type.Collection) cqlType;
            final CollectionType<?> type = (CollectionType<?>) collection.getType();
            switch (type.kind)
            {
                case LIST:
                    validateType(((ListType<?>) type).getElementsType());
                    return;
                case SET:
                    validateType(((SetType<?>) type).getElementsType());
                    return;
                case MAP:
                    validateType(((MapType<?, ?>) type).getKeysType());
                    validateType(((MapType<?, ?>) type).getValuesType());
            }
        }
        else if (cqlType instanceof CQL3Type.Tuple)
        {
            final CQL3Type.Tuple tuple = (CQL3Type.Tuple) cqlType;
            final TupleType tupleType = (TupleType) tuple.getType();
            for (final AbstractType<?> subType : tupleType.allTypes())
            {
                validateType(subType);
            }
        }
        else
        {
            // validate UDT inner types
            final UserType userType = (UserType) ((CQL3Type.UserDefined) cqlType).getType();
            for (final AbstractType<?> innerType : userType.fieldTypes())
            {
                validateType(innerType);
            }
        }
    }

    private static boolean keyspaceExists(final String keyspaceName)
    {
        return Schema.instance.getKeyspaceInstance(keyspaceName) != null;
    }

    private static boolean tableExists(final String keyspaceName, final String tableName)
    {
        return Schema.instance.getKeyspaceMetadata(keyspaceName).hasTable(tableName);
    }

    private static synchronized void setupKeyspaceTable(final String keyspaceName,
                                                        final ReplicationFactor rf,
                                                        final TableMetadata tableMetadata)
    {
        if (keyspaceExists(keyspaceName))
        {
            return;
        }
        LOGGER.info("Setting up keyspace and table schema keyspace={} rfStrategy={} table={} partitioner={}",
                    keyspaceName, rf.getReplicationStrategy().name(), tableMetadata.name, tableMetadata.partitioner.getClass().getName());
        final KeyspaceMetadata keyspaceMetadata = KeyspaceMetadata.create(keyspaceName, KeyspaceParams.create(true, rfToMap(rf)));
        Schema.instance.load(keyspaceMetadata.withSwapped(keyspaceMetadata.tables.with(tableMetadata)));
        Keyspace.openWithoutSSTables(keyspaceName);
    }

    private static synchronized void setupTable(final String keyspaceName,
                                                final TableMetadata tableMetadata)
    {
        final KeyspaceMetadata keyspaceMetadata = Schema.instance.getKeyspaceMetadata(keyspaceName);
        if (keyspaceMetadata == null)
        {
            throw new IllegalStateException("Keyspace meta-data null for '" + keyspaceName + "' when should have been initialized already");
        }
        if (tableExists(keyspaceName, tableMetadata.name))
        {
            return;
        }
        LOGGER.info("Setting up table schema keyspace={} table={} partitioner={}",
                    keyspaceName, tableMetadata.name, tableMetadata.partitioner.getClass().getName());
        Schema.instance.load(keyspaceMetadata.withSwapped(keyspaceMetadata.tables.with(tableMetadata)));
        Schema.instance.getKeyspaceInstance(keyspaceName).initCf(TableMetadataRef.forOfflineTools(tableMetadata), false);
    }

    public TableMetadata tableMetaData()
    {
        return metadata;
    }

    public String createStmt()
    {
        return createStmt;
    }

    public CqlSchema build()
    {
        final Map<String, CqlField.CqlUdt> udts = buildsUdts(this.keyspaceMetadata);
        return new CqlSchema(keyspace, metadata.name, createStmt, rf, buildFields(metadata, udts).stream().sorted().collect(Collectors.toList()), new HashSet<>(udts.values()));
    }

    private Map<String, CqlField.CqlUdt> buildsUdts(final KeyspaceMetadata keyspaceMetadata)
    {
        final List<UserType> userTypes = new ArrayList<>();
        keyspaceMetadata.types.forEach(userTypes::add);
        final Map<String, CqlField.CqlUdt> udts = new HashMap<>(userTypes.size());
        while (!userTypes.isEmpty())
        {
            final UserType userType = userTypes.remove(0);
            if (!FourZeroSchemaBuilder.nestedUdts(userType).stream().allMatch(udts::containsKey))
            {
                // this UDT contains a nested user-defined type that has not been parsed yet
                // so re-add to the queue and parse later.
                userTypes.add(userType);
                continue;
            }
            final String name = userType.getNameAsString();
            final CqlUdt.Builder builder = CqlUdt.builder(keyspaceMetadata.name, name);
            for (int i = 0; i < userType.size(); i++)
            {
                builder.withField(userType.fieldName(i).toString(), fourZero.parseType(userType.fieldType(i).asCQL3Type().toString(), udts));
            }
            udts.put(name, builder.build());
        }

        return udts;
    }

    /**
     * @param type an abstract type
     * @return a set of UDTs nested within the type parameter
     */
    private static Set<String> nestedUdts(final AbstractType<?> type)
    {
        final Set<String> result = new HashSet<>();
        nestedUdts(type, result, false);
        return result;
    }

    private static void nestedUdts(final AbstractType<?> type, final Set<String> udts, final boolean isNested)
    {
        if (type instanceof UserType)
        {
            if (isNested)
            {
                udts.add(((UserType) type).getNameAsString());
            }
            for (final AbstractType<?> nestedType : ((UserType) type).fieldTypes())
            {
                nestedUdts(nestedType, udts, true);
            }
        }
        else if (type instanceof TupleType)
        {
            for (final AbstractType<?> nestedType : ((TupleType) type).allTypes())
            {
                nestedUdts(nestedType, udts, true);
            }
        }
        else if (type instanceof SetType)
        {
            nestedUdts(((SetType<?>) type).getElementsType(), udts, true);
        }
        else if (type instanceof ListType)
        {
            nestedUdts(((ListType<?>) type).getElementsType(), udts, true);
        }
        else if (type instanceof MapType)
        {
            nestedUdts(((MapType<?, ?>) type).getKeysType(), udts, true);
            nestedUdts(((MapType<?, ?>) type).getValuesType(), udts, true);
        }
    }

    private List<CqlField> buildFields(final TableMetadata metadata, final Map<String, CqlField.CqlUdt> udts)
    {
        final Iterator<ColumnMetadata> it = metadata.allColumnsInSelectOrder();
        final List<CqlField> result = new ArrayList<>();
        int pos = 0;
        while (it.hasNext())
        {
            final ColumnMetadata col = it.next();
            final boolean isPartitionKey = col.isPartitionKey();
            final boolean isClusteringColumn = col.isClusteringColumn();
            final boolean isStatic = col.isStatic();
            final String name = col.name.toCQLString();
            final CqlField.CqlType type = col.type.isUDT() ? udts.get(((UserType) col.type).getNameAsString()) : fourZero.parseType(col.type.asCQL3Type().toString(), udts);
            final boolean isFrozen = col.type.isFreezable() && !col.type.isMultiCell();
            result.add(new CqlField(isPartitionKey, isClusteringColumn, isStatic, name, (!(type instanceof CqlFrozen) && isFrozen) ? CqlFrozen.build(type) : type, pos));
            pos++;
        }
        return result;
    }

    private static Map<String, String> rfToMap(final ReplicationFactor rf)
    {
        final Map<String, String> result = new HashMap<>(rf.getOptions().size() + 1);
        result.put("class", "org.apache.cassandra.spark.shaded.fourzero.cassandra.locator." + rf.getReplicationStrategy().name());
        for (final Map.Entry<String, Integer> entry : rf.getOptions().entrySet())
        {
            result.put(entry.getKey(), Integer.toString(entry.getValue()));
        }
        return result;
    }

    /**
     * Converts an arbitrary string that contains OSS Cassandra package names (such as a
     * CREATE TABLE statement) into the equivalent string that uses shaded package names.
     * If the string does not contain OSS Cassandra package names, it is returned unchanged.
     *
     * @param string an arbitrary string that contains OSS Cassandra package names
     * @return the equivalent string that uses shaded package names
     */
    @NotNull
    public static String convertToShadedPackages(@NotNull final String string)
    {
        return OSS_PACKAGE_NAME.matcher(string).replaceAll(SHADED_PACKAGE_NAME);
    }
}
