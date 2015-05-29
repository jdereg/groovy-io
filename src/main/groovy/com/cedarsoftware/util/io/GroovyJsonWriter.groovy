package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.sql.Timestamp
import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Output a Groovy object graph in JSON format.  This code handles cyclic
 * references and can serialize any Object graph without requiring a class
 * to be 'Serializeable' or have any specific methods on it.
 * <br/><ul><li>
 * Call the static method: {@code JsonWriter.toJson(employee)}.  This will
 * convert the passed in 'employee' instance into a JSON String.</li>
 * <li>Using streams:
 * <pre>     JsonWriter writer = new JsonWriter(stream)
 *     writer.write(employee)
 *     writer.close()</pre>
 * This will write the 'employee' object to the passed in OutputStream.
 * </li>
 * <p>That's it.  This can be used as a debugging tool.  Output an object
 * graph using the above code.  You can copy that JSON output into this site
 * which formats it with a lot of whitespace to make it human readable:
 * http://jsonformatter.curiousconcept.com
 * <br/><br/>
 * <p>This will output any object graph deeply (or null).  Object references are
 * properly handled.  For example, if you had A->B, B->C, and C->A, then
 * A will be serialized with a B object in it, B will be serialized with a C
 * object in it, and then C will be serialized with a reference to A (ref), not a
 * redefinition of A.</p>
 * <br/>
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
class GroovyJsonWriter implements Closeable, Flushable
{
    static final String DATE_FORMAT = "DATE_FORMAT"         // Set the date format to use within the JSON output
    static final String ISO_DATE_FORMAT = "yyyy-MM-dd"      // Constant for use as DATE_FORMAT value
    static final String ISO_DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"  // Constant for use as DATE_FORMAT value
    static final String TYPE_NAME_MAP = "TYPE_NAME_MAP"     // If set, this map will be used when writing @type values - allows short-hand abbreviations type names
    static final String SHORT_META_KEYS = "SHORT_META_KEYS" // If set, then @type -> @t, @keys -> @k, @items -> @i
    static final String TYPE = "TYPE"                       // Force @type always
    static final String PRETTY_PRINT = "PRETTY_PRINT"       // Force nicely formatted JSON output
    static final String FIELD_SPECIFIERS = "FIELD_SPECIFIERS"   // Set value to a Map<Class, List<String>> which will be used to control which fields on a class are output
    static final String ENUM_PUBLIC_ONLY = "ENUM_PUBLIC_ONLY" // If set, indicates that private variables of ENUMs are not to be serialized
    static final String WRITE_LONGS_AS_STRINGS = "WLAS"    // If set, longs are written in quotes (Javascript safe)
    private static final ConcurrentMap<Class, JsonTypeWriterBase> writers = new ConcurrentHashMap<>()
    private static final Set<Class> notCustom = [] as Set
    private static final Object[] byteStrings = new Object[256]
    private static final String newLine = System.getProperty("line.separator")
    private static final Long ZERO = 0L
    private static final ConcurrentMap<Class, JsonTypeWriterBase> writerCache = new ConcurrentHashMap<>()
    private static final NullClass nullWriter = new NullClass()
    private final Map<Object, Long> objVisited = new IdentityHashMap<>()
    private final Map<Object, Long> objsReferenced = new IdentityHashMap<>()
    private final Writer out
    private Map<String, String> typeNameMap = null;
    private boolean shortMetaKeys = false;
    long identity = 1
    private int depth = 0
    // _args is using ThreadLocal so that static inner classes can have access to them
    static final ThreadLocal<Map<String, Object>> _args = new ThreadLocal<Map<String, Object>>() {
        Map<String, Object> initialValue()
        {
            return [:]
        }
    }

    static
    {
        for (short i = -128; i <= 127; i++)
        {
            char[] chars = Integer.toString(i).toCharArray()
            byteStrings[i + 128] = chars
        }

        writers[String.class] = new Writers.JsonStringWriter()
        writers[Date.class] = new Writers.DateWriter()
        writers[BigInteger.class] = new Writers.BigIntegerWriter()
        writers[BigDecimal.class] = new Writers.BigDecimalWriter()
        writers[java.sql.Date.class] = new Writers.DateWriter()
        writers[Timestamp.class] = new Writers.TimestampWriter()
        writers[Calendar.class] = new Writers.CalendarWriter()
        writers[TimeZone.class] = new Writers.TimeZoneWriter()
        writers[Locale.class] = new Writers.LocaleWriter()
        writers[Class.class] = new Writers.ClassWriter()
        writers[StringBuilder.class] = new Writers.StringBuilderWriter()
        writers[StringBuffer.class] = new Writers.StringBufferWriter()
    }

    /**
     * @see GroovyJsonWriter#objectToJson(Object, java.util.Map)
     */
    static String objectToJson(Object item)
    {
        return objectToJson(item, new HashMap<String, Object>())
    }

    /**
     * @return The arguments used to configure the JsonWriter.  These are thread local.
     */
    protected static Map getArgs()
    {
        return _args.get()
    }

    /**
     * Provide access to subclasses.
     */
    protected Map getObjectsReferenced()
    {
        return objsReferenced
    }

    /**
     * Provide access to subclasses.
     */
    protected Map getObjectsVisited()
    {
        return objVisited
    }

    protected String getSubstituteTypeNameIfExists(String typeName)
    {
        if (typeNameMap == null)
        {
            return null
        }
        return typeNameMap[typeName]
    }

    protected String getSubstituteTypeName(String typeName)
    {
        if (typeNameMap == null)
        {
            return typeName
        }
        String shortName = typeNameMap[typeName]
        return shortName == null ? typeName : shortName
    }

