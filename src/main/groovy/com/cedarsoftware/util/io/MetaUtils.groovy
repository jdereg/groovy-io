package com.cedarsoftware.util.io

import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap

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
class MetaUtils
{
    private static final Map<Class, Map<String, Field>> classMetaCache = new ConcurrentHashMap<>()
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
}
