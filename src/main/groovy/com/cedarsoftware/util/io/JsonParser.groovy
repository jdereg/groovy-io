package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

/**
 * Parse the JSON input stream supplied by the FastPushbackReader to the constructor.
 * The entire JSON input stream will be read until it is emptied: an EOF (-1) is read.
 *
 * While reading the content, Maps (JsonObjects) are used to hold the contents of
 * JSON objects { }.  Lists are used to hold the contents of JSON arrays.  Each object
 * that has an @id field will be copied into the supplied 'objectsMap' constructor
 * argument.  This allows the user of this class to locate any referenced object
 * directly.
 *
 * When this parser completes, the @ref objects (references to objects identified
 * with @id) are stored as a JsonObject with an @ref as the key and the ID value of
 * the object. No substitution has yet occurred (substituting the @ref pointers with
 * a Groovy reference to the actual Map (Map containing the @id)).
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
@CompileStatic
class JsonParser
{
    static final String EMPTY_OBJECT = '~!o~'  // compared with ==
    private static final int STATE_READ_START_OBJECT = 0
    private static final int STATE_READ_FIELD = 1
    private static final int STATE_READ_VALUE = 2
    private static final int STATE_READ_POST_VALUE = 3
    private static final String EMPTY_ARRAY = '~!a~'  // compared with ==
    private static final Map<String, String> stringCache = [
            '':'',
            'true':'true',
            'True':'True',
            'TRUE':'TRUE',
            'false':'false',
            'False':'False',
            'FALSE':'FALSE',
            'null':'null',
            'yes':'yes',
            'Yes':'Yes',
            'YES':'YES',
            'no':'no',
            'No':'No',
            'NO':'NO',
            'on':'on',
            'On':'On',
            'ON':'ON',
            'off':'off',
            'Off':'Off',
            'OFF':'OFF',
            '@id':'@id',
            '@ref':'@ref',
            '@items':'@items',
            '@type':'@type',
            '@keys':'@keys',
            '0':'0',
            '1':'1',
            '2':'2',
            '3':'3',
            '4':'4',
            '5':'5',
            '6':'6',
            '7':'7',
            '8':'8',
            '9':'9'
    ]

    private final FastPushbackReader input
    private final Map<Long, JsonObject> objsRead
    private final StringBuilder strBuf = new StringBuilder()
    private final StringBuilder hexBuf = new StringBuilder()
    private final char[] numBuf = new char[256]
    private final boolean useMaps
    private final Map<String, String> typeNameMap

    JsonParser(FastPushbackReader reader, Map<Long, JsonObject> objectsMap, Map<String, Object> args)
    {
        input = reader
        objsRead = objectsMap
        useMaps = Boolean.TRUE.equals(args.get(GroovyJsonReader.USE_MAPS))
        typeNameMap = (Map<String, String>) args.get(GroovyJsonReader.TYPE_NAME_MAP_REVERSE)
    }

    private Object readJsonObject()
    {
        boolean done = false
        String field = null
        JsonObject<String, Object> object = new JsonObject<>()
        int state = STATE_READ_START_OBJECT
        final FastPushbackReader inp = input

        while (!done)
        {
            int c
            switch (state)
            {
                case STATE_READ_START_OBJECT:
                    c = skipWhitespaceRead()
                    if (c == '{')
                    {
                        object.line = inp.line
                        object.column = inp.col
                        c = skipWhitespaceRead()
                        if (c == '}')
                        {    // empty object
                            return EMPTY_OBJECT
                        }
                        inp.unread(c)
                        state = STATE_READ_FIELD
                    }
                    else
                    {
                        error("Input is invalid JSON; object does not start with '{', c=" + c)
                    }
                    break

                case STATE_READ_FIELD:
                    c = skipWhitespaceRead()
                    if (c == '"')
                    {
                        field = readString()
                        c = skipWhitespaceRead()
                        if (c != ':')
                        {
                            error("Expected ':' between string field and value")
                        }
                        skipWhitespace()

                        if (field.startsWith('@'))
                        {   // Expand short-hand meta keys
                            switch(field)
                            {
                                case '@t': field = stringCache['@type']
                                    break
                                case '@i': field = stringCache['@id']
                                    break
                                case '@r': field = stringCache['@ref']
                                    break
                                case '@k': field = stringCache['@keys']
                                    break
                                case '@e': field = stringCache['@items']
                                    break
                            }
                        }
                        state = STATE_READ_VALUE
                    }
                    else
                    {
                        error("Expected quote")
                    }
                    break

                case STATE_READ_VALUE:
                    if (field == null)
                    {	// field is null when you have an untyped Object[], so we place
                        // the JsonArray on the @items field.
                        field = "@items"
                    }

                    Object value = readValue(object)
                    if ("@type".equals(field) && typeNameMap != null)
                    {
                        final String substitute = typeNameMap[value as String]
                        if (substitute != null)
                        {
                            value = substitute
                        }
                    }
                    object[field] = value

                    // If object is referenced (has @id), then put it in the _objsRead table.
                    if ("@id".equals(field))
                    {
                        objsRead[(Long) value] = object
                    }
                    state = STATE_READ_POST_VALUE
                    break

                case STATE_READ_POST_VALUE:
                    c = skipWhitespaceRead()
                    if (c == -1)
                    {
                        error("EOF reached before closing '}'")
                    }
                    if (c == '}')
                    {
                        done = true
                    }
                    else if (c == ',')
                    {
                        state = STATE_READ_FIELD
                    }
                    else
                    {
                        error("Object not ended with '}'")
                    }
                    break
            }
        }

        if (useMaps && object.isPrimitive())
        {
            return object.primitiveValue
        }

        return object
    }

    protected Object readValue(JsonObject object)
    {
        final int c = input.read()
        switch(c as char)
        {
            case '"':
                return readString()
            case '{':
                input.unread(c)
                return readJsonObject()
            case '[':
                return readArray(object)
            case ']':   // empty array
                input.unread(c)
                return EMPTY_ARRAY
            case 'f':
            case 'F':
                input.unread(c)
                readToken("false")
                return Boolean.FALSE
            case 'n':
            case 'N':
                input.unread(c)
                readToken("null")
                return null
            case 't':
            case 'T':
                input.unread(c)
                readToken("true")
                return Boolean.TRUE
            case -1 as char:
                error("EOF reached prematurely")
        }

        if (c >= 0x30 && c <= 0x39 || c == '-')
        {
            return readNumber(c)
        }
        return error("Unknown JSON value type")
    }

    /**
     * Read a JSON array
     */
    private Object readArray(JsonObject object)
    {
        final Collection array = new ArrayList()

        while (true)
        {
            skipWhitespace()
            final Object o = readValue(object)
            if (!EMPTY_ARRAY.is(o))
            {
                array.add(o)
            }
            final int c = skipWhitespaceRead()

            if (c == ']')
            {
                break
            }
            else if (c != ',')
            {
                error("Expected ',' or ']' inside array")
            }
        }

        return array.toArray()
    }

    /**
     * Return the specified token from the reader.  If it is not found,
     * throw an Exception indicating that.  Converting to c to
     * (char) c is acceptable because the 'tokens' allowed in a
     * JSON input stream (true, false, null) are all ASCII.
     */
    private void readToken(String token)
    {
        final int len = token.length()

        for (int i = 0; i < len; i++)
        {
            int c = input.read()
            if (c == -1)
            {
                error("EOF reached while reading token: " + token)
            }
            c = Character.toLowerCase(c as char)
            int loTokenChar = token.charAt(i)

            if (loTokenChar != c)
            {
                error("Expected token: " + token)
            }
        }
    }

    /**
     * Read a JSON number
     *
     * @param c int a character representing the first digit of the number that
     *          was already read.
     * @return a Number (a Long or a Double) depending on whether the number is
     *         a decimal number or integer.  This choice allows all smaller types (Float, int, short, byte)
     *         to be represented as well.
     */
    private Number readNumber(int c)
    {
        final FastPushbackReader inp = input
        final char[] buffer = this.numBuf
        buffer[0] = c as char
        int len = 1
        boolean isFloat = false

        try
        {
            while (true)
            {
                c = inp.read()
                if ((c >= 0x30 && c <= 0x39) || c == '-' || c == '+')     // isDigit() inlined for speed here
                {
                    buffer[len++] = c as char
                }
                else if (c == '.' || c == 'e' || c == 'E')
                {
                    buffer[len++] = c as char
                    isFloat = true
                }
                else if (c == -1)
                {
                    break
                }
                else
                {
                    inp.unread(c)
                    break
                }
            }
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            error("Too many digits in number: " + new String(buffer))
        }

        if (isFloat)
        {   // Floating point number needed
            String num = new String(buffer, 0, len)
            try
            {
                return (Number)Double.parseDouble(num)
            }
            catch (NumberFormatException e)
            {
                error("Invalid floating point number: " + num, e)
            }
        }
        boolean isNeg = buffer[0] == '-'
        long n = 0

        for (int i = (isNeg ? 1 : 0); i < len; i++)
        {
            n = (buffer[i] - (char)'0') + n * 10
        }
        return (Number) (isNeg ? -n : n)
    }

    private static final int STATE_STRING_START = 0
    private static final int STATE_STRING_SLASH = 1
    private static final int STATE_HEX_DIGITS_START = 2
    private static final int STATE_HEX_DIGITS = 3

    /**
     * Read a JSON string
     * This method assumes the initial quote has already been read.
     *
     * @return String read from JSON input stream.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    private String readString()
    {
        final StringBuilder str = this.strBuf
        str.length = 0
        boolean done = false
        int state = STATE_STRING_START

        while (!done)
        {
            final int c = input.read()
            if (c == -1)
            {
                error("EOF reached while reading JSON string")
            }

            switch (state)
            {
                case STATE_STRING_START:
                    if (c == '"')
                    {
                        done = true
                    }
                    else if (c == '\\')
                    {
                        state = STATE_STRING_SLASH
                    }
                    else
                    {
                        str.appendCodePoint(c)
                    }
                    break

                case STATE_STRING_SLASH:
                    switch(c as char)
                    {
                        case '\\':
                            str.append('\\')
                            break
                        case '/':
                            str.append('/')
                            break
                        case '"':
                            str.append('"')
                            break
                        case '\'':
                            str.append('\'')
                            break
                        case 'b':
                            str.append('\b')
                            break
                        case 'f':
                            str.append('\f')
                            break
                        case 'n':
                            str.append('\n')
                            break
                        case 'r':
                            str.append('\r')
                            break
                        case 't':
                            str.append('\t')
                            break
                        case 'u':
                            state = STATE_HEX_DIGITS_START
                            break
                        default:
                            error("Invalid character escape sequence specified: " + c)
                    }

                    if (c != 'u')
                    {
                        state = STATE_STRING_START
                    }
                    break

                case STATE_HEX_DIGITS_START:
                    hexBuf.length = 0;
                    state = STATE_HEX_DIGITS;   // intentional 'fall-thru'
                case STATE_HEX_DIGITS:
                    switch(c as char)
                    {
                        case '0':
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                        case '8':
                        case '9':
                        case 'A':
                        case 'B':
                        case 'C':
                        case 'D':
                        case 'E':
                        case 'F':
                        case 'a':
                        case 'b':
                        case 'c':
                        case 'd':
                        case 'e':
                        case 'f':
                            hexBuf.append(c as char)
                            if (hexBuf.length() == 4)
                            {
                                int value = Integer.parseInt(hexBuf.toString(), 16)
                                str.append(MetaUtils.valueOf(value as char))
                                state = STATE_STRING_START
                            }
                            break
                        default:
                            error("Expected hexadecimal digits")
                    }
                    break
            }
        }

        final String s = str.toString()
        final String cacheHit = stringCache[s]
        return cacheHit == null ? s : cacheHit
    }


    /**
     * Read until non-whitespace character and then return it.
     * This saves extra read/pushback.
     *
     * @return int representing the next non-whitespace character in the stream.
     * @throws java.io.IOException for stream errors or parsing errors.
     */
    private int skipWhitespaceRead()
    {
        final FastPushbackReader inp = input
        int c = inp.read()
        while (true)
        {
            switch (c as char)
            {
                case '\t':
                case '\n':
                case '\r':
                case ' ':
                    break
                default:
                    return c
            }

            c = inp.read()
        }
    }

    private void skipWhitespace()
    {
        input.unread(skipWhitespaceRead())
    }

    static Object error(String msg)
    {
        return MetaUtils.error(msg)
    }

    static Object error(String msg, Exception e)
    {
        return MetaUtils.error(msg, e)
    }
}
