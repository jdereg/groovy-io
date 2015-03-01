package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

import java.lang.reflect.Array
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * This utility class is used to perform operations on Classes, Fields, etc.
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
 *         limitations under the License.*
 */
@CompileStatic
class MetaUtils
{
    private static final Map<Class, Map<String, Field>> classMetaCache = new ConcurrentHashMap<>()
    private static final Character[] charCache = new Character[128]
    private static final Byte[] byteCache = new Byte[256]
    private static final Pattern extraQuotes = Pattern.compile('(["]*)([^"]*)(["]*)')
    private static final Map<Class, Object[]> constructors = new ConcurrentHashMap<>()
    private static final Collection unmodifiableCollection = Collections.unmodifiableCollection([])
    private static final Collection unmodifiableSet = Collections.unmodifiableSet(new HashSet())
    private static final Collection unmodifiableSortedSet = Collections.unmodifiableSortedSet(new TreeSet())
    private static final Map unmodifiableMap = Collections.unmodifiableMap(new HashMap())
    private static final Map unmodifiableSortedMap = Collections.unmodifiableSortedMap(new TreeMap())
    private static final Class[] emptyClassArray = [] as Class[]
    private static boolean useUnsafe = false
    private static Unsafe unsafe
    private static final Set<Class> prims = [
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            Boolean.class,
            Character.class
    ] as Set
    private static final Map<String, Class> nameToClass = [
            'string':String.class,
            'boolean':boolean.class,
            'char':char.class,
            'byte':byte.class,
            'short':short.class,
            'int':int.class,
            'long':long.class,
            'float':float.class,
            'double':double.class,
            'date':Date.class,
            'class':Class.class
    ]
    protected static final ThreadLocal<SimpleDateFormat> dateFormat = new ThreadLocal<SimpleDateFormat>() {
        public SimpleDateFormat initialValue()
        {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        }
    }

    static
    {
        // Save memory by re-using common Characters (Characters are immutable)
        for (int i = 0; i < charCache.length; i++)
        {
            charCache[i] = new Character((char)i)
        }

        // Save memory by re-using all byte instances (Bytes are immutable)
        for (int i = 0; i < byteCache.length; i++)
        {
            byteCache[i] = (byte) (i - 128)
        }
    }

    static void setUseUnsafe(boolean state)
    {
        useUnsafe = state
        if (state)
        {
            try
            {
                unsafe = new Unsafe()
            }
            catch (ReflectiveOperationException e)
            {
                useUnsafe = false
            }
        }
    }

    static Field getField(Class c, String field)
    {
        return getDeepDeclaredFields(c).get(field)
    }

    /**
     * @param c Class instance
     * @return ClassMeta which contains fields of class.  The results are cached internally for performance
     *         when called again with same Class.
     */
    static Map<String, Field> getDeepDeclaredFields(Class c)
    {
        Map<String, Field> classFields = classMetaCache[c]
        if (classFields != null)
        {
            return classFields
        }

        classFields = [:]
        Class curr = c

        while (curr != null)
        {
            try
            {
                final Field[] local = curr.declaredFields

                for (Field field : local)
                {
                    if ((field.modifiers & Modifier.STATIC) == 0)
                    {   // speed up: do not process static fields.
                        if ("metaClass".equals(field.name) && "groovy.lang.MetaClass".equals(field.type.name))
                        {   // Skip Groovy metaClass field if present
                            continue
                        }

                        if (!field.accessible)
                        {
                            try
                            {
                                field.accessible = true
                            }
                            catch (Exception ignored) { }
                        }
                        if (classFields.containsKey(field.name))
                        {
                            classFields[curr.name + '.' + field.name] = field
                        }
                        else
                        {
                            classFields[field.name] = field
                        }
                    }
                }
            }
            catch (ThreadDeath t)
            {
                throw t
            }
            catch (Throwable ignored) { }

            curr = curr.superclass
        }

        classMetaCache[c] = classFields
        return classFields
    }

