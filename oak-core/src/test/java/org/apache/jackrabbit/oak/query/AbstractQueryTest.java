/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.query;

import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NODE_TYPE;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.REINDEX_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.TYPE_PROPERTY_NAME;
import static org.apache.jackrabbit.oak.plugins.memory.PropertyStates.createProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;

import com.google.common.collect.Lists;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.mk.json.JsopReader;
import org.apache.jackrabbit.mk.json.JsopTokenizer;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.PropertyValue;
import org.apache.jackrabbit.oak.api.QueryEngine;
import org.apache.jackrabbit.oak.api.Result;
import org.apache.jackrabbit.oak.api.ResultRow;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.kernel.TypeCodes;
import org.apache.jackrabbit.oak.plugins.memory.BooleanPropertyState;
import org.apache.jackrabbit.oak.plugins.memory.StringPropertyState;
import org.apache.jackrabbit.oak.plugins.value.Conversions;
import org.apache.jackrabbit.oak.query.xpath.XPathToSQL2Converter;
import org.junit.Before;

/**
 * AbstractQueryTest...
 */
public abstract class AbstractQueryTest {

    protected static final String TEST_INDEX_NAME = "test-index";
    protected static final String SQL2 = QueryEngineImpl.SQL2;

    protected QueryEngine qe;
    protected ContentSession session;
    protected Root root;

    @Before
    public void before() throws Exception {
        session = createRepository().login(null, null);
        root = session.getLatestRoot();
        qe = root.getQueryEngine();
        createTestIndexNode();
    }

    protected abstract ContentRepository createRepository();

    /**
     * Override this method to add your default index definition
     * 
     * {@link #createTestIndexNode(Tree, String)} for a helper method
     */
    protected void createTestIndexNode() throws Exception {
        Tree index = root.getTree("/");
        createTestIndexNode(index, "unknown");
        root.commit();
    }

    protected static Tree createTestIndexNode(Tree index, String type)
            throws Exception {
        Tree indexDef = index.addChild(INDEX_DEFINITIONS_NAME).addChild(
                TEST_INDEX_NAME);
        indexDef.setProperty(JcrConstants.JCR_PRIMARYTYPE,
                INDEX_DEFINITIONS_NODE_TYPE, Type.NAME);
        indexDef.setProperty(TYPE_PROPERTY_NAME, type);
        indexDef.setProperty(REINDEX_PROPERTY_NAME, true);
        return indexDef;
    }

    protected Result executeQuery(String statement, String language,
            Map<String, PropertyValue> sv) throws ParseException {
        return qe.executeQuery(statement, language, Long.MAX_VALUE, 0, sv, null);
    }

