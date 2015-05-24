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
class TestInnerClass1
{
    static public class A
    {
        public String a;

        class B
        {

            public String b;

            public B()
            {
                // No args constructor for B
            }
        }

        static B createB(A aa)
        {
            new B(aa)
        }
    }

    @Test
    void testInner()
    {
        A a = new A()
        a.a = "aaa"

        String json = TestUtil.getJsonString(a)
        TestUtil.printLine("json = " + json)
        A o1 = (A) TestUtil.readJsonObject(json)
        assert o1.a.equals("aaa")

//        TestJsonReaderWriter.A.B b = a.new B()        // Original Java
        TestInnerClass1.A.B b = A.createB(a)
        // Groovy requires static method on outer class to create non-static inner instance
        b.b = "bbb"
        json = TestUtil.getJsonString(b)
        TestUtil.printLine("json = " + json)
        TestInnerClass1.A.B o2 = (TestInnerClass1.A.B) TestUtil.readJsonObject(json)
        assert o2.b.equals("bbb")
    }
}