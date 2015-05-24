package com.cedarsoftware.util.io

import org.junit.Test

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
class TestInnerClass2
{
    @Test
    void testChangedClass()
    {
        Dog dog = new Dog()
        dog.x = 10
//        Dog.Leg leg = dog.new Dog.Leg()       // original Java
        Dog.Leg leg = Dog.createLeg(dog)        // Groovy requires static method on outer class to create non-static inner instance
        leg.y = 20
        String json0 = TestUtil.getJsonString(dog)
        TestUtil.printLine("json0=" + json0)
        JsonObject job = (JsonObject) GroovyJsonReader.jsonToMaps(json0)
        job.put("phantom", new TestObject("Eddie"))
        String json1 = TestUtil.getJsonString(job)
        TestUtil.printLine("json1=" + json1)
        assert json1.contains("phantom")
        assert json1.contains("TestObject")
        assert json1.contains("_other")
    }
}
