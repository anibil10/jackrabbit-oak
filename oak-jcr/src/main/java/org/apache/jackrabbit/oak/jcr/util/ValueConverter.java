/*
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
 */

package org.apache.jackrabbit.oak.jcr.util;

import org.apache.jackrabbit.ScalarImpl;
import org.apache.jackrabbit.oak.api.Scalar;
import org.apache.jackrabbit.oak.jcr.json.JsonValue;
import org.apache.jackrabbit.oak.jcr.json.JsonValue.JsonArray;
import org.apache.jackrabbit.oak.jcr.json.JsonValue.JsonAtom;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for converting between internal value representation and JCR
 * values.
 * todo: needs refactoring. see OAK-16.
 */
public final class ValueConverter {
    private ValueConverter() {}

    public static Scalar toScalar(Value value) throws RepositoryException {
        switch (value.getType()) {
            case PropertyType.STRING: {
                return ScalarImpl.stringScalar(value.getString());
            }
            case PropertyType.DOUBLE: {
                return ScalarImpl.doubleScalar(value.getDouble());
            }
            case PropertyType.LONG: {
                return ScalarImpl.longScalar(value.getLong());
            }
            case PropertyType.BOOLEAN: {
                return ScalarImpl.booleanScalar(value.getBoolean());
            }
            case PropertyType.DECIMAL:
            case PropertyType.BINARY:
            case PropertyType.DATE:
            case PropertyType.NAME:
            case PropertyType.PATH:
            case PropertyType.REFERENCE:
            case PropertyType.WEAKREFERENCE:
            case PropertyType.URI:
            default: {
                throw new UnsupportedRepositoryOperationException("toScalar"); // todo implement toScalar
            }
        }
    }
    
    public static JsonValue toJsonValue(Value value) throws RepositoryException {
        switch (value.getType()) {
            case PropertyType.STRING: {
                return JsonAtom.string(value.getString());
            }
            case PropertyType.DOUBLE: {
                return JsonAtom.number(value.getDouble()); // todo mind NaN
            }
            case PropertyType.LONG: {
                return JsonAtom.number(value.getLong());
            }
            case PropertyType.DECIMAL: {
                return JsonAtom.number(value.getDecimal());
            }
            case PropertyType.BOOLEAN: {
                return value.getBoolean() ? JsonAtom.TRUE : JsonAtom.FALSE;
            }
            case PropertyType.BINARY:
            case PropertyType.DATE:
            case PropertyType.NAME:
            case PropertyType.PATH:
            case PropertyType.REFERENCE:
            case PropertyType.WEAKREFERENCE:
            case PropertyType.URI:
            default: {
                throw new UnsupportedRepositoryOperationException("toJson"); // todo implement toJson
            }
        }
    }

    public static JsonArray toJsonValue(Value[] values) throws RepositoryException {
        List<JsonValue> jsonValues = new ArrayList<JsonValue>();
        for (Value value : values) {
            if (value != null) {
                jsonValues.add(toJsonValue(value));
            }
        }
        return new JsonArray(jsonValues);
    }

    public static Value toValue(ValueFactory valueFactory, Scalar scalar)
            throws UnsupportedRepositoryOperationException {

        switch (scalar.getType()) {
            case Scalar.BOOLEAN:
                return valueFactory.createValue(scalar.getBoolean());
            case Scalar.LONG:
                return valueFactory.createValue(scalar.getLong());
            case Scalar.DOUBLE:
                return valueFactory.createValue(scalar.getDouble());
            case Scalar.STRING:
                return valueFactory.createValue(scalar.getString());
            default:
                throw new UnsupportedRepositoryOperationException("toValue"); // todo implement toValue
        }
    }

    public static Value[] toValues(ValueFactory valueFactory, List<Scalar> scalars)
            throws UnsupportedRepositoryOperationException {

        Value[] values = new Value[scalars.size()];
        int k = 0;
        for (Scalar scalar : scalars) {
            values[k++] = toValue(valueFactory, scalar);
        }
        return values;
    }

    public static Value toValue(ValueFactory valueFactory, JsonAtom jsonAtom) {
        switch (jsonAtom.type()) {
            case STRING: {
                return valueFactory.createValue(jsonAtom.value());  // todo decode to correct type
            }
            case NUMBER: {
                String value = jsonAtom.value();
                try {
                    return valueFactory.createValue(Long.valueOf(value));
                }
                catch (NumberFormatException e) {
                    try {
                        return valueFactory.createValue(Double.valueOf(value));
                    }
                    catch (NumberFormatException e1) {
                        return valueFactory.createValue(new BigDecimal(value));
                    }
                }
            }
            case BOOLEAN: {
                return valueFactory.createValue(Boolean.valueOf(jsonAtom.value()));
            }
            default:
                throw new IllegalArgumentException(jsonAtom.toString());
        }
    }

}
