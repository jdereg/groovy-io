package com.cedarsoftware.util.io

/**
 * The MapResolver converts the raw Maps created from the GroovyJsonParser to higher
 * quality Maps representing the implied object graph.  It does this by replace
 * @ref values with the Map with an @id key with the same value.
 *
 * This approach 'rewires' the original object graph.  During the resolution process,
 * if 'peer' classes can be found for given Maps (for example, an @type entry is
 * available which indicates the class that would have been associated to the Map,
 * then the associated class is consulted to help 'improve' the quality of the primitive
 * values within the map fields.  For example, if the peer class indicated that a field
 * was of type 'short', and the Map had a long value (JSON only returns long's for integer
 * types), then the long would be converted to a short.
 *
 * The final Map representation is a very high-quality graph that represents the original
 * JSON graph.  It can be passed as input to JsonWriter, and the JsonWriter will write
 * out the equivalent JSON to what was originally read.  This technique allows groovy-io to
 * be used on a machine that does not have any of the Groovy classes from the original graph,
 * read it in a JSON graph (any JSON graph), return the equivalent maps, allow mutations of
 * those maps, and finally this graph can be written out.
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
class MapResolver extends Resolver
{
}
