package com.cedarsoftware.util.io

import java.sql.Timestamp
import java.text.Format
import java.text.SimpleDateFormat

/**
 * All special writers for groovy-io are stored here.  Special writers are not needed for handling
 * user-defined classes.  However, special writers are built/supplied by groovy-io for many of the
 * primitive types and other JDK classes simply to allow for a more concise form.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br/>
 *         Copyright (c) Cedar Software LLC
 *         <br/><br/>
 *         Licensed under the Apache License, Version 2.0 (the "License");
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
class Writers
{
    static class TimeZoneWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            TimeZone cal = (TimeZone) obj
            output.write('"zone":"')
            output.write(cal.ID)
            output.write('"')
        }

        boolean hasPrimitiveForm() { return false }
        void writePrimitiveForm(Object o, Writer output) {}
    }

    static class CalendarWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            Calendar cal = (Calendar) obj
            MetaUtils.dateFormat.get().timeZone = cal.timeZone
            output.write('"time":"')
            output.write(MetaUtils.dateFormat.get().format(cal.time))
            output.write('","zone":"')
            output.write(cal.timeZone.ID)
            output.write('"')
        }

        boolean hasPrimitiveForm() { return false }
        void writePrimitiveForm(Object o, Writer output) {}
    }

    static class DateWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            Date date = (Date)obj
            Object dateFormat = args[DATE_FORMAT]
            if (dateFormat instanceof String)
            {   // Passed in as String, turn into a SimpleDateFormat instance to be used throughout this stream write.
                dateFormat = new SimpleDateFormat((String) dateFormat, Locale.ENGLISH)
                args[DATE_FORMAT] = dateFormat
            }
            if (showType)
            {
                output.write('"value":')
            }

            if (dateFormat instanceof Format)
            {
                output.write('"')
                output.write(((Format) dateFormat).format(date))
                output.write('"')
            }
            else
            {
                output.write(Long.toString(((Date) obj).time))
            }
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            if (args.containsKey(DATE_FORMAT))
            {
                write(o, false, output)
            }
            else
            {
                output.write(Long.toString(((Date) o).time))
            }
        }
    }

    static class TimestampWriter implements JsonTypeWriter {
        void write(Object o, boolean showType, Writer output)
        {
            Timestamp tstamp = (Timestamp) o
            output.write('"time":"')
            long x = (long) tstamp.time / 1000L
            output.write(Long.toString(x * 1000L))
            output.write('","nanos":"')
            output.write(Integer.toString(tstamp.nanos))
            output.write('"')
        }

        boolean hasPrimitiveForm() { return false }

        void writePrimitiveForm(Object o, Writer output) { }
    }

    static class ClassWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            String value = ((Class) obj).name
            output.write('"value":')
            writeJsonUtf8String(value, output)
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            writeJsonUtf8String(((Class) o).name, output)
        }
    }

    static class JsonStringWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            output.write('"value":')
            writeJsonUtf8String((String) obj, output)
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            writeJsonUtf8String((String) o, output)
        }
    }

    static class LocaleWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            Locale locale = (Locale) obj

            output.write('"language":"')
            output.write(locale.language)
            output.write('","country":"')
            output.write(locale.country)
            output.write('","variant":"')
            output.write(locale.variant)
            output.write('"')
        }
        boolean hasPrimitiveForm() { return false }
        void writePrimitiveForm(Object o, Writer output) { }
    }

    static class BigIntegerWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            BigInteger big = (BigInteger) obj
            output.write('"value":"')
            output.write(big.toString(10))
            output.write('"')
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            BigInteger big = (BigInteger) o
            output.write('"')
            output.write(big.toString(10))
            output.write('"')
        }
    }

    static class BigDecimalWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            BigDecimal big = (BigDecimal) obj
            output.write('"value":"')
            output.write(big.toPlainString())
            output.write('"')
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            BigDecimal big = (BigDecimal) o
            output.write('"')
            output.write(big.toPlainString())
            output.write('"')
        }
    }

    static class StringBuilderWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            StringBuilder builder = (StringBuilder) obj
            output.write('"value":"')
            output.write(builder.toString())
            output.write('"')
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            StringBuilder builder = (StringBuilder) o
            output.write('"')
            output.write(builder.toString())
            output.write('"')
        }
    }

    static class StringBufferWriter implements JsonTypeWriter {
        void write(Object obj, boolean showType, Writer output)
        {
            StringBuffer buffer = (StringBuffer) obj;
            output.write('"value":"')
            output.write(buffer.toString())
            output.write('"')
        }

        boolean hasPrimitiveForm() { return true }

        void writePrimitiveForm(Object o, Writer output)
        {
            StringBuffer buffer = (StringBuffer) o
            output.write('"')
            output.write(buffer.toString())
            output.write('"')
        }
    }

    // ========== Maintain relationship aware methods below ==========
    static final String DATE_FORMAT = GroovyJsonWriter.DATE_FORMAT

    protected static void writeJsonUtf8String(String s, final Writer output)
    {
        GroovyJsonWriter.writeJsonUtf8String(s, output)
    }

    protected static Map getArgs()
    {
        return GroovyJsonWriter.args
    }
}
