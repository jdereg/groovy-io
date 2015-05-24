package com.cedarsoftware.util.io

import org.junit.Test

import static org.junit.Assert.assertTrue
import static org.junit.Assert.fail

/**
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
class TestErrors
{
    @Test
    void testBadJson()
    {
        Object o = null;

        try
        {
            o = TestUtil.readJsonObject('["bad JSON input"')
            fail()
        }
        catch(Exception e)
        {
            assertTrue(e.message.contains("inside"))
        }
        assertTrue(o == null)
    }

    @Test
    void testParseMissingQuote()
    {
        try
        {
            String json = '''{
  "array": [
    1,
    2,
    3
  ],
  "boolean": true,
  "null": null,
  "number": 123,
  "object": {
    "a": "b",
    "c": "d",
    "e": "f"
  },
  "string: "Hello World"
}'''
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("Expected ':' between string field and value"))
        }
    }
    @Test
    void testParseInvalid1stChar()
    {
        try
        {
            String json = '''
  "array": [
    1,
    2,
    3
  ],
  "boolean": true,
  "null": null,
  "number": 123,
  "object": {
    "a": "b",
    "c": "d",
    "e": "f"
  },
  "string:" "Hello World"
}'''
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("nknown JSON value type"))
        }
    }

    @Test
    void testParseMissingLastBrace()
    {
        try
        {
            String json = """{
  "array": [
    1,
    2,
    3
  ],
  "boolean": true,
  "null": null,
  "number": 123,
  "object": {
    "a": "b",
    "c": "d",
    "e": "f"
  },
  "string": "Hello World"
"""
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("EOF reached before closing '}'"))
        }
    }

    @Test
    void testParseMissingLastBracket()
    {
        String json = '[true, "bunch of ints", 1,2, 3 , 4, 5 , 6,7,8,9,10]'
        GroovyJsonReader.jsonToGroovy(json)

        try
        {
            json = '[true, "bunch of ints", 1,2, 3 , 4, 5 , 6,7,8,9,10'
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("xpected ',' or ']' inside array"))
        }
    }

    @Test
    void testParseBadValueInArray()
    {
        String json

        try
        {
            json = '[true, "bunch of ints", 1,2, 3 , 4, 5 , 6,7,8,9,\'a\']'
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("nknown JSON value type"))
        }
    }

    @Test
    void testParseObjectWithoutClosingBrace()
    {
        try
        {
            String json = '{"key": true{'
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("Object not ended with '}'"))
        }
    }

    @Test
    void testParseBadHex()
    {
        try
        {
            String json = '"\\u5h1t"'
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("Expected hexadecimal digits"))
        }
    }

    @Test
    void testParseBadEscapeChar()
    {
        try
        {
            String json = '"What if I try to escape incorrectly \\L1CK"'
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("nvalid character escape sequence specified"))
        }
    }

    @Test
    void testParseUnfinishedString()
    {
        try
        {
            String json = '"This is an unfinished string...'
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("EOF reached while reading JSON string"))
        }
    }

    @Test
    void testParseEOFInToken()
    {
        try
        {
            String json = "falsz"
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("xpected token: false"))
        }
    }

    @Test
    void testParseEOFReadingToken()
    {
        try
        {
            String json = "tru"
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("EOF reached while reading token"))
        }
    }

    @Test
    void testParseEOFinArray()
    {
        try
        {
            String json = "[true, false,"
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.contains("EOF reached prematurely"))
        }
    }

    @Test
    void testMalformedJson()
    {
        String json;

        try
        {
            json = '{"field"0}'  // colon expected between fields
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = "{field:0}"  // not quoted field name
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '{"field":0'  // object not terminated correctly (ending in number)
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '{"field":true'  // object not terminated correctly (ending in token)
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '{"field":"test"'  // object not terminated correctly (ending in string)
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '{"field":{}'  // object not terminated correctly (ending in another object)
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '{"field":[]'  // object not terminated correctly (ending in an array)
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '{"field":3.14'  // object not terminated correctly (ending in double precision number)
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '[1,2,3'
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e)  { }

        try
        {
            json = "[false,true,false"
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            json = '["unclosed string]'
            GroovyJsonReader.jsonToMaps(json)
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testBadType()
    {
        try
        {
            String json = '{"@type":"non.existent.class.Non"}'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.toLowerCase().contains("unable"))
            assertTrue(e.message.toLowerCase().contains("create"))
            assertTrue(e.message.toLowerCase().contains("class"))
        }

        // Bad class inside a Collection
        try
        {
            String json = '{"@type":"java.util.ArrayList","@items":[null, true, {"@type":"bogus.class.Name"}]}'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e)
        {
            assertTrue(e.message.toLowerCase().contains("class listed"))
            assertTrue(e.message.toLowerCase().contains("@type"))
            assertTrue(e.message.toLowerCase().contains("not found"))
        }
    }

    @Test
    void testBadHexNumber()
    {
        StringBuilder str = new StringBuilder()
        str.append("[\"\\")
        str.append("u000r\"]")
        try
        {
            TestUtil.readJsonObject(str.toString())
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testBadValue()
    {
        try
        {
            String json = '{"field":19;}'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            String json = '{"field":'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            String json = '{"field":joe'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            String json = '{"field":trux}'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e) { }

        try
        {
            String json = '{"field":tru'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testStringEscape()
    {
        String json = '["escaped slash \\\' should result in a single /"]'
        TestUtil.readJsonObject(json)

        json = '["escaped slash \\/ should result in a single /"]'
        TestUtil.readJsonObject(json)

        try
        {
            json = '["escaped slash \\x should result in a single /"]'
            TestUtil.readJsonObject(json)
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testClassMissingValue()
    {
        try
        {
            TestUtil.readJsonObject('[{"@type":"class"}]')
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testCalendarMissingValue()
    {
        try
        {
            TestUtil.readJsonObject('[{"@type":"java.util.Calendar"}]')
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testBadFormattedCalendar()
    {
        try
        {
            TestUtil.readJsonObject('[{"@type":"java.util.GregorianCalendar","value":"2012-05-03T12:39:45.1X5-0400"}]')
            fail()
        }
        catch (Exception e) { }
    }

    @Test
    void testEmptyClassName()
    {
        try
        {
            TestUtil.readJsonObject('{"@type":""}')
            fail()
        }
        catch(Exception e) { }
    }

    @Test
    void testBadBackRef()
    {
        try
        {
            TestUtil.readJsonObject('{"@type":"java.util.ArrayList","@items":[{"@ref":1}]}')
            fail()
        }
        catch(Exception e) { }
    }

    @Test
    void testErrorReporting()
    {
        String json = '[{"@type":"funky"},\n{"field:"value"]'
        try
        {
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e) { }
    }
}
