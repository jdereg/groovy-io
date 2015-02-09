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
class TestUsingSunMisc
{
    static class ShouldBeImpossibleToInstantiate
    {
        private x = 0;
        ShouldBeImpossibleToInstantiate()
        {
            throw new RuntimeException("Go away")
        }
    }

    @Test
    void testCustomTopReaderShoe() throws IOException
    {
        GroovyJsonReader.addReader(Dog.Shoe.class, new GroovyJsonReader.JsonClassReader() {
            public Object read(Object jOb, Deque<JsonObject<String, Object>> stack) throws IOException
            {
                // no need to do anything special
                return Dog.Shoe.construct()
            }
        })
        Dog.Shoe shoe = Dog.Shoe.construct()

        // Dirty Workaround otherwise
        Object[] array = new Object[1]
        array[0] = shoe;
        String workaroundString = GroovyJsonWriter.objectToJson(array)
        GroovyJsonReader.jsonToJava(workaroundString)// shoe can be accessed by
        // checking array type + length
        // and accessing [0]

        String json = GroovyJsonWriter.objectToJson(shoe)
        //Should not fail, as we defined our own reader
        // It is expected, that this object is instantiated twice:
        // -once for analysis + Stack
        // -deserialization with Stack
        GroovyJsonReader.jsonToJava(json)
    }

    @Test
    void testDirectCreation() throws Exception
    {
        GroovyJsonReader.useUnsafe = true;
        // this test will fail without directCreation
        Dog.OtherShoe shoe = Dog.OtherShoe.construct()
        Dog.OtherShoe oShoe = (Dog.OtherShoe) GroovyJsonReader.jsonToJava((GroovyJsonWriter.objectToJson(shoe)))
        assertTrue(shoe.equals(oShoe))
        oShoe = (Dog.OtherShoe) GroovyJsonReader.jsonToJava((GroovyJsonWriter.objectToJson(shoe)))
        assertTrue(shoe.equals(oShoe))

        try
        {
            GroovyJsonReader.useUnsafe = false;
            shoe = Dog.OtherShoe.construct()
            GroovyJsonReader.jsonToJava((GroovyJsonWriter.objectToJson(shoe)))
            fail()
        }
        catch (NullPointerException ignored)
        {
        }

        GroovyJsonReader.useUnsafe = true;
        // this test will fail without directCreation
        Dog.OtherShoe.construct()
        oShoe = (Dog.OtherShoe) GroovyJsonReader.jsonToJava((GroovyJsonWriter.objectToJson(shoe)))
        assertTrue(shoe.equals(oShoe))
    }

    @Test
    void testImpossibleClass() throws Exception
    {
        try
        {
            ShouldBeImpossibleToInstantiate s = new ShouldBeImpossibleToInstantiate()
            fail()
        }
        catch (Exception e)
        {
            e.message.toLowerCase().concat("go away")
        }

        String json = '{"@type":"' + ShouldBeImpossibleToInstantiate.class.name + '", "x":50}'
        try
        {
            GroovyJsonReader.jsonToJava(json)
            fail()
        }
        catch (Exception e)
        {
            e.message.toLowerCase().concat("go away")
        }

        GroovyJsonReader.useUnsafe = true
        ShouldBeImpossibleToInstantiate s = GroovyJsonReader.jsonToJava(json)
        assert s.x == 50
        GroovyJsonReader.useUnsafe = false
    }
}
