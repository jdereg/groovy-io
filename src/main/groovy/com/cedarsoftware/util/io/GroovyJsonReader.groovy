package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap

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
    protected static final Map<Class, JsonTypeReader> readers = [
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
    private boolean useMaps = false

    static final ThreadLocal<FastPushbackReader> threadInput = new ThreadLocal<>()

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

    public interface ClassFactory
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
    public static void assignInstantiator(Class c, ClassFactory f)
    {
        factory[c] = f
    }

    /**
     * Use to create new instances of collection interfaces (needed for empty collections)
     */
    public static class CollectionFactory implements ClassFactory
    {
        public Object newInstance(Class c)
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
    public static class MapFactory implements ClassFactory
    {
        public Object newInstance(Class c)
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

    public static void addReader(Class c, JsonTypeReader reader)
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

    public static void addNotCustomReader(Class c)
    {
        notCustom.add(c)
    }

    /**
     * Convert the passed in JSON string into a Groovy object graph.
     *
     * @param json String JSON input
     * @return Groovy object graph matching JSON input
     * @throws java.io.IOException If an I/O error occurs
     */
    public static Object jsonToGroovy(String json) throws IOException
    {
        ByteArrayInputStream ba = new ByteArrayInputStream(json.getBytes("UTF-8"))
        GroovyJsonReader jr = new GroovyJsonReader(ba, false)
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
     * @throws java.io.IOException If an I/O error occurs
     */
    public static Map jsonToMaps(String json) throws IOException
    {
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

    public GroovyJsonReader()
    {
        useMaps = false
        input = null
    }

    public GroovyJsonReader(InputStream inp)
    {
        this(inp, false)
    }

    public GroovyJsonReader(InputStream inp, boolean useMaps)
    {
        this.useMaps = useMaps
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

    /**
     * Finite State Machine (FSM) used to parse the JSON input into
     * JsonObject's (Maps).  Then, if requested, the JsonObjects are
     * converted into Groovy instances.
     *
     * @return Groovy Object graph constructed from InputStream supplying
     *         JSON serialized content.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    public Object readObject() throws IOException
    {
        JsonParser parser = new JsonParser(input, objsRead, useMaps)
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
        if (useMaps)
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
    public Object jsonObjectsToGroovy(JsonObject root) throws IOException
    {
        useMaps = false
        return convertParsedMapsToGroovy(root)
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
    protected Object convertParsedMapsToGroovy(JsonObject root) throws IOException
    {
        Resolver resolver = useMaps ? new MapResolver(objsRead, true) : new ObjectResolver(objsRead, false)
        resolver.createGroovyObjectInstance(Object.class, root)
        Object graph = resolver.convertMapsToObjects((JsonObject<String, Object>) root)
        resolver.cleanup()
        return graph
    }

    /**
     * Convert an input JsonObject map (known to represent a Map.class or derivative) that has regular keys and values
     * to have its keys placed into @keys, and its values placed into @items.
     * @param map Map to convert
     */
    private static void convertMapToKeysItems(JsonObject map)
    {
        if (!map.containsKey("@keys") && !map.containsKey("@ref"))
        {
            Object[] keys = new Object[map.keySet().size()]
            Object[] values = new Object[map.keySet().size()]
            int i=0
            for (Object e : map.entrySet())
            {
                Entry entry = (Entry)e
                keys[i] = entry.key
                values[i] = entry.value
                i++
            }
            String saveType = map.type
            map.clear()
            map.type = saveType
            map['@keys'] = keys
            map['@items'] = values
        }
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

    public void close()
    {
        try
        {
            threadInput.remove()
            if (input != null)
            {
                input.close()
            }
        }
        catch (IOException ignored) { }
    }

    static Object newInstance(Class c) throws IOException
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

    static Object error(String msg) throws IOException
    {
        throw new IOException(getErrorMessage(msg))
    }

    static Object error(String msg, Exception e) throws IOException
    {
        throw new IOException(getErrorMessage(msg), e)
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
