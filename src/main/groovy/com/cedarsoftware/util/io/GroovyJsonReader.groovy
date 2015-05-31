package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import static java.util.Map.Entry

/**
 * Read an object graph in JSON format and make it available in Groovy objects, or
 * in a "Map of Maps." (untyped representation).  This code handles cyclic references
 * and can deserialize any Object graph without requiring a class to be 'Serializeable'
 * or have any specific methods on it.  It will handle classes with non public constructors.
 * <br/><br/>
 * Usages:
 * <ul><li>
 * Call the static method: {@code GroovyJsonReader.jsonToGroovy(String json)}.  This will
 * return a typed Groovy object graph.</li>
 * <li>
 * Call the static method: {@code GroovyJsonReader.jsonToMaps(String json)}.  This will
 * return an untyped object representation of the JSON String as a Map of Maps, where
 * the fields are the Map keys, and the field values are the associated Map's values.  You can
 * call the GroovyJsonWriter.objectToJson() method with the returned Map, and it will serialize
 * the Graph into the identical JSON stream from which it was read.
 * <li>
 * Instantiate the GroovyJsonReader with an InputStream: {@code GroovyJsonReader(InputStream in)} and then call
 * {@code readObject()}.  Cast the return value of readObject() to the Groovy class that was the root of
 * the graph.
 * </li>
 * <li>
 * Instantiate the GroovyJsonReader with an InputStream: {@code GroovyJsonReader(InputStream in, true)} and then call
 * {@code readObject()}.  The return value will be a Map of Maps.
 * </li></ul><br/>
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br/>
 *         Copyright (c) Cedar Software LLC
 *         <br/><br/>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br/><br/>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br/><br/>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
class GroovyJsonReader implements Closeable
{
    static final String USE_MAPS = "USE_MAPS"               // If set, the read-in JSON will be turned into a Map of Maps (JsonObject) representation
    static final String UNKNOWN_OBJECT = "UNKNOWN_OBJECT";   // What to do when an object is found and 'type' cannot be determined.
    static final String JSON_READER = "JSON_READER";         // Pointer to 'this' (automatically placed in the Map)
    static final String TYPE_NAME_MAP = "TYPE_NAME_MAP"     // If set, this map will be used when writing @type values - allows short-hand abbreviations type names
    static final String TYPE_NAME_MAP_REVERSE = "TYPE_NAME_MAP_REVERSE" // This map is the reverse of the TYPE_NAME_MAP (value -> key)

    protected static final ConcurrentMap<Class, JsonTypeReaderBase> readers = [
            (String.class):new Readers.StringReader(),
            (Date.class):new Readers.DateReader(),
            (BigInteger.class):new Readers.BigIntegerReader(),
            (BigDecimal.class):new Readers.BigDecimalReader(),
            (java.sql.Date.class):new Readers.SqlDateReader(),
            (Timestamp.class):new Readers.TimestampReader(),
            (Calendar.class):new Readers.CalendarReader(),
            (TimeZone.class):new Readers.TimeZoneReader(),
            (Locale.class):new Readers.LocaleReader(),
            (Class.class):new Readers.ClassReader(),
            (StringBuilder.class):new Readers.StringBuilderReader(),
            (StringBuffer.class):new Readers.StringBufferReader()
    ] as ConcurrentHashMap
    protected static final Set<Class> notCustom = new HashSet<>()
    private static final Map<Class, ClassFactory> factory = new ConcurrentHashMap<>();
    private final Map<Long, JsonObject> objsRead = new HashMap<>()
    private final FastPushbackReader input

    static final ThreadLocal<FastPushbackReader> threadInput = new ThreadLocal<>()
    // _args is using ThreadLocal so that static inner classes can have access to them
    static final ThreadLocal<Map<String, Object>> _args = new ThreadLocal<Map<String, Object>>() {
        Map<String, Object> initialValue()
        {
            return new HashMap<>();
        }
    }

    static
    {
        ClassFactory colFactory = new CollectionFactory()
        assignInstantiator(Collection.class, colFactory)
        assignInstantiator(List.class, colFactory)
        assignInstantiator(Set.class, colFactory)
        assignInstantiator(SortedSet.class, colFactory)

        ClassFactory mapFactory = new MapFactory()
        assignInstantiator(Map.class, mapFactory)
        assignInstantiator(SortedMap.class, mapFactory)
    }

    interface ClassFactory
    {
        Object newInstance(Class c)
    }

    /**
     * For difficult to instantiate classes, you can add your own ClassFactory
     * which will be called when the passed in class 'c' is encountered.  Your
     * ClassFactory will be called with newInstance(c) and your factory is expected
     * to return a new instance of 'c'.
     *
     * This API is an 'escape hatch' to allow ANY object to be instantiated by JsonReader
     * and is useful when you encounter a class that JsonReader cannot instantiate using its
     * internal exhausting attempts (trying all constructors, varying arguments to them, etc.)
     */
    static void assignInstantiator(Class c, ClassFactory f)
    {
        factory[c] = f
    }

    /**
     * @return The arguments used to configure the JsonReader.  These are thread local.
     */
    static Map getArgs()
    {
        return _args.get();
    }

    /**
     * Use to create new instances of collection interfaces (needed for empty collections)
     */
    static class CollectionFactory implements ClassFactory
    {
        Object newInstance(Class c)
        {
            if (List.class.isAssignableFrom(c))
            {
                return new ArrayList()
            }
            else if (SortedSet.class.isAssignableFrom(c))
            {
                return new TreeSet()
            }
            else if (Set.class.isAssignableFrom(c))
            {
                return new LinkedHashSet()
            }
            else if (Collection.class.isAssignableFrom(c))
            {
                return new ArrayList()
            }
            throw new RuntimeException("CollectionFactory handed Class for which it was not expecting: " + c.getName())
        }
    }

    /**
     * Use to create new instances of Map interfaces (needed for empty Maps)
     */
    static class MapFactory implements ClassFactory
    {
        Object newInstance(Class c)
        {
            if (SortedMap.class.isAssignableFrom(c))
            {
                return new TreeMap()
            }
            else if (Map.class.isAssignableFrom(c))
            {
                return [:]
            }
            throw new RuntimeException("MapFactory handed Class for which it was not expecting: " + c.getName())
        }
    }

    static void addReader(Class c, JsonTypeReaderBase reader)
    {
        for (Entry entry : readers.entrySet())
        {
            if (entry.key == c)
            {
                entry.value = reader
                return
            }
        }
        readers[c] = reader
    }

    static void addNotCustomReader(Class c)
    {
        notCustom.add(c)
    }

    /**
     * Convert the passed in JSON string into a Groovy object graph.
     *
     * @param json String JSON input
     * @return Groovy object graph matching JSON input
     */
    static Object jsonToGroovy(String json)
    {
        return jsonToGroovy(json, [:])
    }

    /**
     * Convert the passed in JSON string into a Groovy object graph.
     *
     * @param json String JSON input
     * @return Groovy object graph matching JSON input
     */
    static Object jsonToGroovy(String json, Map<String, Object> optionalArgs)
    {
        optionalArgs[USE_MAPS] = false
        ByteArrayInputStream ba = new ByteArrayInputStream(json.getBytes("UTF-8"))
        GroovyJsonReader jr = new GroovyJsonReader(ba, optionalArgs)
        Object obj = jr.readObject()
        jr.close()
        return obj
    }

    /**
     * Convert the passed in JSON string into a Groovy object graph
     * that consists solely of Groovy Maps where the keys are the
     * fields and the values are primitives or other Maps (in the
     * case of objects).
     *
     * @param json String JSON input
     * @return Groovy object graph of Maps matching JSON input,
     *         or null if an error occurred.
     */
    static Map jsonToMaps(String json)
    {
        return jsonToMaps(json, [:])
    }

    /**
     * Convert the passed in JSON string into a Groovy object graph
     * that consists solely of Groovy Maps where the keys are the
     * fields and the values are primitives or other Maps (in the
     * case of objects).
     *
     * @param json String JSON input
     * @param optionalArgs Map of additional arguments to control reading
     * @return Groovy object graph of Maps matching JSON input,
     *         or null if an error occurred.
     */
    static Map jsonToMaps(String json, Map<String, Object> optionalArgs)
    {
        optionalArgs[USE_MAPS] = true
        ByteArrayInputStream ba = new ByteArrayInputStream(json.getBytes("UTF-8"))
        GroovyJsonReader jr = new GroovyJsonReader(ba, true)
        Object ret = jr.readObject()
        jr.close()

        if (ret instanceof Map)
        {
            return (Map) ret
        }

        if (ret != null && ret.getClass().isArray())
        {
            JsonObject retMap = new JsonObject()
            retMap['@items'] = ret
            return retMap

        }
        JsonObject retMap = new JsonObject()
        retMap['@items'] = [ret] as Object[]
        return retMap
    }

    GroovyJsonReader()
    {
        input = null
        getArgs().put(USE_MAPS, false)
    }

    GroovyJsonReader(InputStream inp)
    {
        this(inp, false)
    }

    GroovyJsonReader(InputStream inp, boolean useMaps)
    {
        this(inp, makeArgMap([:], useMaps))
    }

    GroovyJsonReader(InputStream inp, Map<String, Object> optionalArgs)
    {
        Map<String, Object> args = getArgs()
        args.clear()
        args.putAll(optionalArgs)
        args[JSON_READER] = this
        Map<String, String> typeNames = (Map<String, String>) args[TYPE_NAME_MAP]

        if (typeNames != null)
        {   // Reverse the Map (this allows the users to only have a Map from type to short-hand name,
            // and not keep a 2nd map from short-hand name to type.
            Map<String, String> typeNameMap = [:]
            for (Entry<String, String> entry : typeNames.entrySet())
            {
                typeNameMap.put(entry.getValue(), entry.getKey());
            }
            args[TYPE_NAME_MAP_REVERSE] = typeNameMap;   // replace with our reversed Map.
        }

        try
        {
            input = new FastPushbackReader(new BufferedReader(new InputStreamReader(inp, "UTF-8")))
            threadInput.set(input)
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException("Your JVM does not support UTF-8.  Get a better JVM.", e)
        }
    }

    // This method is needed to get around the fact that 'this()' has to be the first method of a constructor.
    static Map makeArgMap(Map<String, Object> args, boolean useMaps)
    {
        args[USE_MAPS] = useMaps
        return args
    }

    /**
     * Finite State Machine (FSM) used to parse the JSON input into
     * JsonObject's (Maps).  Then, if requested, the JsonObjects are
     * converted into Groovy instances.
     *
     * @return Groovy Object graph constructed from InputStream supplying
     *         JSON serialized content.
     */
    Object readObject()
    {
        JsonParser parser = new JsonParser(input, objsRead, getArgs())
        JsonObject root = new JsonObject()
        Object o = parser.readValue(root)
        if (JsonParser.EMPTY_OBJECT.is(o))
        {
            return new JsonObject()
        }

        Object graph
        if (o instanceof Object[])
        {
            root.type = ([] as Object[]).class.getName()
            root.target = o
            root['@items'] = o
            graph = convertParsedMapsToGroovy(root)
        }
        else if (o instanceof JsonObject)
        {
            graph = convertParsedMapsToGroovy((JsonObject) o)
        }
        else
        {
            graph = o
        }
        // Allow a complete 'Map' return (Javascript style)
        if (useMaps())
        {
            return o
        }
        return graph
    }

    /**
     * Convert a root JsonObject that represents parsed JSON, into
     * an actual Groovy object.
     * @param root JsonObject instance that was the root object from the
     * JSON input that was parsed in an earlier call to JsonReader.
     * @return a typed Groovy instance that was serialized into JSON.
     */
    Object jsonObjectsToGroovy(JsonObject root)
    {
        getArgs().put(USE_MAPS, false);
        return convertParsedMapsToGroovy(root)
    }

    protected boolean useMaps()
    {
        return Boolean.TRUE.equals(args.get(USE_MAPS));
    }

    /**
     * This method converts a root Map, (which contains nested Maps
     * and so forth representing a Groovy Object graph), to a Groovy
     * object instance.  The root map came from using the JsonReader
     * to parse a JSON graph (using the API that puts the graph
     * into Maps, not the typed representation).
     * @param root JsonObject instance that was the root object from the
     * JSON input that was parsed in an earlier call to JsonReader.
     * @return a typed Groovy instance that was serialized into JSON.
     */
    protected Object convertParsedMapsToGroovy(JsonObject root)
    {
        Resolver resolver = useMaps() ? new MapResolver(objsRead) : new ObjectResolver(objsRead)
        resolver.createGroovyObjectInstance(Object.class, root)
        Object graph = resolver.convertMapsToObjects((JsonObject<String, Object>) root)
        resolver.cleanup()
        return graph
    }

    /**
     * Fetch enum value (may need to try twice, due to potential 'name' field shadowing by enum subclasses
     */
    private static Object getEnum(Class c, JsonObject jsonObj)
    {
        try
        {
            return Enum.valueOf(c, (String) jsonObj['name'])
        }
        catch (Exception e)
        {   // In case the enum class has it's own 'name' member variable (shadowing the 'name' variable on Enum)
            return Enum.valueOf(c, (String) jsonObj['java.lang.Enum.name'])
        }
    }

    void close()
    {
        try
        {
            threadInput.remove()
            if (input != null)
            {
                input.close()
            }
        }
        catch (Exception e)
        {
            throw new JsonIoException("Error close stream:", e)
        }
    }

    static Object newInstance(Class c)
    {
        if (factory.containsKey(c))
        {
            return factory.get(c).newInstance(c);
        }
        return MetaUtils.newInstanceImpl(c);
    }

    private static String getErrorMessage(String msg)
    {
        if (threadInput.get() != null)
        {
            return msg + "\nLast read: " + getLastReadSnippet() + "\nline: " + threadInput.get().line + ", col: " + threadInput.get().col
        }
        return msg
    }

    static Object error(String msg)
    {
        throw new JsonIoException(getErrorMessage(msg))
    }

    static Object error(String msg, Exception e)
    {
        throw new JsonIoException(getErrorMessage(msg), e)
    }

    private static String getLastReadSnippet()
    {
        if (threadInput.get() != null)
        {
            return threadInput.get().getLastSnippet()
        }
        return ""
    }
}
