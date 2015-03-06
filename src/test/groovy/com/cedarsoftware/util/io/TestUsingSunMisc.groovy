package com.cedarsoftware.util.io

import groovy.transform.CompileStatic
import org.junit.Test

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
@CompileStatic
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
    void testCustomTopReaderShoe()
    {
        GroovyJsonReader.addReader(Dog.Shoe.class, new JsonTypeReader() {
            public Object read(Object jOb, Deque<JsonObject<String, Object>> stack)
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
        GroovyJsonReader.jsonToGroovy(workaroundString)// shoe can be accessed by
        // checking array type + length
        // and accessing [0]

        String json = GroovyJsonWriter.objectToJson(shoe)
        //Should not fail, as we defined our own reader
        // It is expected, that this object is instantiated twice:
        // -once for analysis + Stack
        // -deserialization with Stack
        GroovyJsonReader.jsonToGroovy(json)
    }

    @Test
    void testDirectCreation() throws Exception
    {
        // sun.misc.Unsafe does not appear to work with Groovy in @CompileStatic mode
        MetaUtils.useUnsafe = true;
        // this test will fail without directCreation
        Dog.OtherShoe shoe = Dog.OtherShoe.construct()
//        Dog.OtherShoe oShoe = (Dog.OtherShoe) GroovyJsonReader.jsonToGroovy((GroovyJsonWriter.objectToJson(shoe)))
//        assert shoe.equals(oShoe)
//        oShoe = (Dog.OtherShoe) GroovyJsonReader.jsonToGroovy((GroovyJsonWriter.objectToJson(shoe)))
//        assert shoe.equals(oShoe)
//
//        try
//        {
//            MetaUtils.useUnsafe = false;
//            shoe = Dog.OtherShoe.construct()
//            GroovyJsonReader.jsonToGroovy((GroovyJsonWriter.objectToJson(shoe)))
//            fail()
//        }
//        catch (NullPointerException ignored)
//        {
//        }
//
//        MetaUtils.useUnsafe = true;
//        // this test will fail without directCreation
//        Dog.OtherShoe.construct()
//        oShoe = (Dog.OtherShoe) GroovyJsonReader.jsonToGroovy((GroovyJsonWriter.objectToJson(shoe)))
//        assert shoe.equals(oShoe)
    }

    @Test
    void testImpossibleClass() throws Exception
    {
        // sun.misc.Unsafe does not appear to work with Groovy in @CompileStatic mode
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
            GroovyJsonReader.jsonToGroovy(json)
            fail()
        }
        catch (Exception e)
        {
            e.message.toLowerCase().concat("go away")
        }

        MetaUtils.setUseUnsafe(true)
//        ShouldBeImpossibleToInstantiate s = (ShouldBeImpossibleToInstantiate) GroovyJsonReader.jsonToGroovy(json)
//        assert s.x == 50
//        MetaUtils.setUseUnsafe(false)
    }
}