    /**
     * @return inheritance distance between two classes, or Integer.MAX_VALUE if they are not related.
     */
    static int getDistance(Class a, Class b)
    {
        if (a.isInterface())
        {
            return getDistanceToInterface(a, b)
        }
        Class curr = b
        int distance = 0

        while (curr != a)
        {
            distance++
            curr = curr.superclass
            if (curr == null)
            {   // No inheritance relationship between the two classes
                return Integer.MAX_VALUE
            }
        }

        return distance
    }

    protected static int getDistanceToInterface(Class<?> to, Class<?> from)
    {
        Set<Class<?>> possibleCandidates = new LinkedHashSet<>()

        Class<?>[] interfaces = from.interfaces
        // is the interface direct inherited or via interfaces extends interface?
        for (Class<?> interfase : interfaces)
        {
            if (to.equals(interfase))
            {
                return 1
            }
            // because of multi-inheritance from interfaces
            if (to.isAssignableFrom(interfase))
            {
                possibleCandidates.add(interfase)
            }
        }

        // it is also possible, that the interface is included in superclasses
        if (from.superclass != null && to.isAssignableFrom(from.superclass))
        {
            possibleCandidates.add(from.superclass)
        }

        int minimum = Integer.MAX_VALUE;
        for (Class<?> candidate : possibleCandidates)
        {
            // Could do that in a non recursive way later
            int distance = getDistanceToInterface(to, candidate)
            if (distance < minimum)
            {
                minimum = ++distance;
            }
        }
        return minimum;
    }

    /**
     * @param c Class to test
     * @return boolean true if the passed in class is a Java primitive, false otherwise.  The Wrapper classes
     * Integer, Long, Boolean, etc. are consider primitives by this method.
     */
    static boolean isPrimitive(Class c)
    {
        return c.isPrimitive() || prims.contains(c)
    }

    /**
     * @param c Class to test
     * @return boolean true if the passed in class is a 'logical' primitive.  A logical primitive is defined
     * as all Java primitives, the primitive wrapper classes, String, Number, and Class.  The reason these are
     * considered 'logical' primitives is that they are immutable and therefore can be written without references
     * in JSON content (making the JSON more readable - less @id / @ref), without breaking the semantics (shape)
     * of the object graph being written.
     */
    static boolean isLogicalPrimitive(Class c)
    {
        return c.isPrimitive() ||
                prims.contains(c) ||
                String.class == c ||
                Number.class.isAssignableFrom(c) ||
                Date.class.isAssignableFrom(c) ||
                c == Class
    }

    /**
     * Return the Class with the given name.  The short name for primitives can be used, as well as 'string',
     * 'date', and 'class'.
     */
    static Class classForName(String name)
    {
        if (name == null || name.isEmpty())
        {
            throw new IllegalArgumentException("Class name cannot be null or empty.");
        }
        Class c = nameToClass[name]
        return c == null ? loadClass(name) : c
    }

    // loadClass() provided by: Thomas Margreiter
    private static Class loadClass(String name) throws ClassNotFoundException
    {
        String className = name
        boolean arrayType = false
        Class primitiveArray = null

        while (className.startsWith("["))
        {
            arrayType = true
            if (className.endsWith(";"))
            {
                className = className.substring(0, className.length() - 1)
            }
            switch (className)
            {
                case "[B":
                    primitiveArray = ([] as byte[]).class
                    break
                case "[S":
                    primitiveArray = ([] as short[]).class
                    break
                case "[I":
                    primitiveArray = ([] as int[]).class
                    break
                case "[J":
                    primitiveArray = ([] as long[]).class
                    break
                case "[F":
                    primitiveArray = ([] as float[]).class
                    break
                case "[D":
                    primitiveArray = ([] as double[]).class
                    break
                case "[Z":
                    primitiveArray = ([] as boolean[]).class
                    break
                case "[C":
                    primitiveArray = ([] as char[]).class
                    break
            }
            int startpos = className.startsWith("[L") ? 2 : 1
            className = className.substring(startpos)
        }
        Class currentClass = null
        if (null == primitiveArray)
        {
            currentClass = Thread.currentThread().contextClassLoader.loadClass(className)
        }

        if (arrayType)
        {
            currentClass = (null != primitiveArray) ? primitiveArray : Array.newInstance(currentClass, 0).getClass()
            while (name.startsWith("[["))
            {
                currentClass = Array.newInstance(currentClass, 0).getClass()
                name = name.substring(1)
            }
        }
        return currentClass
    }

