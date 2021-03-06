/*
 * Copyright 2020 Red Hat, Inc.
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

package io.smallrye.graphql.bootstrap.datafetcher;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.inject.spi.CDI;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

import org.eclipse.microprofile.graphql.GraphQLException;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherResult;
import graphql.execution.ExecutionPath;
import graphql.language.SourceLocation;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLScalarType;
import io.smallrye.graphql.bootstrap.Argument;
import io.smallrye.graphql.bootstrap.Classes;
import io.smallrye.graphql.bootstrap.ObjectBag;
import io.smallrye.graphql.bootstrap.TransformException;
import io.smallrye.graphql.bootstrap.Transformable;
import io.smallrye.graphql.bootstrap.schema.helper.CollectionHelper;
import io.smallrye.graphql.execution.error.GraphQLExceptionWhileDataFetching;

/**
 * Fetch data using Reflection
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class ReflectionDataFetcher implements DataFetcher {
    private static final Logger LOG = Logger.getLogger(ReflectionDataFetcher.class.getName());

    private final List<Argument> arguments;
    private final boolean hasArguments;
    private final String methodName;
    private final CollectionHelper collectionHelper = new CollectionHelper();

    private final Class declaringClass;
    private final Class[] parameterClasses;

    public ReflectionDataFetcher(MethodInfo methodInfo, List<Argument> arguments) {
        this.methodName = methodInfo.name();
        this.arguments = arguments;
        this.declaringClass = loadClass(methodInfo.declaringClass().name().toString());
        this.parameterClasses = getParameterClasses(arguments);
        this.hasArguments = parameterClasses.length != 0;
    }

    @Override
    public Object get(DataFetchingEnvironment dfe) throws Exception {

        try {
            Object declaringObject = CDI.current().select(declaringClass).get();
            Class cdiClass = declaringObject.getClass();
            if (hasArguments) {
                Method method = cdiClass.getMethod(methodName, parameterClasses);
                return method.invoke(declaringObject, getArguments(dfe).toArray());
            } else {
                Method method = cdiClass.getMethod(methodName);
                return method.invoke(declaringObject, getArguments(dfe).toArray());
            }
        } catch (TransformException pe) {
            return pe.getDataFetcherResult(dfe);
        } catch (InvocationTargetException ite) {
            Throwable throwable = ite.getCause();

            if (throwable == null) {
                throw new ReflectionDataFetcherException(ite);
            } else {
                if (throwable instanceof Error) {
                    throw (Error) throwable;
                } else if (throwable instanceof GraphQLException) {
                    GraphQLException graphQLException = (GraphQLException) throwable;
                    return getPartialResult(dfe, graphQLException);
                } else {
                    throw (Exception) throwable;
                }
            }
        }
    }

    private DataFetcherResult<Object> getPartialResult(DataFetchingEnvironment dfe, GraphQLException graphQLException) {
        DataFetcherExceptionHandlerParameters handlerParameters = DataFetcherExceptionHandlerParameters
                .newExceptionParameters()
                .dataFetchingEnvironment(dfe)
                .exception(graphQLException)
                .build();

        SourceLocation sourceLocation = handlerParameters.getSourceLocation();
        ExecutionPath path = handlerParameters.getPath();
        GraphQLExceptionWhileDataFetching error = new GraphQLExceptionWhileDataFetching(path, graphQLException,
                sourceLocation);

        return DataFetcherResult.newResult()
                .data(graphQLException.getPartialResults())
                .error(error)
                .build();

    }

    private ArrayList getArguments(DataFetchingEnvironment dfe) throws GraphQLException {
        ArrayList argumentObjects = new ArrayList();
        for (Argument a : arguments) {
            Object argument = getArgument(dfe, a.getName());
            argumentObjects.add(toArgumentInputParameter(argument, a));
        }
        return argumentObjects;
    }

    private Object toArgumentInputParameter(Object argumentValue, Argument a) throws GraphQLException {
        Type type = a.getType();

        if (argumentValue != null) {
            Type.Kind kind = type.kind();
            if (kind.equals(Type.Kind.PRIMITIVE)) {
                return handlePrimative(argumentValue, a);
            } else if (kind.equals(Type.Kind.ARRAY)) {
                return handleArray(argumentValue, a);
            } else if (kind.equals(Type.Kind.PARAMETERIZED_TYPE) && isOptionalType(a.getArgumentClass())) {
                return handleOptional(argumentValue);
            } else if (kind.equals(Type.Kind.PARAMETERIZED_TYPE)) {
                return handleCollection(argumentValue, a);
            } else if (kind.equals(Type.Kind.CLASS)) {
                return handleClass(argumentValue, a);
            } else {
                return handleDefault(argumentValue, a, "Not sure what to do with this kind");
            }
        }
        return handleDefault(argumentValue, a, "Argument is NULL");
    }

    private Object handlePrimative(Object argumentValue, Argument a) {
        // First make sure we have a primative type
        Class clazz = a.getArgumentClass();
        Class givenClass = argumentValue.getClass();
        if (!givenClass.isPrimitive()) {
            givenClass = Classes.toPrimativeClassType(givenClass);
        }
        if (givenClass.equals(clazz)) {
            return argumentValue;
        } else {
            return toScalar(argumentValue, a, clazz);
        }
    }

    private Object handleClass(Object argumentValue, Argument argument) throws GraphQLException {
        Class clazz = argument.getArgumentClass();
        Class givenClass = argumentValue.getClass();
        if (givenClass.equals(clazz)) {
            return argumentValue;
        } else if (Map.class.isAssignableFrom(argumentValue.getClass())) {
            return mapToPojo(Map.class.cast(argumentValue), argument);
        } else if (givenClass.equals(String.class)) {
            // We got a String, but not expecting one. Lets bind to Pojo with JsonB or transformation
            // This happens with @DefaultValue and Transformable (Passthrough) Scalars
            return objectToPojo(argumentValue, argument);
        } else {
            return toScalar(argumentValue, argument, clazz);
        }
    }

    private <T> Object handleArray(Object argumentValue, Argument a) throws GraphQLException {
        Class clazz = a.getArgumentClass();
        Type type = a.getType();
        Collection givenCollection = (Collection) argumentValue;
        Type typeInCollection = type.asArrayType().component();

        List convertedList = new ArrayList();

        Argument argumentInCollection = new Argument(typeInCollection.name().local(), typeInCollection,
                a.getAnnotations());

        for (Object o : givenCollection) {
            convertedList.add(toArgumentInputParameter(o, argumentInCollection));
        }

        return convertedList.toArray((T[]) Array.newInstance(clazz.getComponentType(), givenCollection.size()));
    }

    private Object handleCollection(Object argumentValue, Argument a) throws GraphQLException {
        Class clazz = a.getArgumentClass();
        Type type = a.getType();
        Collection convertedList = collectionHelper.getCorrectCollectionType(clazz);

        Collection givenCollection = (Collection) argumentValue;

        Type typeInCollection = type.asParameterizedType().arguments().get(0);
        Argument argumentInCollection = new Argument(typeInCollection.name().local(), typeInCollection,
                a.getAnnotations());

        for (Object o : givenCollection) {
            convertedList.add(toArgumentInputParameter(o, argumentInCollection));
        }

        return convertedList;
    }

    private Object handleOptional(Object argumentValue) {
        // Check the type and maybe apply transformation
        if (argumentValue == null) {
            return Optional.empty();
        } else {
            Collection givenCollection = (Collection) argumentValue;
            if (givenCollection.isEmpty()) {
                return Optional.empty();
            } else {
                Object o = givenCollection.iterator().next();
                return Optional.of(o);
            }
        }
    }

    private boolean isOptionalType(Class type) {
        return type.equals(Optional.class);
    }

    private Object handleDefault(Object argumentValue, Argument argument, String message) {
        if (argumentValue == null)
            return null;
        Type type = argument.getType();
        LOG.warn(message + " | argument [" + argumentValue + "] of kind [" + argumentValue.getClass().getName()
                + "] but expecting kind [" + type.kind().name() + "]");
        return argumentValue;
    }

    private Object getArgument(DataFetchingEnvironment dfe, String name) {
        Object argument = dfe.getArgument(name);
        if (argument != null) {
            return argument;
        }
        Object source = dfe.getSource();
        if (source != null) {
            return source;
        }
        return null;
    }

    private Object toScalar(Object input, Argument argument, Class clazz) {
        GraphQLScalarType scalar = getScalarType(argument.getType());
        if (scalar != null) {
            try {
                // For transformable scalars.
                if (Transformable.class.isInstance(scalar)) {
                    Transformable transformable = Transformable.class.cast(scalar);
                    input = transformable.transform(input, argument);
                }
                return Classes.stringToScalar(input.toString(), clazz);

            } catch (NumberFormatException nfe) {
                throw new TransformException(nfe, scalar, argument.getName(), input.toString());
            }
        } else {
            return handleDefault(input, argument, "Expected type [" + clazz.getName() + "]");
        }
    }

    private Object objectToPojo(Object input, Argument argument) {
        Class clazz = argument.getArgumentClass();
        // For Objects (from @DefaultValue)
        Jsonb jsonb = getJsonbForType(argument.getType());
        if (jsonb != null) {
            return jsonb.fromJson(input.toString(), clazz);
        }
        // For transformable scalars.
        GraphQLScalarType scalar = getScalarType(argument.getType());
        if (scalar != null && Transformable.class.isInstance(scalar)) {
            Transformable transformable = Transformable.class.cast(scalar);
            Object transformed = transformable.transform(input, argument);
            return clazz.cast(transformed);
        }
        return input;
    }

    private Object mapToPojo(Map m, Argument argument) throws GraphQLException {
        String jsonString = toJsonString(m, argument);
        Jsonb jsonb = getJsonbForType(argument.getType());
        if (jsonb != null) {
            return jsonb.fromJson(jsonString, argument.getArgumentClass());
        }
        return m;
    }

    private String toJsonString(Map inputMap, Argument argument) throws GraphQLException {
        DotName classDotName = DotName.createSimple(argument.getArgumentClass().getName());
        try (Jsonb jsonb = JsonbBuilder.create()) {

            // See if there are any formatting type annotations of this class definition and if any of the input fields needs formatting.
            if (ObjectBag.ARGUMENT_MAP.containsKey(classDotName) &&
                    hasInputFieldsThatNeedsFormatting(classDotName, inputMap)) {
                Map<String, Argument> fieldsThatShouldBeFormatted = ObjectBag.ARGUMENT_MAP.get(classDotName);
                Set<Map.Entry> inputValues = inputMap.entrySet();
                for (Map.Entry keyValue : inputValues) {
                    String key = String.valueOf(keyValue.getKey());
                    if (fieldsThatShouldBeFormatted.containsKey(key)) {
                        Argument fieldArgument = fieldsThatShouldBeFormatted.get(key);
                        Object o = toArgumentInputParameter(keyValue.getValue(), fieldArgument);
                        inputMap.put(keyValue.getKey(), o);
                    }
                }
            }

            return jsonb.toJson(inputMap);
        } catch (Exception e) {
            LOG.warn("Could not close Jsonb");
            return null;
        }
    }

    private boolean hasInputFieldsThatNeedsFormatting(DotName className, Map input) {
        Set<String> fieldsThatShouldBeFormatted = ObjectBag.ARGUMENT_MAP.get(className).keySet();
        for (String fieldName : fieldsThatShouldBeFormatted) {
            if (input.containsKey(fieldName)) {
                return true;
            }
        }
        return false;
    }

    private Jsonb getJsonbForType(Type type) {
        if (ObjectBag.INPUT_JSON_MAP.containsKey(type.name())) {
            return ObjectBag.INPUT_JSON_MAP.get(type.name());
        }
        return null;
    }

    private GraphQLScalarType getScalarType(Type type) {
        if (ObjectBag.SCALAR_MAP.containsKey(type.name())) {
            return ObjectBag.SCALAR_MAP.get(type.name());
        }
        return null;
    }

    private Class loadClass(String className) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            throw new ReflectionDataFetcherException("Could not find class [" + className + "]", ex);
        }
    }

    private Class[] getParameterClasses(List<Argument> arguments) {
        List<Class> cl = new ArrayList<>();
        for (Argument argument : arguments) {
            cl.add(argument.getArgumentClass());
        }
        return cl.toArray(new Class[] {});
    }

}