    /**
     * Convert a Groovy Object to a JSON String.
     *
     * @param item Object to convert to a JSON String.
     * @param optionalArgs (optional) Map of extra arguments indicating how dates are formatted,
     * what fields are written out (optional).  For Date parameters, use the public static
     * DATE_TIME key, and then use the ISO_DATE or ISO_DATE_TIME indicators.  Or you can specify
     * your own custom SimpleDateFormat String, or you can associate a SimpleDateFormat object,
     * in which case it will be used.  This setting is for both java.util.Date and java.sql.Date.
     * If the DATE_FORMAT key is not used, then dates will be formatted as longs.  This long can
     * be turned back into a date by using 'new Date(longValue)'.
     * @return String containing JSON representation of passed
     *         in object.
     */
    static String objectToJson(Object item, Map<String, Object> optionalArgs)
    {
        ByteArrayOutputStream stream = new ByteArrayOutputStream()
        GroovyJsonWriter writer = new GroovyJsonWriter(stream, optionalArgs)
        writer.write(item)
        writer.close()
        return new String(stream.toByteArray(), "UTF-8")
    }

    /**
     * Format the passed in JSON string in a nice, human readable format.
     * @param json String input JSON
     * @return String containing equivalent JSON, formatted nicely for human readability.
     */
    static String formatJson(String json)
    {
        Map map = GroovyJsonReader.jsonToMaps(json)
        return objectToJson(map, [(PRETTY_PRINT):true] as Map<String, Object>)
    }

    /**
     * @see GroovyJsonWriter#GroovyJsonWriter(java.io.OutputStream, java.util.Map)
     */
    GroovyJsonWriter(OutputStream out)
    {
        this(out, [:])
    }

    /**
     * @param out OutputStream to which the JSON output will be written.
     * @param optionalArgs (optional) Map of extra arguments indicating how dates are formatted,
     * what fields are written out (optional).  For Date parameters, use the public static
     * DATE_TIME key, and then use the ISO_DATE or ISO_DATE_TIME indicators.  Or you can specify
     * your own custom SimpleDateFormat String, or you can associate a SimpleDateFormat object,
     * in which case it will be used.  This setting is for both java.util.Date and java.sql.Date.
     * If the DATE_FORMAT key is not used, then dates will be formatted as longs.  This long can
     * be turned back into a date by using 'new Date(longValue)'.
     */
    GroovyJsonWriter(OutputStream out, Map<String, Object> optionalArgs)
    {
        Map args = _args.get()
        args.clear()
        args.putAll(optionalArgs)
        args[JsonTypeWriterEx.JSON_WRITER] = this
        typeNameMap = (Map<String, String>) args[TYPE_NAME_MAP]
        shortMetaKeys = Boolean.TRUE.equals(args[SHORT_META_KEYS])

        if (optionalArgs.containsKey(FIELD_SPECIFIERS))
        {   // Convert String field names to Groovy Field instances (makes it easier for user to set this up)
            Map<Class, List<String>> specifiers = (Map<Class, List<String>>) args[FIELD_SPECIFIERS]
            Map<Class, List<Field>> copy = [:]
            for (Entry<Class, List<String>> entry : specifiers.entrySet())
            {
                Class clazz = entry.key
                List<String> fields = entry.value
                List<Field> newList = new ArrayList(fields.size())

                Map<String, Field> classFields = MetaUtils.getDeepDeclaredFields(clazz)

                for (String field : fields)
                {
                    Field f = classFields[field]
                    if (f == null)
                    {
                        throw new JsonIoException("Unable to locate field: " + field + " on class: " + clazz.getName() + ". Make sure the fields in the FIELD_SPECIFIERS map existing on the associated class.")
                    }
                    newList.add(f)
                }
                copy[clazz] = newList
            }
            args[FIELD_SPECIFIERS] = copy
        }
        else
        {   // Ensure that at least an empty Map is in the FIELD_SPECIFIERS entry
            args[FIELD_SPECIFIERS] = [:]
        }

        try
        {
            this.out = new BufferedWriter(new OutputStreamWriter(out, 'UTF-8'))
        }
        catch (UnsupportedEncodingException e)
        {
            throw new JsonIoException('Unsupported encoding.  Get a JVM that supports UTF-8', e)
        }
    }

    static boolean isPublicEnumsOnly()
    {
        return isTrue(_args.get()[ENUM_PUBLIC_ONLY])
    }

    static boolean isPrettyPrint()
    {
        return isTrue(_args.get()[PRETTY_PRINT])
    }

    static boolean getWriteLongsAsStrings()
    {
        return isTrue(_args.get()[WRITE_LONGS_AS_STRINGS])
    }

    private static boolean isTrue(Object setting)
    {
        if (setting instanceof Boolean)
        {
            return Boolean.TRUE.equals(setting)
        }
        else if (setting instanceof String)
        {
            return 'true'.equalsIgnoreCase((String) setting)
        }
        else if (setting instanceof Number)
        {
            return ((Number)setting).intValue() != 0;
        }

        return false
    }

    protected void tabIn()
    {
        tab(out, 1)
    }

    protected void newLine()
    {
        tab(out, 0)
    }

    protected void tabOut()
    {
        tab(out, -1)
    }

    private void tab(Writer output, int delta)
    {
        if (!isPrettyPrint())
        {
            return
        }
        output.write(newLine)
        depth += delta;
        for (int i=0; i < depth; i++)
        {
            output.write("  ")
        }
    }

    boolean writeIfMatching(Object o, boolean showType, Writer output)
    {
        Class c = o.getClass()
        if (notCustom.contains(c))
        {
            return false
        }

        return writeCustom(c, o, showType, output)
    }

    boolean writeArrayElementIfMatching(Class arrayComponentClass, Object o, boolean showType, Writer output)
    {
        if (!o.getClass().isAssignableFrom(arrayComponentClass) || notCustom.contains(o.getClass()))
        {
            return false
        }

        return writeCustom(arrayComponentClass, o, showType, output)
    }