    static Object newInstance(Class c, Closure errorHandler) throws IOException
    {
        if (unmodifiableSortedMap.getClass().isAssignableFrom(c))
        {
            return new TreeMap()
        }
        if (unmodifiableMap.getClass().isAssignableFrom(c))
        {
            return [:]
        }
        if (unmodifiableSortedSet.getClass().isAssignableFrom(c))
        {
            return new TreeSet()
        }
        if (unmodifiableSet.getClass().isAssignableFrom(c))
        {
            return new LinkedHashSet()
        }
        if (unmodifiableCollection.getClass().isAssignableFrom(c))
        {
            return []
        }

        // Constructor not cached, go find a constructor
        Object[] constructorInfo = constructors[(c)]
        if (constructorInfo != null)
        {   // Constructor was cached
            Constructor constructor = (Constructor) constructorInfo[0]

            if (constructor == null && useUnsafe)
            {   // null constructor --> set to null when object instantiated with unsafe.allocateInstance()
                try
                {
                    return unsafe.allocateInstance(c)
                }
                catch (Exception e)
                {
                    // Should never happen, as the code that fetched the constructor was able to instantiate it once already
                    errorHandler("Could not instantiate " + c.getName(), e)
                }
            }

            Boolean useNull = (Boolean) constructorInfo[1]
            Class[] paramTypes = constructor.parameterTypes
            if (paramTypes == null || paramTypes.length == 0)
            {
                try
                {
                    return constructor.newInstance()
                }
                catch (Exception e)
                {   // Should never happen, as the code that fetched the constructor was able to instantiate it once already
                    errorHandler("Could not instantiate " + c.getName(), e)
                }
            }
            Object[] values = fillArgs(paramTypes, useNull, errorHandler)
            try
            {
                return constructor.newInstance(values)
            }
            catch (Exception e)
            {   // Should never happen, as the code that fetched the constructor was able to instantiate it once already
                errorHandler("Could not instantiate " + c.getName(), e)
            }
        }

        Object[] ret = newInstanceEx(c, errorHandler)
        constructors[(c)] = [ret[1], ret[2]] as Object[]
        return ret[0]
    }

    /**
     * Return constructor and instance as elements 0 and 1, respectively.
     */
    private static Object[] newInstanceEx(Class c, Closure errorHandler) throws IOException
    {
        try
        {
            Constructor constructor = c.getConstructor(emptyClassArray)
            if (constructor != null)
            {
                return [constructor.newInstance(), constructor, true] as Object[]
            }
            return tryOtherConstruction(c, errorHandler)
        }
        catch (Exception e)
        {
            // OK, this class does not have a public no-arg constructor.  Instantiate with
            // first constructor found, filling in constructor values with null or
            // defaults for primitives.
            return tryOtherConstruction(c, errorHandler)
        }
    }

