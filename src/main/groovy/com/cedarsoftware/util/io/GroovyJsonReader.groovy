package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
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
    private static final Map<Class, JsonTypeReader> readers = [
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
    ]
    private static final Set<Class> notCustom = new HashSet<>()
    private static final Map<Class, ClassFactory> factory = [:]
    private static final Map<Class, JsonTypeReader> readerCache = new ConcurrentHashMap<>()
    private static final NullClass nullReader = new NullClass()
    private final Map<Long, JsonObject> _objsRead = [:]
    private final Collection<UnresolvedReference> unresolvedRefs = []
    private final Collection<Object[]> prettyMaps = []
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

    protected static Object readIfMatching(Object o, Class compType, Deque<JsonObject<String, Object>> stack) throws IOException
    {
        if (o == null)
        {
            error("Bug in json-io, null must be checked before calling this method.")
        }

        if (notCustom.contains(o.getClass()))
        {
            return null
        }

        if (compType != null)
        {
            if (notCustom.contains(compType))
            {
                return null
            }
        }

        boolean isJsonObject = o instanceof JsonObject
        if (!isJsonObject && compType == null)
        {   // If not a JsonObject (like a Long that represents a date, then compType must be set)
            return null
        }

        Class c
        boolean needsType = false

        // Set up class type to check against reader classes (specified as @type, or jObj.target, or compType)
        if (isJsonObject)
        {
            JsonObject jObj = (JsonObject) o
            if (jObj.containsKey("@ref"))
            {
                return null
            }

            if (jObj.target == null)
            {   // '@type' parameter used
                String typeStr = null
                try
                {
                    Object type = jObj.type
                    if (type != null)
                    {
                        typeStr = (String) type
                        c = MetaUtils.classForName((String) type)
                    }
                    else
                    {
                        if (compType != null)
                        {
                            c = compType
                            needsType = true
                        }
                        else
                        {
                            return null
                        }
                    }
                }
                catch(Exception e)
                {
                    return error("Class listed in @type [" + typeStr + "] is not found", e)
                }
            }
            else
            {   // Type inferred from target object
                c = jObj.getTargetClass()
            }
        }
        else
        {
            c = compType
        }

        JsonTypeReader closestReader = getCustomReader(c)

        if (closestReader == null)
        {
            return null
        }

        if (needsType && isJsonObject)
        {
            ((JsonObject)o).type = c.getName()
        }
        return closestReader.read(o, stack)
    }

    static class NullClass implements JsonTypeReader
    {
        Object read(Object jOb, Deque<JsonObject<String, Object>> stack) throws IOException
        {
            return null
        }
    }

    private static JsonTypeReader getCustomReader(Class c)
    {
        JsonTypeReader reader = readerCache[c]
        if (reader == null)
        {
            synchronized (readerCache)
            {
                reader = readerCache[c]
                if (reader == null)
                {
                    reader = getForceCustomReader(c)
                    readerCache[c] = reader
                }
            }
        }
        return reader.is(nullReader) ? null : reader
    }
    private static JsonTypeReader getForceCustomReader(Class c)
    {
		JsonTypeReader closestReader = nullReader
        int minDistance = Integer.MAX_VALUE

        for (Entry<Class, JsonTypeReader> entry : readers.entrySet())
        {
            Class clz = entry.key
            if (clz == c)
            {
                return entry.value
            }
            int distance = MetaUtils.getDistance(clz, c)
            if (distance < minDistance)
            {
                minDistance = distance
                closestReader = entry.value
            }
        }
		return closestReader
	}

    /**
     * UnresolvedReference is created to hold a logical pointer to a reference that
     * could not yet be loaded, as the @ref appears ahead of the referenced object's
     * definition.  This can point to a field reference or an array/Collection element reference.
     */
    private static final class UnresolvedReference
    {
        private final JsonObject referencingObj
        private String field
        private final long refId
        private int index = -1

        UnresolvedReference(JsonObject referrer, String fld, long id)
        {
            referencingObj = referrer
            field = fld
            refId = id
        }

        UnresolvedReference(JsonObject referrer, int idx, long id)
        {
            referencingObj = referrer
            index = idx
            refId = id
        }
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
        JsonParser parser = new JsonParser(input, _objsRead, useMaps)
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
        createGroovyInstance(Object.class, root)
        Object graph = convertMapsToObjects((JsonObject<String, Object>) root)
        patchUnresolvedReferences()
        rehashMaps()
        _objsRead.clear()
        unresolvedRefs.clear()
        prettyMaps.clear()
        return graph
    }

    /**
     * Walk a JsonObject (Map of String keys to values) and return the
     * Groovy object equivalent filled in as best as possible (everything
     * except unresolved reference fields or unresolved array/collection elements).
     *
     * @param root JsonObject reference to a Map-of-Maps representation of the JSON
     *             input after it has been completely read.
     * @return Properly constructed, typed, Groovy object graph built from a Map
     *         of Maps representation (JsonObject root).
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected Object convertMapsToObjects(JsonObject<String, Object> root) throws IOException
    {
        Deque<JsonObject<String, Object>> stack = new ArrayDeque<>()
        stack.addFirst(root)
        final boolean useMapsLocal = useMaps

        while (!stack.isEmpty())
        {
            JsonObject<String, Object> jsonObj = stack.removeFirst()

            if (useMapsLocal)
            {
                if (jsonObj.isArray() || jsonObj.isCollection())
                {
                    traverseCollectionNoObj(stack, jsonObj)
                }
                else if (jsonObj.isMap())
                {
                    traverseMap(stack, jsonObj)
                }
                else
                {
                    traverseFieldsNoObj(stack, jsonObj)
                }
            }
            else
            {
                if (jsonObj.isArray())
                {
                    traverseArray(stack, jsonObj)
                }
                else if (jsonObj.isCollection())
                {
                    traverseCollection(stack, jsonObj)
                }
                else if (jsonObj.isMap())
                {
                    traverseMap(stack, jsonObj)
                }
                else
                {
                    traverseFields(stack, jsonObj)
                }

                // Reduce heap footprint during processing
                jsonObj.clear()
            }
        }
        return root.target
    }

    /**
     * Traverse the JsonObject associated to an array (of any type).  Convert and
     * assign the list of items in the JsonObject (stored in the @items field)
     * to each array element.  All array elements are processed excluding elements
     * that reference an unresolved object.  These are filled in later.
     *
     * @param stack   a Stack (Deque) used to support graph traversal.
     * @param jsonObj a Map-of-Map representation of the JSON input stream.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected void traverseArray(Deque<JsonObject<String, Object>> stack, JsonObject<String, Object> jsonObj) throws IOException
    {
        final int len = jsonObj.getLength()
        if (len == 0)
        {
            return
        }

        final Class compType = jsonObj.getComponentType()

        if (char.class == compType)
        {
            return
        }

        if (byte.class == compType)
        {   // Handle byte[] special for performance boost.
            jsonObj.moveBytesToMate()
            jsonObj.clearArray()
            return
        }

        final boolean isPrimitive = MetaUtils.isPrimitive(compType)
        final Object array = jsonObj.getTarget()
        final Object[] items =  jsonObj.getArray()

        for (int i=0; i < len; i++)
        {
            final Object element = items[i]

            Object special
            if (element == null)
            {
                Array.set(array, i, null)
            }
            else if (JsonParser.EMPTY_OBJECT.is(element))
            {    // Use either explicitly defined type in ObjectMap associated to JSON, or array component type.
                Object arrayElement = createGroovyInstance(compType, new JsonObject())
                Array.set(array, i, arrayElement)
            }
            else if ((special = readIfMatching(element, compType, stack)) != null)
            {
                Array.set(array, i, special)
            }
            else if (isPrimitive)
            {   // Primitive component type array
                Array.set(array, i, MetaUtils.newPrimitiveWrapper(compType, element))
            }
            else if (element.getClass().isArray())
            {   // Array of arrays
                if (([] as char[]).class == compType)
                {   // Specially handle char[] because we are writing these
                    // out as UTF-8 strings for compactness and speed.
                    Object[] jsonArray = (Object[]) element
                    if (jsonArray.length == 0)
                    {
                        Array.set(array, i, [] as char[])
                    }
                    else
                    {
                        final String value = (String) jsonArray[0]
                        final int numChars = value.length()
                        final char[] chars = new char[numChars]
                        for (int j = 0; j < numChars; j++)
                        {
                            chars[j] = value.charAt(j)
                        }
                        Array.set(array, i, chars)
                    }
                }
                else
                {
                    JsonObject<String, Object> jsonObject = new JsonObject<>()
                    jsonObject['@items'] = element
                    Array.set(array, i, createGroovyInstance(compType, jsonObject))
                    stack.addFirst(jsonObject)
                }
            }
            else if (element instanceof JsonObject)
            {
                JsonObject<String, Object> jsonObject = (JsonObject<String, Object>) element
                Long ref = (Long) jsonObject['@ref']

                if (ref != null)
                {    // Connect reference
                    JsonObject refObject = getReferencedObj(ref)
                    if (refObject.target != null)
                    {   // Array element with @ref to existing object
                        Array.set(array, i, refObject.target)
                    }
                    else
                    {    // Array with a forward @ref as an element
                        unresolvedRefs.add(new UnresolvedReference(jsonObj, i, ref))
                    }
                }
                else
                {    // Convert JSON HashMap to Groovy Object instance and assign values
                    Object arrayElement = createGroovyInstance(compType, jsonObject)
                    Array.set(array, i, arrayElement)
                    if (!MetaUtils.isLogicalPrimitive(arrayElement.getClass()))
                    {    // Skip walking primitives, primitive wrapper classes, Strings, and Classes
                        stack.addFirst(jsonObject)
                    }
                }
            }
            else
            {
                if (element instanceof String && "".equals(((String) element).trim()) && compType != String.class && compType != Object.class)
                {   // Allow an entry of "" in the array to set the array element to null, *if* the array type is NOT String[] and NOT Object[]
                    Array.set(array, i, null)
                }
                else
                {
                    Array.set(array, i, element)
                }
            }
        }
        jsonObj.clearArray()
    }

    private JsonObject getReferencedObj(Long ref) throws IOException
    {
        JsonObject refObject = _objsRead[(ref)]
        if (refObject == null)
        {
            error("Forward reference @ref: " + ref + ", but no object defined (@id) with that value")
        }
        return refObject
    }

    /**
     * Process java.util.Collection and it's derivatives.  Collections are written specially
     * so that the serialization does not expose the Collection's internal structure, for
     * example a TreeSet.  All entries are processed, except unresolved references, which
     * are filled in later.  For an indexable collection, the unresolved references are set
     * back into the proper element location.  For non-indexable collections (Sets), the
     * unresolved references are added via .add().
     * @param stack   a Stack (Deque) used to support graph traversal.
     * @param jsonObj a Map-of-Map representation of the JSON input stream.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected void traverseCollectionNoObj(Deque<JsonObject<String, Object>> stack, JsonObject jsonObj) throws IOException
    {
        Object[] items = jsonObj.getArray()
        if (items == null || items.length == 0)
        {
            return
        }

        int idx = 0
        List copy = new ArrayList(items.length)

        for (Object element : items)
        {
            if (JsonParser.EMPTY_OBJECT.is(element))
            {
                copy.add(new JsonObject())
                continue
            }

            copy.add(element)

            if (element instanceof Object[])
            {   // array element inside Collection
                JsonObject<String, Object> jsonObject = new JsonObject<>()
                jsonObject['@items'] = element
                stack.addFirst(jsonObject)
            }
            else if (element instanceof JsonObject)
            {
                JsonObject<String, Object> jsonObject = (JsonObject<String, Object>) element
                Long ref = (Long) jsonObject['@ref']

                if (ref != null)
                {    // connect reference
                    JsonObject refObject = getReferencedObj(ref)
                    copy.set(idx, refObject)
                }
                else
                {
                    stack.addFirst(jsonObject)
                }
            }
            idx++
        }
        jsonObj.target = null  // don't waste space (used for typed return, not generic Map return)

        for (int i=0; i < items.length; i++)
        {
            items[i] = copy[i]
        }
    }

    /**
     * Process java.util.Collection and it's derivatives.  Collections are written specially
     * so that the serialization does not expose the Collection's internal structure, for
     * example a TreeSet.  All entries are processed, except unresolved references, which
     * are filled in later.  For an indexable collection, the unresolved references are set
     * back into the proper element location.  For non-indexable collections (Sets), the
     * unresolved references are added via .add().
     * @param jsonObj a Map-of-Map representation of the JSON input stream.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected void traverseCollection(Deque<JsonObject<String, Object>> stack, JsonObject jsonObj) throws IOException
    {
        Object[] items = jsonObj.getArray()
        if (items == null || items.length == 0)
        {
            return
        }
        Collection col = (Collection) jsonObj.target
        boolean isList = col instanceof List
        int idx = 0

        for (Object element : items)
        {
            Object special
            if (element == null)
            {
                col.add(null)
            }
            else if (JsonParser.EMPTY_OBJECT.is(element))
            {   // Handles {}
                col.add(new JsonObject())
            }
            else if ((special = readIfMatching(element, null, stack)) != null)
            {
                col.add(special)
            }
            else if (element instanceof String || element instanceof Boolean || element instanceof Double || element instanceof Long)
            {    // Allow Strings, Booleans, Longs, and Doubles to be "inline" without Groovy object decoration (@id, @type, etc.)
                col.add(element)
            }
            else if (element.getClass().isArray())
            {
                JsonObject jObj = new JsonObject()
                jObj['@items'] = element
                createGroovyInstance(Object.class, jObj)
                col.add(jObj.target)
                convertMapsToObjects(jObj)
            }
            else // if (element instanceof JsonObject)
            {
                JsonObject jObj = (JsonObject) element
                Long ref = (Long) jObj['@ref']

                if (ref != null)
                {
                    JsonObject refObject = getReferencedObj(ref)

                    if (refObject.target != null)
                    {
                        col.add(refObject.target)
                    }
                    else
                    {
                        unresolvedRefs.add(new UnresolvedReference(jsonObj, idx, ref))
                        if (isList)
                        {   // Indexable collection, so set 'null' as element for now - will be patched in later.
                            col.add(null)
                        }
                    }
                }
                else
                {
                    createGroovyInstance(Object.class, jObj)

                    if (!MetaUtils.isLogicalPrimitive(jObj.getTargetClass()))
                    {
                        convertMapsToObjects(jObj)
                    }
                    col.add(jObj.target)
                }
            }
            idx++
        }

        jsonObj.remove('@items')   // Reduce memory required during processing
    }

    /**
     * Process java.util.Map and it's derivatives.  These can be written specially
     * so that the serialization would not expose the derivative class internals
     * (internal fields of TreeMap for example).
     * @param stack   a Stack (Deque) used to support graph traversal.
     * @param jsonObj a Map-of-Map representation of the JSON input stream.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected void traverseMap(Deque<JsonObject<String, Object>> stack, JsonObject<String, Object> jsonObj) throws IOException
    {
        // Convert @keys to a Collection of Groovy objects.
        convertMapToKeysItems(jsonObj)
        Object[] keys = (Object[]) jsonObj['@keys']
        Object[] items = jsonObj.getArray()

        if (keys == null || items == null)
        {
            if (!keys.is(items))
            {
                error("Map written where one of @keys or @items is empty")
            }
            return
        }

        int size = keys.length
        if (size != items.length)
        {
            error("Map written with @keys and @items entries of different sizes")
        }

        Object[] mapKeys = buildCollection(stack, keys, size)
        Object[] mapValues = buildCollection(stack, items, size)

        // Save these for later so that unresolved references inside keys or values
        // get patched first, and then build the Maps.
        prettyMaps.add([jsonObj, mapKeys, mapValues] as Object[])
    }

    private static Object[] buildCollection(Deque<JsonObject<String, Object>> stack, Object[] items, int size)
    {
        JsonObject jsonCollection = new JsonObject()
        jsonCollection['@items'] = items
        Object[] javaKeys = new Object[size]
        jsonCollection.target = javaKeys
        stack.addFirst(jsonCollection)
        return javaKeys
    }

    protected void traverseFieldsNoObj(final Deque<JsonObject<String, Object>> stack, final JsonObject<String, Object> jsonObj) throws IOException
    {
        final Object target = jsonObj.target
        for (Entry<String, Object> e : jsonObj.entrySet())
        {
            final String fieldName = e.key

            if (fieldName.charAt(0) == '@')
            {   // Skip our own meta fields
                continue
            }

            final Field field = (target != null) ? MetaUtils.getField(target.getClass(), fieldName) : null
            final Object rhs = e.value

            if (rhs == null)
            {
                jsonObj[fieldName] = null
            }
            else if (JsonParser.EMPTY_OBJECT.is(rhs))
            {
                jsonObj[fieldName] = new JsonObject()
            }
            else if (rhs.getClass().isArray())
            {    // LHS of assignment is an [] field or RHS is an array and LHS is Object (Map)
                final JsonObject<String, Object> jsonArray = new JsonObject<>()
                jsonArray['@items'] = rhs
                stack.addFirst(jsonArray)
                jsonObj[(fieldName)] = jsonArray
            }
            else if (rhs instanceof JsonObject)
            {
                final JsonObject<String, Object> jObj = (JsonObject) rhs
                if (field != null && JsonObject.isPrimitiveWrapper(field.type))
                {
                    jObj['value'] = MetaUtils.newPrimitiveWrapper(field.type, jObj['value'])
                    continue
                }
                final Long ref = (Long) jObj['@ref']

                if (ref != null)
                {    // Correct field references
                    JsonObject refObject = getReferencedObj(ref)
                    jsonObj[(fieldName)] = refObject    // Update Map-of-Maps reference
                }
                else
                {
                    stack.addFirst(jObj)
                }
            }
            else if (field != null)
            {   // The code below is 'upgrading' the RHS values in the passed in JsonObject Map
                // by using the @type class name (when specified and exists), to coerce the vanilla
                // JSON values into the proper types defined by the class listed in @type.  This is
                // a cool feature of groovy-io, that even when reading a map-of-maps JSON file, it will
                // improve the final types of values in the maps RHS, to be of the field type that
                // was optionally specified in @type.
                final Class fieldType = field.type
                if (MetaUtils.isPrimitive(fieldType))
                {
                    jsonObj[fieldName] = MetaUtils.newPrimitiveWrapper(fieldType, rhs)
                }
                else if (BigDecimal.class == fieldType)
                {
                    jsonObj[fieldName] = Readers.bigDecimalFrom(rhs)
                }
                else if (BigInteger.class == fieldType)
                {
                    jsonObj[fieldName] = Readers.bigIntegerFrom(rhs)
                }
                else if (rhs instanceof String)
                {
                    if (fieldType != String.class && fieldType != StringBuilder.class && fieldType != StringBuffer.class)
                    {
                        if ("".equals(((String)rhs).trim()))
                        {   // Allow "" to null out a non-String field on the inbound JSON
                            jsonObj[fieldName] = null
                        }
                    }
                }
            }
        }
        jsonObj.target = null  // don't waste space (used for typed return, not for Map return)
    }

    /**
     * Walk the Groovy object fields and copy them from the JSON object to the Groovy object, performing
     * any necessary conversions on primitives, or deep traversals for field assignments to other objects,
     * arrays, Collections, or Maps.
     * @param stack   Stack (Deque) used for graph traversal.
     * @param jsonObj a Map-of-Map representation of the current object being examined (containing all fields).
     * @throws java.io.IOException
     */
    protected void traverseFields(Deque<JsonObject<String, Object>> stack, JsonObject<String, Object> jsonObj) throws IOException
    {
        Object special
        if ((special = readIfMatching(jsonObj, null, stack)) != null)
        {
            jsonObj.target = special
            return
        }

        final Object javaMate = jsonObj.target
        Iterator<Entry<String, Object>> i = jsonObj.entrySet().iterator()
        Class cls = javaMate.getClass()

        while (i.hasNext())
        {
            Entry<String, Object> e = i.next()
            String key = e.key
            Field field = MetaUtils.getField(cls, key)
            Object rhs = e.value
            if (field != null)
            {
                assignField(stack, jsonObj, field, rhs)
            }
        }
        jsonObj.clear()    // Reduce memory required during processing
    }

    /**
     * Map Json Map object field to Groovy object field.
     *
     * @param stack   Stack (Deque) used for graph traversal.
     * @param jsonObj a Map-of-Map representation of the current object being examined (containing all fields).
     * @param field   a Groovy Field object representing where the jsonObj should be converted and stored.
     * @param rhs     the JSON value that will be converted and stored in the 'field' on the associated
     *                Groovy target object.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected void assignField(final Deque<JsonObject<String, Object>> stack, final JsonObject jsonObj,
                               final Field field, final Object rhs) throws IOException
    {
        final Object target = jsonObj.target
        try
        {
            final Class fieldType = field.type
            if (rhs == null)
            {
                if (fieldType.isPrimitive())
                {
                    field.set(target, MetaUtils.newPrimitiveWrapper(fieldType, "0"))
                }
                else
                {
                    field.set(target, null)
                }
                return
            }

            // If there is a "tree" of objects (e.g, Map<String, List<Person>>), the subobjects may not have an
            // @type on them, if the source of the JSON is from JSON.stringify().  Deep traverse the args and
            // mark @type on the items within the Maps and Collections, based on the parameterized type (if it
            // exists).
            if (rhs instanceof JsonObject)
            {
                if (field.genericType instanceof ParameterizedType)
                {   // Only JsonObject instances could contain unmarked objects.
                    markUntypedObjects(field.genericType, rhs, MetaUtils.getDeepDeclaredFields(fieldType))
                }

                // Ensure .setType() field set on JsonObject
                JsonObject job = (JsonObject) rhs
                String type = job.type
                if (type == null || type.isEmpty())
                {
                    job.type = fieldType.name
                }
            }

            Object special
            if (JsonParser.EMPTY_OBJECT.is(rhs))
            {
                JsonObject jObj = new JsonObject()
                jObj.type = fieldType.name
                Object value = createGroovyInstance(fieldType, jObj)
                field.set(target, value)
            }
            else if ((special = readIfMatching(rhs, fieldType, stack)) != null)
            {
                field.set(target, special)
            }
            else if (rhs.getClass().isArray())
            {    // LHS of assignment is an [] field or RHS is an array and LHS is Object
                Object[] elements = (Object[]) rhs
                JsonObject<String, Object> jsonArray = new JsonObject<>()
                if (([] as char[]).class == fieldType)
                {   // Specially handle char[] because we are writing these
                    // out as UTF8 strings for compactness and speed.
                    if (elements.length == 0)
                    {
                        field.set(target, [] as char[])
                    }
                    else
                    {
                        field.set(target, ((String) elements[0]).toCharArray())
                    }
                }
                else
                {
                    jsonArray['@items'] = elements
                    createGroovyInstance(fieldType, jsonArray)
                    field.set(target, jsonArray.target)
                    stack.addFirst(jsonArray)
                }
            }
            else if (rhs instanceof JsonObject)
            {
                JsonObject<String, Object> jObj = (JsonObject) rhs
                Long ref = (Long) jObj['@ref']

                if (ref != null)
                {    // Correct field references
                    JsonObject refObject = getReferencedObj(ref)

                    if (refObject.target != null)
                    {
                        field.set(target, refObject.target)
                    }
                    else
                    {
                        unresolvedRefs.add(new UnresolvedReference(jsonObj, field.name, ref))
                    }
                }
                else
                {    // Assign ObjectMap's to Object (or derived) fields
                    field.set(target, createGroovyInstance(fieldType, jObj))
                    if (!MetaUtils.isLogicalPrimitive(jObj.getTargetClass()))
                    {
                        stack.addFirst((JsonObject) rhs)
                    }
                }
            }
            else
            {
                if (MetaUtils.isPrimitive(fieldType))
                {
                    field.set(target, MetaUtils.newPrimitiveWrapper(fieldType, rhs))
                }
                else if (rhs instanceof String && "".equals(((String) rhs).trim()) && fieldType != String.class)
                {   // Allow "" to null out a non-String field
                    field.set(target, null)
                }
                else
                {
                    field.set(target, rhs)
                }
            }
        }
        catch (Exception e)
        {
            error(e.getClass().getSimpleName() + " setting field '" + field.name + "' on target: " + safeToString(target) + " with value: " + rhs, e)
        }
    }

    private static String safeToString(Object o)
    {
        if (o == null)
        {
            return "null"
        }
        try
        {
            return o.toString()
        }
        catch (Exception e)
        {
            return o.getClass().toString()
        }
    }

    private static void markUntypedObjects(Type type, Object rhs, Map<String, Field> classFields)
    {
        Deque<Object[]> stack = new ArrayDeque<>()
        stack.addFirst([type, rhs] as Object[])

        while (!stack.isEmpty())
        {
            Object[] item = stack.removeFirst()
            Type t = (Type) item[0]
            Object instance = item[1]
            if (t instanceof ParameterizedType)
            {
                Class clazz = getRawType(t)
                ParameterizedType pType = (ParameterizedType)t
                Type[] typeArgs = pType.actualTypeArguments

                if (typeArgs == null || typeArgs.length < 1 || clazz == null)
                {
                    continue
                }

                stampTypeOnJsonObject(instance, t)

                if (Map.class.isAssignableFrom(clazz))
                {
                    Map map = (Map) instance
                    if (!map.containsKey("@keys") && !map.containsKey("@items") && map instanceof JsonObject)
                    {   // Maps created in Javascript will come over without @keys / @items.
                        convertMapToKeysItems((JsonObject) map)
                    }

                    Object[] keys = (Object[])map['@keys']
                    getTemplateTraverseWorkItem(stack, keys, typeArgs[0])

                    Object[] items = (Object[])map['@items']
                    getTemplateTraverseWorkItem(stack, items, typeArgs[1])
                }
                else if (Collection.class.isAssignableFrom(clazz))
                {
                    if (instance instanceof Object[])
                    {
                        Object[] array = (Object[]) instance
                        for (int i=0; i < array.length; i++)
                        {
                            Object vals = array[i]
                            stack.addFirst([t, vals] as Object[])

                            if (vals instanceof JsonObject)
                            {
                                stack.addFirst([t, vals] as Object[])
                            }
                            else if (vals instanceof Object[])
                            {
                                JsonObject coll = new JsonObject()
                                coll.type = clazz.getName()
                                List items = Arrays.asList((Object[]) vals)
                                coll['@items'] = items.toArray()
                                stack.addFirst([t, items] as Object[])
                                array[i] = coll
                            }
                            else
                            {
                                stack.addFirst([t, vals] as Object[])
                            }
                        }
                    }
                    else if (instance instanceof Collection)
                    {
                        Collection col = (Collection)instance
                        for (Object o : col)
                        {
                            stack.addFirst([typeArgs[0], o] as Object[])
                        }
                    }
                    else if (instance instanceof JsonObject)
                    {
                        JsonObject jObj = (JsonObject) instance
                        Object[] array = jObj.getArray()
                        if (array != null)
                        {
                            for (Object o : array)
                            {
                                stack.addFirst([typeArgs[0], o] as Object[])
                            }
                        }
                    }
                }
                else
                {
                    if (instance instanceof JsonObject)
                    {
                        JsonObject<String, Object> jObj = (JsonObject) instance

                        for (Entry<String, Object> entry : jObj.entrySet())
                        {
                            final String fieldName = entry.key
                            if (!fieldName.startsWith('this$'))
                            {
                                // TODO: If more than one type, need to associate correct typeArgs entry to value
                                Field field = classFields.get(fieldName)

                                if (field != null && (field.type.typeParameters.length > 0 || field.genericType instanceof TypeVariable))
                                {
                                    stack.addFirst([typeArgs[0], entry.value] as Object[])
                                }
                            }
                        }
                    }
                }
            }
            else
            {
                stampTypeOnJsonObject(instance, t)
            }
        }
    }

    private static void getTemplateTraverseWorkItem(Deque<Object[]> stack, Object[] items, Type type)
    {
        if (items == null || items.length < 1)
        {
            return
        }
        Class rawType = getRawType(type)
        if (rawType != null && Collection.class.isAssignableFrom(rawType))
        {
            stack.add([type, items] as Object[])
        }
        else
        {
            for (Object o : items)
            {
                stack.add([type, o] as Object[])
            }
        }
    }

    // Mark 'type' on JsonObject when the type is missing and it is a 'leaf'
    // node (no further subtypes in it's parameterized type definition)
    private static void stampTypeOnJsonObject(Object o, Type t)
    {
        Class clazz = t instanceof Class ? (Class)t : getRawType(t)

        if (o instanceof JsonObject && clazz != null)
        {
            JsonObject jObj = (JsonObject) o
            if ((jObj.type == null || jObj.type.empty) && jObj.target == null)
            {
                jObj.type = clazz.getName()
            }
        }
    }

    public static Class getRawType(Type t)
    {
        if (t instanceof ParameterizedType)
        {
            ParameterizedType pType = ((ParameterizedType) t)

            if (pType.rawType instanceof Class)
            {
                return (Class) pType.rawType
            }
        }
        return null
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
     * This method creates a Groovy Object instance based on the passed in parameters.
     * If the JsonObject contains a key '@type' then that is used, as the type was explicitly
     * set in the JSON stream.  If the key '@type' does not exist, then the passed in Class
     * is used to create the instance, handling creating an Array or regular Object
     * instance.
     * <p/>
     * The '@type' is not often specified in the JSON input stream, as in many
     * cases it can be inferred from a field reference or array component type.
     *
     * @param clazz   Instance will be create of this class.
     * @param jsonObj Map-of-Map representation of object to create.
     * @return a new Groovy object of the appropriate type (clazz) using the jsonObj to provide
     *         enough hints to get the right class instantiated.  It is not populated when returned.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    protected Object createGroovyInstance(Class clazz, JsonObject jsonObj) throws IOException
    {
        final boolean useMapsLocal = useMaps
        final String type = jsonObj.type
        Object mate

        // @type always takes precedence over inferred Groovy (clazz) type.
        if (type != null)
        {    // @type is explicitly set, use that as it always takes precedence
            Class c
            try
            {
                c = MetaUtils.classForName(type)
            }
            catch (IOException e)
            {
                if (useMapsLocal)
                {
                    jsonObj.type = null
                    jsonObj.target = null
                    return jsonObj
                }
                else
                {
                    throw e
                }
            }
            if (c.isArray())
            {    // Handle []
                Object[] items = jsonObj.getArray()
                int size = (items == null) ? 0 : items.length
                if (c == ([] as char[]).class)
                {
                    jsonObj.moveCharsToMate()
                    mate = jsonObj.target
                }
                else
                {
                    mate = Array.newInstance(c.componentType, size)
                }
            }
            else
            {    // Handle regular field.object reference
                if (MetaUtils.isPrimitive(c))
                {
                    mate = MetaUtils.newPrimitiveWrapper(c, jsonObj['value'])
                }
                else if (c == Class.class)
                {
                    mate = MetaUtils.classForName((String) jsonObj['value'])
                }
                else if (c.isEnum())
                {
                    mate = getEnum(c, jsonObj)
                }
                else if (Enum.class.isAssignableFrom(c)) // anonymous subclass of an enum
                {
                    mate = getEnum(c.superclass, jsonObj)
                }
                else if ('java.util.Arrays$ArrayList'.equals(c.getName()))
                {	// Special case: Arrays$ArrayList does not allow .add() to be called on it.
                    mate = new ArrayList()
                }
                else
                {
					JsonTypeReader customReader = getCustomReader(c)
					if (customReader != null)
                    {
						mate = customReader.read(jsonObj,  new ArrayDeque<JsonObject<String, Object>>())
					}
                    else
                    {
						mate = newInstance(c)
					}
                }
            }
        }
        else
        {    // @type, not specified, figure out appropriate type
            Object[] items = jsonObj.getArray()

            // if @items is specified, it must be an [] type.
            // if clazz.isArray(), then it must be an [] type.
            if (clazz.isArray() || (items != null && clazz == Object.class && !jsonObj.containsKey("@keys")))
            {
                int size = (items == null) ? 0 : items.length
                mate = Array.newInstance(clazz.isArray() ? clazz.componentType : Object.class, size)
            }
            else if (clazz.isEnum())
            {
                mate = getEnum(clazz, jsonObj)
            }
            else if (Enum.class.isAssignableFrom(clazz)) // anonymous subclass of an enum
            {
                mate = getEnum(clazz.superclass, jsonObj)
            }
            else if ('java.util.Arrays$ArrayList'.equals(clazz.getName()))
            {	// Special case: Arrays$ArrayList does not allow .add() to be called on it.
                mate = []
            }
            else if (clazz == Object.class && !useMapsLocal)
            {
                if (jsonObj.isMap() || jsonObj.size() > 0)
                {
                    mate = new JsonObject()
                }
                else
                {   // Dunno
                    mate = newInstance(clazz)
                }
            }
            else
            {
                mate = newInstance(clazz)
            }
        }
        return jsonObj.target = mate
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

    /**
     * For all fields where the value was "@ref":"n" where 'n' was the id of an object
     * that had not yet been encountered in the stream, make the final substitution.
     * @throws java.io.IOException
     */
    private void patchUnresolvedReferences() throws IOException
    {
        Iterator i = unresolvedRefs.iterator()
        while (i.hasNext())
        {
            UnresolvedReference ref = (UnresolvedReference) i.next()
            Object objToFix = ref.referencingObj.target
            JsonObject objReferenced = _objsRead[(ref.refId)]

            if (objReferenced == null)
            {
                // System.err.println("Back reference (" + ref.refId + ") does not match any object id in input, field '" + ref.field + '\'')
                continue
            }

            if (objReferenced.target == null)
            {
                // System.err.println("Back referenced object does not exist,  @ref " + ref.refId + ", field '" + ref.field + '\'')
                continue
            }

            if (objToFix == null)
            {
                // System.err.println("Referencing object is null, back reference, @ref " + ref.refId + ", field '" + ref.field + '\'')
                continue
            }

            if (ref.index >= 0)
            {    // Fix []'s and Collections containing a forward reference.
                if (objToFix instanceof List)
                {   // Patch up Indexable Collections
                    List list = (List) objToFix
                    list.set(ref.index, objReferenced.target)
                }
                else if (objToFix instanceof Collection)
                {   // Add element (since it was not indexable, add it to collection)
                    Collection col = (Collection) objToFix
                    col.add(objReferenced.target)
                }
                else
                {
                    Array.set(objToFix, ref.index, objReferenced.target)        // patch array element here
                }
            }
            else
            {    // Fix field forward reference
                Field field = MetaUtils.getField(objToFix.getClass(), ref.field)
                if (field != null)
                {
                    try
                    {
                        field.set(objToFix, objReferenced.target)               // patch field here
                    }
                    catch (Exception e)
                    {
                        error("Error setting field while resolving references '" + field.name + "', @ref = " + ref.refId, e)
                    }
                }
            }

            i.remove()
        }

        int count = unresolvedRefs.size()
        if (count > 0)
        {
            StringBuilder out = new StringBuilder()
            out.append(count)
            out.append(" unresolved references:\n")
            i = unresolvedRefs.iterator()
            count = 1

            while (i.hasNext())
            {
                UnresolvedReference ref = (UnresolvedReference) i.next()
                out.append("    Unresolved reference ")
                out.append(count)
                out.append('\n')
                out.append("        @ref ")
                out.append(ref.refId)
                out.append('\n')
                out.append("        field ")
                out.append(ref.field)
                out.append("\n\n")
                count++
            }
            error(out.toString())
        }
    }

    /**
     * Process Maps/Sets (fix up their internal indexing structure)
     * This is required because Maps hash items using hashCode(), which will
     * change between VMs.  Rehashing the map fixes this.
     *
     * If useMaps==true, then move @keys to keys and @items to values
     * and then drop these two entries from the map.
     *
     * This hashes both Sets and Maps because the JDK sets are implemented
     * as Maps.  If you have a custom built Set, this would not 'treat' it
     * and you would need to provider a custom reader for that set.
     */
    private void rehashMaps()
    {
        final boolean useMapsLocal = useMaps
        for (Object[] mapPieces : prettyMaps)
        {
            JsonObject jObj = (JsonObject)  mapPieces[0]
            Object[] javaKeys, javaValues
            Map map

            if (useMapsLocal)
            {   // Make the @keys be the actual keys of the map.
                map = jObj
                javaKeys = (Object[]) jObj.remove("@keys")
                javaValues = (Object[]) jObj.remove("@items")
            }
            else
            {
                map = (Map) jObj.target
                javaKeys = (Object[]) mapPieces[1]
                javaValues = (Object[]) mapPieces[2]
                jObj.clear()
            }

            int j=0

            while (javaKeys != null && j < javaKeys.length)
            {
                map[javaKeys[j]] = javaValues[j]
                j++
            }
        }
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