    protected boolean writeCustom(Class arrayComponentClass, Object o, boolean showType, Writer output)
    {
		JsonTypeWriterBase closestWriter = getCustomWriter(arrayComponentClass)

        if (closestWriter == null)
        {
            return false
        }

        if (writeOptionalReference(o))
        {
            return true
        }

        boolean referenced = objsReferenced.containsKey(o)

        if (closestWriter instanceof JsonTypeWriter)
        {
            JsonTypeWriter writer = (JsonTypeWriter) closestWriter;
            if (writer.hasPrimitiveForm())
            {
                if ((!referenced && !showType) || closestWriter instanceof Writers.JsonStringWriter)
                {
                    writer.writePrimitiveForm(o, output)
                    return true
                }
            }
        }

        output.write('{')
        tabIn()
        if (referenced)
        {
            writeId(getId(o))
            if (showType)
            {
                output.write(',')
                newLine()
            }
        }

        if (showType)
        {
            writeType(o, output)
        }

        if (referenced || showType)
        {
            output.write(',')
            newLine()
        }

        if (closestWriter instanceof JsonTypeWriterEx)
        {
            ((JsonTypeWriterEx)closestWriter).write(o, showType || referenced, output, getArgs());
        }
        else
        {
            ((JsonTypeWriter)closestWriter).write(o, showType || referenced, output);
        }
        tabOut()
        output.write('}')
        return true
    }

    static class NullClass implements JsonTypeWriterBase { }

    private static JsonTypeWriterBase getCustomWriter(Class c)
    {
        JsonTypeWriterBase writer = writerCache[c]
        if (writer == null)
        {
            writer = getForceCustomWriter(c)
            JsonTypeWriterBase writerRef = writerCache.putIfAbsent(c, writer)
            if (writerRef != null)
            {
                writer = writerRef
            }
        }
        return writer.is(nullWriter) ? null : writer
    }