    private static Object[] tryOtherConstruction(Class c, Closure errorHandler) throws IOException
    {
        Constructor[] constructors = c.declaredConstructors
        if (constructors.length == 0)
        {
            errorHandler("Cannot instantiate '" + c.getName() + "' - Primitive, interface, array[] or void")
        }

        // Try each constructor (private, protected, or public) with null values for non-primitives.
        for (Constructor constructor : constructors)
        {
            constructor.accessible = true
            Class[] argTypes = constructor.parameterTypes
            Object[] values = fillArgs(argTypes, true, errorHandler)
            try
            {
                return [constructor.newInstance(values), constructor, true] as Object[]
            }
            catch (Exception ignored)
            { }
        }

        // Try each constructor (private, protected, or public) with non-null values for primitives.
        for (Constructor constructor : constructors)
        {
            constructor.accessible = true
            Class[] argTypes = constructor.parameterTypes
            Object[] values = fillArgs(argTypes, false, errorHandler)
            try
            {
                return [constructor.newInstance(values), constructor, false] as Object[]
            }
            catch (Exception ignored)
            { }
        }

        // Try instantiation via unsafe
        // This may result in heapdumps for e.g. ConcurrentHashMap or can cause problems when the class is not initialized
        // Thats why we try ordinary constructors first
        if (useUnsafe)
        {
            try
            {
                return [unsafe.allocateInstance(c), null, null] as Object[]
            }
            catch (Exception ignored)
            { }
        }

        errorHandler("Could not instantiate " + c.getName() + " using any constructor")
        return null
    }

    protected static Object[] fillArgs(Class[] argTypes, boolean useNull, Closure errorHandler) throws IOException
    {
        final Object[] values = new Object[argTypes.length]
        for (int i = 0; i < argTypes.length; i++)
        {
            final Class argType = argTypes[i]
            if (isPrimitive(argType))
            {
                values[i] = newPrimitiveWrapper(argType, null, errorHandler)
            }
            else if (useNull)
            {
                values[i] = null
            }
            else
            {
                if (argType == String.class)
                {
                    values[i] = ""
                }
                else if (argType == Date.class)
                {
                    values[i] = new Date()
                }
                else if (List.class.isAssignableFrom(argType))
                {
                    values[i] = []
                }
                else if (SortedSet.class.isAssignableFrom(argType))
                {
                    values[i] = new TreeSet()
                }
                else if (Set.class.isAssignableFrom(argType))
                {
                    values[i] = new LinkedHashSet()
                }
                else if (SortedMap.class.isAssignableFrom(argType))
                {
                    values[i] = new TreeMap()
                }
                else if (Map.class.isAssignableFrom(argType))
                {
                    values[i] = [:]
                }
                else if (Collection.class.isAssignableFrom(argType))
                {
                    values[i] = []
                }
                else if (Calendar.class.isAssignableFrom(argType))
                {
                    values[i] = Calendar.instance
                }
                else if (TimeZone.class.isAssignableFrom(argType))
                {
                    values[i] = TimeZone.default
                }
                else if (argType == BigInteger.class)
                {
                    values[i] = BigInteger.TEN
                }
                else if (argType == BigDecimal.class)
                {
                    values[i] = BigDecimal.TEN
                }
                else if (argType == StringBuilder.class)
                {
                    values[i] = new StringBuilder()
                }
                else if (argType == StringBuffer.class)
                {
                    values[i] = new StringBuffer()
                }
                else if (argType == Locale.class)
                {
                    values[i] = Locale.FRANCE  // overwritten
                }
                else if (argType == Class.class)
                {
                    values[i] = String.class
                }
                else if (argType == Timestamp.class)
                {
                    values[i] = new Timestamp(System.currentTimeMillis())
                }
                else if (argType == java.sql.Date.class)
                {
                    values[i] = new java.sql.Date(System.currentTimeMillis())
                }
                else if (argType == URL.class)
                {
                    values[i] = new URL("http://localhost") // overwritten
                }
                else if (argType == Object.class)
                {
                    values[i] = new Object()
                }
                else
                {
                    values[i] = null
                }
            }
        }

        return values
    }