    protected void test(String file) throws Exception {
        InputStream in = AbstractQueryTest.class.getResourceAsStream(file);
        LineNumberReader r = new LineNumberReader(new InputStreamReader(in));
        String className = getClass().getName();
        String shortClassName = className.replaceAll("org.apache.jackrabbit.oak.plugins.index.", "oajopi.");
        PrintWriter w = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("target/" + shortClassName + "_"
                        + file)));
        HashSet<String> knownQueries = new HashSet<String>();
        boolean errors = false;
        try {
            while (true) {
                String line = r.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.startsWith("#") || line.length() == 0) {
                    w.println(line);
                } else if (line.startsWith("xpath2sql")) {
                    line = line.substring("xpath2sql".length()).trim();
                    w.println("xpath2sql " + line);
                    XPathToSQL2Converter c = new XPathToSQL2Converter();
                    String got;
                    try {
                        got = c.convert(line);
                    } catch (ParseException e) {
                        got = "invalid: " + e.getMessage().replace('\n', ' ');
                    } catch (Exception e) {
                        // e.printStackTrace();
                        got = "error: " + e.toString().replace('\n', ' ');
                    }
                    if (!knownQueries.add(line)) {
                        got = "duplicate xpath2sql query";
                    }
                    line = r.readLine().trim();
                    w.println(got);
                    if (!line.equals(got)) {
                        errors = true;
                    }
                } else if (line.startsWith("select")
                        || line.startsWith("explain")
                        || line.startsWith("measure")
                        || line.startsWith("sql1") || line.startsWith("xpath")) {
                    w.println(line);
                    String language = QueryEngineImpl.SQL2;
                    if (line.startsWith("sql1 ")) {
                        language = QueryEngineImpl.SQL;
                        line = line.substring("sql1 ".length());
                    } else if (line.startsWith("xpath ")) {
                        language = QueryEngineImpl.XPATH;
                        line = line.substring("xpath ".length());
                    }
                    boolean readEnd = true;
                    for (String resultLine : executeQuery(line, language)) {
                        w.println(resultLine);
                        if (readEnd) {
                            line = r.readLine();
                            if (line == null) {
                                errors = true;
                                readEnd = false;
                            } else {
                                line = line.trim();
                                if (line.length() == 0) {
                                    errors = true;
                                    readEnd = false;
                                } else {
                                    if (!line.equals(resultLine)) {
                                        errors = true;
                                    }
                                }
                            }
                        }
                    }
                    w.println("");
                    if (readEnd) {
                        while (true) {
                            line = r.readLine();
                            if (line == null) {
                                break;
                            }
                            line = line.trim();
                            if (line.length() == 0) {
                                break;
                            }
                            errors = true;
                        }
                    }
                } else if (line.startsWith("commit")) {
                    w.println(line);
                    line = line.substring("commit".length()).trim();
                    apply(root, line);
                    root.commit();
                }
                w.flush();
            }
        } finally {
            w.close();
            r.close();
        }
        if (errors) {
            throw new Exception("Results in target/" + file
                    + " don't match expected "
                    + "results in src/test/resources/" + file
                    + "; compare the files for details");
        }
    }

    protected List<String> executeQuery(String query, String language) {
        return executeQuery(query, language, false);
    }

    protected List<String> executeQuery(String query, String language, boolean pathsOnly) {
        long time = System.currentTimeMillis();
        List<String> lines = new ArrayList<String>();
        try {
            Result result = executeQuery(query, language, null);
            for (ResultRow row : result.getRows()) {
                lines.add(readRow(row, pathsOnly));
            }
            if (!query.contains("order by")) {
                Collections.sort(lines);
            }
        } catch (ParseException e) {
            lines.add(e.toString());
        } catch (IllegalArgumentException e) {
            lines.add(e.toString());
        }
        time = System.currentTimeMillis() - time;
        if (time > 10000 && !isDebugModeEnabled()) {
            fail("Query took too long: " + query + " took " + time + " ms");
        }
        return lines;
    }

    protected List<String> assertQuery(String sql, List<String> expected) {
        return assertQuery(sql, SQL2, expected);
    }

    protected List<String> assertQuery(String sql, String language,
            List<String> expected) {
        List<String> paths = executeQuery(sql, language, true);
        assertEquals("Result set size is different", expected.size(),
                paths.size());
        for (String p : expected) {
            assertTrue(paths.contains(p));
        }
        return paths;
    }

    protected void setTravesalFallback(boolean traversal) {
        ((QueryEngineImpl) qe).setTraversalFallback(traversal);
    }

    protected static String readRow(ResultRow row, boolean pathOnly) {
        if (pathOnly) {
            return row.getValue(QueryImpl.JCR_PATH).getValue(Type.STRING);
        }
        StringBuilder buff = new StringBuilder();
        PropertyValue[] values = row.getValues();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                buff.append(", ");
            }
            PropertyValue v = values[i];
            buff.append(v == null ? "null" : v.getValue(Type.STRING));
        }
        return buff.toString();
    }

    /**
     * Check whether the test is running in debug mode.
     * 
     * @return true if debug most is (most likely) enabled
     */
    protected static boolean isDebugModeEnabled() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().toString().indexOf("-agentlib:jdwp") > 0;
    }

    /**
     * Applies the commit string to a given Root instance
     *
     * The commit string represents a sequence of operations, jsonp style:
     *
     * <p>
     * / + "test": { "a": { "id": "ref:123" }, "b": { "id" : "str:123" }}
     * <p>
     * or
     * <p>
     * "/ - "test"
     * </p>
     *
     * @param root
     * @param commit the commit string
     * @throws UnsupportedOperationException if the operation is not supported
     */
    private static void apply(Root root, String commit)
            throws UnsupportedOperationException {
        int index = commit.indexOf(' ');
        String path = commit.substring(0, index).trim();
        Tree c = root.getTree(path);
        if (!c.exists()) {
            // TODO create intermediary?
            throw new UnsupportedOperationException("Non existing path " + path);
        }
        commit = commit.substring(index);
        JsopTokenizer tokenizer = new JsopTokenizer(commit);
        if (tokenizer.matches('-')) {
            removeTree(c, tokenizer);
        } else if (tokenizer.matches('+')) {
            addTree(c, tokenizer);
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported " + (char) tokenizer.read()
                    + ". This should be either '+' or '-'.");
        }
    }

    private static void removeTree(Tree t, JsopTokenizer tokenizer) {
        String path = tokenizer.readString();
        for (String p : PathUtils.elements(path)) {
            if (!t.hasChild(p)) {
                return;
            }
            t = t.getChild(p);
        }
        t.remove();
    }

    private static void addTree(Tree t, JsopTokenizer tokenizer) {
        do {
            String key = tokenizer.readString();
            tokenizer.read(':');
            if (tokenizer.matches('{')) {
                Tree c = t.addChild(key);
                if (!tokenizer.matches('}')) {
                    addTree(c, tokenizer);
                    tokenizer.read('}');
                }
            } else if (tokenizer.matches('[')) {
                t.setProperty(readArrayProperty(key, tokenizer));
            } else {
                t.setProperty(readProperty(key, tokenizer));
            }
        } while (tokenizer.matches(','));
    }

    /**
     * Read a {@code PropertyState} from a {@link JsopReader}
     * @param name  The name of the property state
     * @param reader  The reader
     * @return new property state
     */
    private static PropertyState readProperty(String name, JsopReader reader) {
        if (reader.matches(JsopReader.NUMBER)) {
            String number = reader.getToken();
            return createProperty(name, number, PropertyType.LONG);
        } else if (reader.matches(JsopReader.TRUE)) {
            return BooleanPropertyState.booleanProperty(name, true);
        } else if (reader.matches(JsopReader.FALSE)) {
            return BooleanPropertyState.booleanProperty(name, false);
        } else if (reader.matches(JsopReader.STRING)) {
            String jsonString = reader.getToken();
            int split = TypeCodes.split(jsonString);
            if (split != -1) {
                int type = TypeCodes.decodeType(split, jsonString);
                String value = TypeCodes.decodeName(split, jsonString);
                if (type == PropertyType.BINARY) {
                    throw new UnsupportedOperationException();
                } else {
                    return createProperty(name, value, type);
                }
            } else {
                return StringPropertyState.stringProperty(name, jsonString);
            }
        } else {
            throw new IllegalArgumentException("Unexpected token: " + reader.getToken());
        }
    }

    /**
     * Read a multi valued {@code PropertyState} from a {@link JsopReader}
     * @param name  The name of the property state
     * @param reader  The reader
     * @return new property state
     */
    private static PropertyState readArrayProperty(String name, JsopReader reader) {
        int type = PropertyType.STRING;
        List<Object> values = Lists.newArrayList();
        while (!reader.matches(']')) {
            if (reader.matches(JsopReader.NUMBER)) {
                String number = reader.getToken();
                type = PropertyType.LONG;
                values.add(Conversions.convert(number).toLong());
            } else if (reader.matches(JsopReader.TRUE)) {
                type = PropertyType.BOOLEAN;
                values.add(true);
            } else if (reader.matches(JsopReader.FALSE)) {
                type = PropertyType.BOOLEAN;
                values.add(false);
            } else if (reader.matches(JsopReader.STRING)) {
                String jsonString = reader.getToken();
                int split = TypeCodes.split(jsonString);
                if (split != -1) {
                    type = TypeCodes.decodeType(split, jsonString);
                    String value = TypeCodes.decodeName(split, jsonString);
                    if (type == PropertyType.BINARY) {
                        throw new UnsupportedOperationException();
                    } else if (type == PropertyType.DOUBLE) {
                        values.add(Conversions.convert(value).toDouble());
                    } else if (type == PropertyType.DECIMAL) {
                        values.add(Conversions.convert(value).toDecimal());
                    } else {
                        values.add(value);
                    }
                } else {
                    type = PropertyType.STRING;
                    values.add(jsonString);
                }
            } else {
                throw new IllegalArgumentException("Unexpected token: " + reader.getToken());
            }
            reader.matches(',');
        }
        return createProperty(name, values, Type.fromTag(type, true));
    }

}