    private static JsonTypeWriterBase getForceCustomWriter(Class c)
    {
        JsonTypeWriterBase closestWriter = nullWriter
        int minDistance = Integer.MAX_VALUE

        for (Entry<Class, JsonTypeWriterBase> entry : writers.entrySet())
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
                closestWriter = entry.value
            }
        }
        return closestWriter
    }

    static void addWriter(Class c, JsonTypeWriterBase writer)
    {
        for (Entry<Class, JsonTypeWriterBase> entry : writers.entrySet())
        {
            Class clz = entry.key
            if (clz.is(c))
            {
                entry.value = writer   // Replace writer
                return
            }
        }
        writers[c] = writer
    }

    static void addNotCustomWriter(Class c)
    {
        writerCache[c] = nullWriter
    }

    void write(Object obj)
    {
        traceReferences(obj)
        objVisited.clear()
        writeImpl(obj, true)
        flush()
        objVisited.clear()
        objsReferenced.clear()
        _args.get().clear()
        _args.remove()
    }

    /**
     * Walk object graph and visit each instance, following each field, each Collection, Map and so on.
     * Tracks visited count to handle cycles and to determine if an item is referenced elsewhere.  If an
     * object is never referenced more than once, no @id field needs to be emitted for it.
     * @param root Object to be deeply traced.  The objVisited and objsReferenced Maps will be written to
     * during the trace.
     */
    protected void traceReferences(Object root)
    {
        if (root == null)
        {
            return
        }
        Deque<Object> stack = new ArrayDeque<>()
        stack.addFirst(root)
        final Map<Object, Long> visited = objVisited
        final Map<Object, Long> referenced = objsReferenced

        while (!stack.isEmpty())
        {
            Object obj = stack.removeFirst()

            if (!MetaUtils.isLogicalPrimitive(obj.getClass()))
            {
                Long id = visited[obj]
                if (id != null)
                {   // Only write an object once.
                    if (id == ZERO)
                    {   // 2nd time this object has been seen, so give it a unique ID and mark it referenced
                        id = identity++
                        visited[obj] = id
                        referenced[obj] = id
                    }
                    continue
                }
                else
                {   // Initially, mark an object with 0 as the ID, in case it is never referenced,
                    // we don't waste the memory to store a Long instance that is never used.
                    visited[obj] = ZERO
                }
            }

            final Class clazz = obj.getClass()

            if (clazz.isArray())
            {
                Class compType = clazz.componentType
                if (!MetaUtils.isLogicalPrimitive(compType))
                {   // Speed up: do not traceReferences of primitives, they cannot reference anything
                    final int len = Array.getLength(obj)

                    for (int i = 0; i < len; i++)
                    {
                        Object o = Array.get(obj, i)
                        if (o != null)
                        {   // Slight perf gain (null is legal)
                            stack.addFirst(o)
                        }
                    }
                }
            }
            else if (Map.class.isAssignableFrom(clazz))
            {   // Speed up - logically walk maps, as opposed to following their internal structure.
                Map map = (Map) obj
                for (Object item : map.entrySet())
                {
                    Entry entry = (Entry) item;
                    if (entry.value != null)
                    {
                        stack.addFirst(entry.value)
                    }
                    if (entry.key != null)
                    {
                        stack.addFirst(entry.key)
                    }
                }
            }
            else if (Collection.class.isAssignableFrom(clazz))
            {
                for (Object item : (Collection)obj)
                {
                    if (item != null)
                    {
                        stack.addFirst(item)
                    }
                }
            }
            else
            {
				// Only trace fields if no custom writer is present
				if (getCustomWriter(obj.getClass()) == null)
                {
					traceFields(stack, obj)
				}
            }
        }
    }

    /**
     * Reach-ability trace to visit all objects within the graph to be written.
     * This API will handle any object, using either reflection APIs or by
     * consulting a specified FIELD_SPECIFIERS map if provided.
     */
    protected void traceFields(Deque<Object> stack, Object obj)
    {
        Map<Class, List<Field>> fieldSpecifiers = (Map) _args.get()[FIELD_SPECIFIERS]

        // If caller has special Field specifier for a given class
        // then use it, otherwise use reflection.
        Collection fields = getFieldsUsingSpecifier(obj.getClass(), fieldSpecifiers)
        if (fields == null)
        {   // Trace fields using reflection
            fields = MetaUtils.getDeepDeclaredFields(obj.getClass()).values()
        }

        for (Field field : fields)
        {
            try
            {
                final Object o = field.get(obj)
                if (o != null && !MetaUtils.isLogicalPrimitive(o.getClass()))
                {
                    stack.addFirst(o)
                }
            }
            catch (Exception ignored)
            { }
        }
    }

    private static List<Field> getFieldsUsingSpecifier(Class classBeingWritten, Map<Class, List<Field>> fieldSpecifiers)
    {
        Iterator<Entry<Class, List<Field>>> i = fieldSpecifiers.entrySet().iterator()
        int minDistance = Integer.MAX_VALUE
        List<Field> fields = null

        while (i.hasNext())
        {
            Entry<Class, List<Field>> entry = i.next()
            Class c = entry.key
            if (c == classBeingWritten)
            {
                return entry.value
            }

            int distance = MetaUtils.getDistance(c, classBeingWritten)

            if (distance < minDistance)
            {
                minDistance = distance
                fields = entry.value
            }
        }

        return fields
    }

    private boolean writeOptionalReference(Object obj)
    {
        if (obj != null && MetaUtils.isLogicalPrimitive(obj.getClass()))
        {
            return false
        }
        final Writer output = this.out
        if (objVisited.containsKey(obj))
        {    // Only write (define) an object once in the JSON stream, otherwise emit a @ref
            String id = getId(obj)
            if (id == null)
            {   // Test for null because of Weak/Soft references being gc'd during serialization.
                return false
            }
            output.write(shortMetaKeys ? '{"@r":' : '{"@ref":')
            output.write(id)
            output.write('}')
            return true
        }

        // Mark the object as visited by putting it in the Map (this map is re-used / clear()'d after walk()).
        objVisited[obj] = (Long)null
        return false
    }

    void writeImpl(Object obj, boolean showType)
    {
        if (obj == null)
        {
            out.write('null')
            return
        }

        if (obj.getClass().isArray())
        {
            writeArray(obj, showType)
        }
        else if (obj instanceof Collection)
        {
            writeCollection((Collection) obj, showType)
        }
        else if (obj instanceof JsonObject)
        {   // symmetric support for writing Map of Maps representation back as equivalent JSON format.
            JsonObject jObj = (JsonObject) obj
            if (jObj.isArray())
            {
                writeJsonObjectArray(jObj, showType)
            }
            else if (jObj.isCollection())
            {
                writeJsonObjectCollection(jObj, showType)
            }
            else if (jObj.isMap())
            {
                if (!writeJsonObjectMapWithStringKeys(jObj, showType))
                {
                    writeJsonObjectMap(jObj, showType)
                }
            }
            else
            {
                writeJsonObjectObject(jObj, showType)
            }
        }
        else if (obj instanceof Map)
        {
            if (!writeMapWithStringKeys((Map) obj, showType))
            {
                writeMap((Map) obj, showType)
            }
        }
        else
        {
            writeObject(obj, showType)
        }
    }

    private void writeId(final String id)
    {
        out.write(shortMetaKeys ? '"@i":' : '"@id":')
        out.write(id == null ? "0" : id)
    }

    private void writeType(Object obj, Writer output)
    {
        output.write(shortMetaKeys ? '"@t":"' : '"@type":"')
        final Class c = obj.getClass()
        String typeName = c.getName()
        String shortName = getSubstituteTypeNameIfExists(typeName)

        if (shortName != null)
        {
            output.write(shortName)
            output.write('"')
            return
        }

        switch (c.getName())
        {
            case "java.lang.Boolean":
                output.write("boolean")
                break
            case "java.lang.Byte":
                output.write("byte")
                break
            case "java.lang.Character":
                output.write("char")
                break
            case "java.lang.Class":
                output.write("class")
                break
            case "java.lang.Double":
                output.write("double")
                break
            case "java.lang.Float":
                output.write("float")
                break
            case "java.lang.Integer":
                output.write("int")
                break
            case "java.lang.Long":
                output.write("long")
                break
            case "java.lang.Short":
                output.write("short")
                break
            case "java.lang.String":
                output.write("string")
                break
            case "java.util.Date":
                output.write("date")
                break
            default:
                output.write(c.getName())
                break
        }

        output.write('"')
    }

    private void writePrimitive(final Object obj, boolean showType) throws IOException
    {
        if (obj instanceof Character)
        {
            writeJsonUtf8String(String.valueOf(obj), out)
        }
        else
        {
            if (obj instanceof Long && getWriteLongsAsStrings())
            {
                if (showType)
                {
                    out.write(shortMetaKeys ? '{"@t":"' : '{"@type":"')
                    out.write(getSubstituteTypeName("long"))
                    out.write('","value":"')
                    out.write(obj.toString())
                    out.write('"}')
                }
                else
                {
                    out.write('"')
                    out.write(obj.toString())
                    out.write('"')
                }
            }
            else
            {
                out.write(obj.toString())
            }
        }
    }

    private void writeArray(final Object array, final boolean showType)
    {
        if (writeOptionalReference(array))
        {
            return;
        }

        Class arrayType = array.getClass()
        int len = Array.getLength(array)
        boolean referenced = objsReferenced.containsKey(array)
//        boolean typeWritten = showType && !(Object[].class == arrayType)    // causes IDE warning in NetBeans 7/4 Java 1.7
        boolean typeWritten = showType && !(arrayType.equals(([] as Object[]).class))

        final Writer output = this.out // performance opt: place in final local for quicker access
        if (typeWritten || referenced)
        {
            output.write('{')
            tabIn()
        }

        if (referenced)
        {
            writeId(getId(array))
            output.write(',')
            newLine()
        }

        if (typeWritten)
        {
            writeType(array, output)
            output.write(',')
            newLine()
        }

        if (len == 0)
        {
            if (typeWritten || referenced)
            {
                output.write(shortMetaKeys ? '"@e":[]' : '"@items":[]')
                tabOut()
                output.write('}')
            }
            else
            {
                output.write("[]")
            }
            return
        }

        if (typeWritten || referenced)
        {
            output.write(shortMetaKeys ? '"@e":[' : '"@items":[')
        }
        else
        {
            output.write('[')
        }
        tabIn()

        final int lenMinus1 = len - 1

        // Intentionally processing each primitive array type in separate
        // custom loop for speed. All of them could be handled using
        // reflective Array.get() but it is slower.  I chose speed over code length.
        if (([] as byte[]).class.is(arrayType))
        {
            writeByteArray((byte[]) array, lenMinus1)
        }
        else if (([] as char[]).class.is(arrayType))
        {
            writeJsonUtf8String(new String((char[]) array), output)
        }
        else if (([] as short[]).class.is(arrayType))
        {
            writeShortArray((short[]) array, lenMinus1)
        }
        else if (([] as int[]).class.is(arrayType))
        {
            writeIntArray((int[]) array, lenMinus1)
        }
        else if (([] as long[]).class.is(arrayType))
        {
            writeLongArray((long[]) array, lenMinus1)
        }
        else if (([] as float[]).class.is(arrayType))
        {
            writeFloatArray((float[]) array, lenMinus1)
        }
        else if (([] as double[]).class.is(arrayType))
        {
            writeDoubleArray((double[]) array, lenMinus1)
        }
        else if (([] as boolean[]).class.is(arrayType))
        {
            writeBooleanArray((boolean[]) array, lenMinus1)
        }
        else
        {
            final Class componentClass = array.getClass().componentType
            final boolean isPrimitiveArray = MetaUtils.isPrimitive(componentClass)
            final boolean isObjectArray = ([] as Object[]).class.is(arrayType)

            for (int i = 0; i < len; i++)
            {
                final Object value = Array.get(array, i)

                if (value == null)
                {
                    output.write("null")
                }
                else if (writeArrayElementIfMatching(componentClass, value, false, output)) { }
                else if (isPrimitiveArray || value instanceof Boolean || value instanceof Long || value instanceof Double)
                {
                    writePrimitive(value, value.getClass() != componentClass)
                }
                else if (isObjectArray)
                {
                    if (writeIfMatching(value, true, output)) { }
                    else
                    {
                        writeImpl(value, true)
                    }
                }
                else
                {   // Specific Class-type arrays - only force type when
                    // the instance is derived from array base class.
                    boolean forceType = !(value.getClass() == componentClass)
                    writeImpl(value, forceType || alwaysShowType())
                }

                if (i != lenMinus1)
                {
                    output.write(',')
                    newLine()
                }
            }
        }

        tabOut()
        output.write(']')
        if (typeWritten || referenced)
        {
            tabOut()
            output.write('}')
        }
    }

    /**
     * @return true if the user set the 'TYPE' flag to true, indicating to always show type.
     */
    private static boolean alwaysShowType()
    {
        return Boolean.TRUE.equals(_args.get().containsKey(TYPE))
    }

    private void writeBooleanArray(boolean[] booleans, int lenMinus1)
    {
        final Writer output = this.out
        for (int i = 0; i < lenMinus1; i++)
        {
            output.write(booleans[i] ? "true," : "false,")
        }
        output.write(Boolean.toString(booleans[lenMinus1]))
    }

    private void writeDoubleArray(double[] doubles, int lenMinus1)
    {
        final Writer output = this.out;
        for (int i = 0; i < lenMinus1; i++)
        {
            output.write(Double.toString(doubles[i]))
            output.write(',')
        }
        output.write(Double.toString(doubles[lenMinus1]))
    }

    private void writeFloatArray(float[] floats, int lenMinus1)
    {
        final Writer output = this.out;
        for (int i = 0; i < lenMinus1; i++)
        {
            output.write(Float.toString(floats[i]))
            output.write(',')
        }
        output.write(Float.toString(floats[lenMinus1]))
    }

    private void writeLongArray(long[] longs, int lenMinus1) throws IOException
    {
        final Writer output = this.out
        if (getWriteLongsAsStrings())
        {
            for (int i = 0; i < lenMinus1; i++)
            {
                output.write('"')
                output.write(Long.toString(longs[i]))
                output.write('"')
                output.write(',')
            }
            output.write('"')
            output.write(Long.toString(longs[lenMinus1]))
            output.write('"')
        }
        else
        {
            for (int i = 0; i < lenMinus1; i++)
            {
                output.write(Long.toString(longs[i]))
                output.write(',')
            }
            output.write(Long.toString(longs[lenMinus1]))
        }
    }

    private void writeIntArray(int[] ints, int lenMinus1)
    {
        final Writer output = this.out;
        for (int i = 0; i < lenMinus1; i++)
        {
            output.write(Integer.toString(ints[i]))
            output.write(',')
        }
        output.write(Integer.toString(ints[lenMinus1]))
    }

    private void writeShortArray(short[] shorts, int lenMinus1)
    {
        final Writer output = this.out;
        for (int i = 0; i < lenMinus1; i++)
        {
            output.write(Integer.toString(shorts[i]))
            output.write(',')
        }
        output.write(Integer.toString(shorts[lenMinus1]))
    }

    private void writeByteArray(byte[] bytes, int lenMinus1)
    {
        final Writer output = this.out;
        final Object[] byteStrs = byteStrings;
        for (int i = 0; i < lenMinus1; i++)
        {
            output.write((char[]) byteStrs[bytes[i] + 128])
            output.write(',')
        }
        output.write((char[]) byteStrs[bytes[lenMinus1] + 128])
    }

    private void writeCollection(Collection col, boolean showType)
    {
        if (writeOptionalReference(col))
        {
            return
        }

        final Writer output = this.out
        boolean referenced = objsReferenced.containsKey(col)
        boolean isEmpty = col.isEmpty()

        if (referenced || showType)
        {
            output.write('{')
            tabIn()
        }
        else if (isEmpty)
        {
            output.write('[')
        }

        writeIdAndTypeIfNeeded(col, showType, referenced)

        if (isEmpty)
        {
            if (referenced || showType)
            {
                tabOut()
                output.write('}')
            }
            else
            {
                output.write(']')
            }
            return;
        }

        beginCollection(showType, referenced)
        Iterator i = col.iterator()

        while (i.hasNext())
        {
            writeCollectionElement(i.next())

            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write(']')
        if (showType || referenced)
        {   // Finished object, as it was output as an object if @id or @type was output
            tabOut()
            output.write('}')
        }
    }

    private void writeIdAndTypeIfNeeded(Object col, boolean showType, boolean referenced)
    {
        if (referenced)
        {
            writeId(getId(col))
        }

        if (showType)
        {
            if (referenced)
            {
                out.write(',')
                newLine()
            }
            writeType(col, out)
        }
    }

    private void beginCollection(boolean showType, boolean referenced)
    {
        if (showType || referenced)
        {
            out.write(',')
            newLine()
            out.write(shortMetaKeys ? '"@e":[' : '"@items":[')
        }
        else
        {
            out.write('[')
        }
        tabIn()
    }

    private void writeJsonObjectArray(JsonObject jObj, boolean showType)
    {
        if (writeOptionalReference(jObj))
        {
            return;
        }

        int len = jObj.length
        String type = jObj.type
        Class arrayClass;

        if (type == null || ([] as Object[]).class.is(type))
        {
            arrayClass = ([] as Object[]).class
        }
        else
        {
            arrayClass = MetaUtils.classForName(type)
        }

        final Writer output = this.out
        final boolean isObjectArray = ([] as Object[]).class.is(arrayClass)
        final Class componentClass = arrayClass.componentType
        boolean referenced = objsReferenced.containsKey(jObj) && jObj.hasId()
        boolean typeWritten = showType && !isObjectArray

        if (typeWritten || referenced)
        {
            output.write('{')
            tabIn()
        }

        if (referenced)
        {
            writeId(Long.toString(jObj.id))
            output.write(',')
            newLine()
        }

        if (typeWritten)
        {
            output.write(shortMetaKeys ? '"@t":"' : '"@type":"')
            output.write(getSubstituteTypeName(arrayClass.getName()))
            output.write('",')
            newLine()
        }

        if (len == 0)
        {
            if (typeWritten || referenced)
            {
                output.write(shortMetaKeys ? '"@e":[]' : '"@items":[]')
                tabOut()
                output.write("}")
            }
            else
            {
                output.write("[]")
            }
            return
        }

        if (typeWritten || referenced)
        {
            output.write(shortMetaKeys ? '"@e":[' : '"@items":[')
        }
        else
        {
            output.write('[')
        }
        tabIn()

        Object[] items = (Object[]) jObj['@items']
        final int lenMinus1 = len - 1

        for (int i = 0; i < len; i++)
        {
            final Object value = items[i]

            if (value == null)
            {
                output.write("null")
            }
            else if (Character.class == componentClass || char.class == componentClass)
            {
                writeJsonUtf8String((String) value, output)
            }
            else if (value instanceof Boolean || value instanceof Long || value instanceof Double)
            {
                writePrimitive(value, value.getClass() != componentClass)
            }
            else if (value instanceof String)
            {   // Have to specially treat String because it could be referenced, but we still want inline (no @type, value:)
                writeJsonUtf8String((String) value, output)
            }
            else if (isObjectArray)
            {
                if (writeIfMatching(value, true, output)) { }
                else
                {
                    writeImpl(value, true)
                }
            }
            else if (writeArrayElementIfMatching(componentClass, value, false, output)) { }
            else
            {   // Specific Class-type arrays - only force type when
                // the instance is derived from array base class.
                boolean forceType = !(value.getClass() == componentClass)
                writeImpl(value, forceType || alwaysShowType())
            }

            if (i != lenMinus1)
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write(']')
        if (typeWritten || referenced)
        {
            tabOut()
            output.write('}')
        }
    }

    private void writeJsonObjectCollection(JsonObject jObj, boolean showType)
    {
        if (writeOptionalReference(jObj))
        {
            return
        }

        String type = jObj.type
        Class colClass = MetaUtils.classForName(type)
        boolean referenced = objsReferenced.containsKey(jObj) && jObj.hasId()
        final Writer output = this.out
        int len = jObj.length

        if (referenced || showType || len == 0)
        {
            output.write('{')
            tabIn()
        }

        if (referenced)
        {
            writeId(String.valueOf(jObj.id))
        }

        if (showType)
        {
            if (referenced)
            {
                output.write(',')
                newLine()
            }
            output.write(shortMetaKeys ? '"@t":"' : '"@type":"')
            output.write(getSubstituteTypeName(colClass.getName()))
            output.write('"')
        }

        if (len == 0)
        {
            tabOut()
            output.write('}')
            return
        }

        beginCollection(showType, referenced)
        Object[] items = (Object[]) jObj['@items']
        final int itemsLen = items.length
        final int itemsLenMinus1 = itemsLen - 1

        for (int i=0; i < itemsLen; i++)
        {
            writeCollectionElement(items[i])

            if (i != itemsLenMinus1)
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write("]")
        if (showType || referenced)
        {
            tabOut()
            output.write('}')
        }
    }

    private void writeJsonObjectMap(JsonObject jObj, boolean showType)
    {
        if (writeOptionalReference(jObj))
        {
            return
        }

        boolean referenced = objsReferenced.containsKey(jObj) && jObj.hasId()
        final Writer output = this.out

        output.write('{')
        tabIn()
        if (referenced)
        {
            writeId(String.valueOf(jObj.id))
        }

        if (showType)
        {
            if (referenced)
            {
                output.write(',')
                newLine()
            }
            String type = jObj.type
            if (type != null)
            {
                Class mapClass = MetaUtils.classForName(type)
                output.write(shortMetaKeys ? '"@t":"' : '"@type":"');
                output.write(getSubstituteTypeName(mapClass.getName()));
                output.write('"')
            }
            else
            {   // type not displayed
                showType = false
            }
        }

        if (jObj.isEmpty())
        {   // Empty
            tabOut()
            output.write('}')
            return
        }

        if (showType)
        {
            output.write(',')
            newLine()
        }

        output.write(shortMetaKeys ? '"@k":[' : '"@keys":[')
        tabIn()
        Iterator i = jObj.keySet().iterator()

        while (i.hasNext())
        {
            writeCollectionElement(i.next())

            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write('],')
        newLine()
        output.write(shortMetaKeys ? '"@e":[' : '"@items":[')
        tabIn()
        i =jObj.values().iterator()

        while (i.hasNext())
        {
            writeCollectionElement(i.next())

            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write(']')
        tabOut()
        output.write('}')
    }


    private boolean writeJsonObjectMapWithStringKeys(JsonObject jObj, boolean showType)
    {
        if (!ensureJsonPrimitiveKeys(jObj))
        {
            return false
        }

        if (writeOptionalReference(jObj))
        {
            return true
        }

        boolean referenced = objsReferenced.containsKey(jObj) && jObj.hasId()
        final Writer output = this.out
        output.write('{')
        tabIn()

        if (referenced)
        {
            writeId(String.valueOf(jObj.id))
        }

        if (showType)
        {
            if(referenced)
            {
                output.write(',')
                newLine()
            }
            String type = jObj.type
            if (type != null)
            {
                Class mapClass = MetaUtils.classForName(type)
                output.write(shortMetaKeys ? '"@t":"' : '"@type":"')
                output.write(getSubstituteTypeName(mapClass.getName()))
                output.write('"')
            }
            else
            { // type not displayed
                showType = false
            }
        }

        if (jObj.isEmpty())
        { // Empty
            tabOut()
            output.write('}')
            return true
        }

        if (showType)
        {
            output.write(',')
            newLine()
        }

        return writeMapBody(jObj.entrySet().iterator())
    }


    private void writeJsonObjectObject(JsonObject jObj, boolean showType)
    {
        if (writeOptionalReference(jObj))
        {
            return;
        }

        final Writer output = this.out
        boolean referenced = objsReferenced.containsKey(jObj) && jObj.hasId()
        showType = showType && jObj.getType() != null
        Class type = null

        output.write('{')
        tabIn()
        if (referenced)
        {
            writeId(String.valueOf(jObj.id))
        }

        if (showType)
        {
            if (referenced)
            {
                output.write(',')
                newLine()
            }
            output.write(shortMetaKeys ? '"@t":"' : '"@type":"')
            output.write(getSubstituteTypeName(jObj.type))
            output.write('"')
            try  { type = MetaUtils.classForName(jObj.type) }
            catch(Exception ignored) { type = null; }
        }

        if (jObj.isEmpty())
        {
            tabOut()
            output.write('}')
            return
        }

        if (showType || referenced)
        {
            output.write(',')
            newLine()
        }

        Iterator<Entry<String,Object>> i = jObj.entrySet().iterator()

        while (i.hasNext())
        {
            Entry<String, Object>entry = i.next()
            final String fieldName = entry.key
            output.write('"')
            output.write(fieldName)
            output.write('":')
            Object value = entry.value

            if (value == null)
            {
                output.write("null")
            }
            else if (value instanceof BigDecimal || value instanceof BigInteger)
            {
                writeImpl(value, !doesValueTypeMatchFieldType(type, fieldName, value))
            }
            else if (value instanceof Number || value instanceof Boolean)
            {
                output.write(value.toString())
            }
            else if (value instanceof String)
            {
                writeJsonUtf8String((String) value, output)
            }
            else if (value instanceof Character)
            {
                writeJsonUtf8String(String.valueOf(value), output)
            }
            else
            {
                writeImpl(value, !doesValueTypeMatchFieldType(type, fieldName, value))
            }
            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }
        tabOut()
        output.write('}')
    }

    private static boolean doesValueTypeMatchFieldType(Class type, String fieldName, Object value)
    {
        if (type != null)
        {
            Map<String, Field> classFields = MetaUtils.getDeepDeclaredFields(type)
            Field field = classFields[fieldName]
            return field != null && (value.getClass() == field.type)
        }
        return false
    }

    private void writeMap(Map map, boolean showType)
    {
        if (writeOptionalReference(map))
        {
            return
        }

        final Writer output = this.out;
        boolean referenced = objsReferenced.containsKey(map)

        output.write('{')
        tabIn()
        if (referenced)
        {
            writeId(getId(map))
        }

        if (showType)
        {
            if (referenced)
            {
                output.write(',')
                newLine()
            }
            writeType(map, output)
        }

        if (map.isEmpty())
        {
            tabOut()
            output.write('}')
            return
        }

        if (showType || referenced)
        {
            output.write(',')
            newLine()
        }

        output.write(shortMetaKeys ? '"@k":[' : '"@keys":[')
        tabIn()
        Iterator i = map.keySet().iterator()

        while (i.hasNext())
        {
            writeCollectionElement(i.next())

            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write("],")
        newLine()
        output.write(shortMetaKeys ? '"@e":[' : '"@items":[')
        tabIn()
        i = map.values().iterator()

        while (i.hasNext())
        {
            writeCollectionElement(i.next())

            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write(']')
        tabOut()
        output.write('}')
    }


    private boolean writeMapWithStringKeys(Map map, boolean showType)
    {
        if (!ensureJsonPrimitiveKeys(map))
        {
            return false
        }

        if (writeOptionalReference(map))
        {
            return true
        }

        boolean referenced = objsReferenced.containsKey(map)

        out.write('{')
        tabIn()
        writeIdAndTypeIfNeeded(map, showType, referenced)

        if (map.isEmpty())
        {
            tabOut()
            out.write('}')
            return true
        }

        if (showType || referenced)
        {
            out.write(',')
            newLine()
        }

        return writeMapBody(map.entrySet().iterator())
    }

    private boolean writeMapBody(final Iterator i)
    {
        final Writer output = out;
        while (i.hasNext())
        {
            Entry att2value = (Entry) i.next()
            output.write('"')
            output.write((String) att2value.key)
            output.write('":')

            writeCollectionElement(att2value.value)

            if (i.hasNext())
            {
                output.write(',')
                newLine()
            }
        }

        tabOut()
        output.write('}')
        return true
    }

    // Ensure that all keys within the Map are String instances
    static boolean ensureJsonPrimitiveKeys(Map map)
    {
        for (Object o : map.keySet())
        {
            if (!(o instanceof String))
            {
                return false
            }
        }
        return true
    }

    /**
     * Write an element that is contained in some type of collection or Map.
     * @param o Collection element to output in JSON format.
     */
    private void writeCollectionElement(Object o)
    {
        if (o == null)
        {
            out.write('null')
        }
        else if (o instanceof Boolean || o instanceof Double)
        {
            out.write(o.toString())
        }
        else if (o instanceof Long)
        {
            writePrimitive(o, getWriteLongsAsStrings())
        }
        else if (o instanceof String)
        {
            writeJsonUtf8String((String) o, out)
        }
        else
        {
            writeImpl(o, true)
        }
    }

    /**
     * @param obj      Object to be written in JSON format
     * @param showType boolean true means show the "@type" field, false
     *                 eliminates it.  Many times the type can be dropped because it can be
     *                 inferred from the field or array type.
     */
    private void writeObject(final Object obj, final boolean showType)
    {
        if (writeIfMatching(obj, showType, out))
        {
            return
        }

        if (writeOptionalReference(obj))
        {
            return
        }

        out.write('{')
        tabIn()
        final boolean referenced = objsReferenced.containsKey(obj)
        if (referenced)
        {
            writeId(getId(obj))
        }

        if (referenced && showType)
        {
            out.write(',')
            newLine()
        }

        if (showType)
        {
            writeType(obj, out)
        }

        boolean first = !showType;
        if (referenced && !showType)
        {
            first = false
        }

        final Map<Class, List<Field>> fieldSpecifiers = (Map) _args.get()[FIELD_SPECIFIERS]
        final List<Field> externallySpecifiedFields = getFieldsUsingSpecifier(obj.getClass(), fieldSpecifiers)
        if (externallySpecifiedFields != null)
        {   // Caller is using associating a class name to a set of fields for the given class (allows field reductions)
            for (Field field : externallySpecifiedFields)
            {   // Not currently supporting overwritten field names in hierarchy when using external field specifier
                first = writeField(obj, first, field.getName(), field, true)
            }
        }
        else
        {   // Reflectively use fields, skipping transient and static fields
            final Map<String, Field> classInfo = MetaUtils.getDeepDeclaredFields(obj.getClass())
            for (Entry<String, Field> entry : classInfo.entrySet())
            {
                final String fieldName = entry.key
                final Field field = entry.value
                first = writeField(obj, first, fieldName, field, false)
            }
        }

        tabOut()
        out.write('}')
    }

    private boolean writeField(Object obj, boolean first, String fieldName, Field field, boolean allowTransient)
    {
        if (!allowTransient && (field.modifiers & Modifier.TRANSIENT) != 0)
        {   // Do not write transient fields
            return first
        }

        int modifiers = field.modifiers
        if (field.declaringClass.isEnum() && !Modifier.isPublic(modifiers) && isPublicEnumsOnly())
        {
            return first
        }

        if (!first)
        {
            out.write(',')
            newLine()
        }

        writeJsonUtf8String(fieldName, out)
        out.write(':')

        Object o
        try
        {
            o = field.get(obj)
        }
        catch (Exception ignored)
        {
            o = null
        }

        if (o == null)
        {    // don't quote null
            out.write("null")
            return false
        }

        Class type = field.type
        boolean forceType = o.getClass() != type;     // If types are not exactly the same, write "@type" field

        if (MetaUtils.isPrimitive(type))
        {
            writePrimitive(o, false)
        }
        else if (writeIfMatching(o, forceType, out)) { }
        else
        {
            writeImpl(o, forceType || alwaysShowType())
        }
        return false
    }

    /**
     * Write out special characters "\b, \f, \t, \n, \r", as such, backslash as \\
     * quote as \" and values less than an ASCII space (20hex) as "\\u00xx" format,
     * characters in the range of ASCII space to a '~' as ASCII, and anything higher in UTF-8.
     *
     * @param s String to be written in utf8 format on the output stream.
     */
    static void writeJsonUtf8String(String s, final Writer output)
    {
        output.write('\"')
        final int len = s.length()

        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i)

            if (c < (char)' ')
            {    // Anything less than ASCII space, write either in \\u00xx form, or the special \t, \n, etc. form
                switch (c)
                {
                    case (char)'\b':
                        output.write('\\b')
                        break
                    case (char)'\f':
                        output.write('\\f')
                        break
                    case (char)'\n':
                        output.write('\\n')
                        break
                    case (char)'\r':
                        output.write('\\r')
                        break
                    case (char)'\t':
                        output.write('\\t')
                        break
                    default:
                        String hex = Integer.toHexString((int)c)
                        output.write('\\u')
                        final int pad = 4 - hex.length()
                        for (int k = 0; k < pad; k++)
                        {
                            output.write('0')
                        }
                        output.write(hex)
                        break
                }
            }
            else if (c == (char)'\\' || c == (char)'"')
            {
                output.write('\\')
                output.write(c)
            }
            else
            {   // Anything else - write in UTF-8 form (multi-byte encoded) (OutputStreamWriter is UTF-8)
                output.write(c)
            }
        }
        output.write('\"')
    }

    void flush()
    {
        try
        {
            if (out != null)
            {
                out.flush()
            }
        }
        catch (Exception ignored) { }
    }

    void close()
    {
        try
        {
            out.close()
        }
        catch (Exception ignore) { }
    }

    private String getId(Object o)
    {
        if (o instanceof JsonObject)
        {
            Long id = ((JsonObject) o).getId()
            if (id != -1)
            {
                return String.valueOf(id)
            }
        }
        Long id = objsReferenced[o]
        return id == null ? null : Long.toString(id)
    }
}