    protected static Object newPrimitiveWrapper(Class c, Object rhs, Closure errorHandler) throws IOException
    {
        final String cname
        try
        {
            cname = c.getName()
            switch(cname)
            {
                case "boolean":
                case "java.lang.Boolean":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "false"
                        }
                        return Boolean.parseBoolean((String)rhs)
                    }
                    return rhs != null ? rhs : Boolean.FALSE
                case "byte":
                case "java.lang.Byte":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "0"
                        }
                        return Byte.parseByte((String)rhs)
                    }
                    return rhs != null ? byteCache[((Number) rhs).byteValue() + 128] : (byte) 0
                case "char":
                case "java.lang.Character":
                    if (rhs == null)
                    {
                        return (char)'\u0000'
                    }
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "\u0000"
                        }
                        return valueOf(((String) rhs).charAt(0))
                    }
                    if (rhs instanceof Character)
                    {
                        return rhs
                    }
                    break
                case "double":
                case "java.lang.Double":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "0.0"
                        }
                        return Double.parseDouble((String)rhs)
                    }
                    return rhs != null ? rhs : 0.0d
                case "float":
                case "java.lang.Float":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "0.0f"
                        }
                        return Float.parseFloat((String)rhs)
                    }
                    return rhs != null ? ((Number) rhs).floatValue() : 0.0f
                case "int":
                case "java.lang.Integer":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "0"
                        }
                        return Integer.parseInt((String)rhs)
                    }
                    return rhs != null ? ((Number) rhs).intValue() : 0
                case "long":
                case "java.lang.Long":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "0"
                        }
                        return Long.parseLong((String)rhs)
                    }
                    return rhs != null ? rhs : 0L
                case "short":
                case "java.lang.Short":
                    if (rhs instanceof String)
                    {
                        rhs = removeLeadingAndTrailingQuotes((String) rhs)
                        if ("".equals(rhs))
                        {
                            rhs = "0"
                        }
                        return Short.parseShort((String)rhs)
                    }
                    return rhs != null ? ((Number) rhs).shortValue() : (short) 0
            }
        }
        catch (Exception e)
        {
            String className = c == null ? 'null' : c.getName();
            return errorHandler("Error creating primitive wrapper instance for Class: " + className, e);
        }

        return errorHandler("Class '" + cname + "' requested for special instantiation - isPrimitive() does not match newPrimitiveWrapper()")
    }

    static String removeLeadingAndTrailingQuotes(String s)
    {
        Matcher m = extraQuotes.matcher(s)
        if (m.find())
        {
            s = m.group(2)
        }
        return s
    }

    /**
     * This is a performance optimization.  The lowest 128 characters are re-used.
     *
     * @param c char to match to a Character.
     * @return a Character that matches the passed in char.  If the value is
     *         less than 127, then the same Character instances are re-used.
     */
    static Character valueOf(char c)
    {
        return c <= 127 ? charCache[(int) c] : c
    }

    /**
     * Wrapper for unsafe, decouples direct usage of sun.misc.* package.
     * @author Kai Hufenback
     */
    static final class Unsafe
    {
        private final def sunUnsafe
        private final Method allocateInstance

        /**
         * Constructs unsafe object, acting as a wrapper.
         * @throws ReflectiveOperationException
         */
        public Unsafe() throws ReflectiveOperationException
        {
            try
            {
                Constructor<Unsafe> unsafeConstructor = classForName("sun.misc.Unsafe").getDeclaredConstructor()
                unsafeConstructor.setAccessible(true)
                sunUnsafe = unsafeConstructor.newInstance()
                allocateInstance = sunUnsafe.getClass().getMethod("allocateInstance", Class.class)
                allocateInstance.setAccessible(true)
            }
            catch(Exception e)
            {
                throw new ReflectiveOperationException(e)
            }
        }

        /**
         * Creates an object without invoking constructor or initializing variables.
         * <b>Be careful using this with JDK objects, like URL or ConcurrentHashMap this may bring your VM into troubles.</b>
         * @param clazz to instantiate
         * @return allocated Object
         */
        public Object allocateInstance(Class clazz)
        {
            try
            {
                return allocateInstance.invoke(sunUnsafe, clazz)
            }
            catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
            {
                throw new RuntimeException(e)
            }
        }
    }
}
