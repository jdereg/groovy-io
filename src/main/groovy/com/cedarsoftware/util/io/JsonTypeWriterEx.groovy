package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

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
interface JsonTypeWriterEx extends JsonTypeWriterBase
{
    String JSON_WRITER = "JSON_WRITER"

    void write(Object o, boolean showType, Writer output, Map<String, Object> args) throws IOException

    class Support
    {
        static GroovyJsonWriter getWriter(Map<String, Object> args)
        {
            return (GroovyJsonWriter) args.get(JSON_WRITER)
        }
    }
}
