groovy-io
=======

Perfect [Groovy](http://groovy.codehaus.org/) serialization to and from JSON format (available on [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cgroovy-io)). To include in your project:
```
<dependency>
  <groupId>com.cedarsoftware</groupId>
  <artifactId>groovy-io</artifactId>
  <version>1.1.0</version>
</dependency>
```
[Donations welcome](https://coinbase.com/jdereg)

**groovy-io** consists of two main classes, a reader (`GroovyJsonReader`) and a writer (`GroovyJsonWriter`).  **groovy-io** eliminates the need for using `ObjectInputStream / ObjectOutputStream` to serialize objects and instead uses the JSON format.  There is a 3rd optional class (`JsonObject`) see 'Non-typed Usage' below.

**groovy-io** does not require that classes implement `Serializable` or `Externalizable` to be serialized, unlike `ObjectInputStream` / `ObjectOutputStream`.  It will serialize any object graph into JSON and retain complete graph semantics / shape and object types.  This includes supporting private fields, private inner classes (static or non-static), of any depth.  It also includes handling cyclic references.  Objects do not need to have public constructors to be serialized.  The output JSON will not include `transient` fields, identical to the ObjectOutputStream behavior.  This can be overidden if needed.

The `GroovyJsonReader / GroovyJsonWriter` code does not depend on any native or 3rd party libraries.

_For useful and powerful Java utilities, check out java-util at http://github.com/jdereg/java-util_

### Format
**groovy-io** uses proper JSON format.  As little type information is included in the JSON format to keep it compact as possible.  When an object's class can be inferred from a field type or array type, the object's type information is left out of the stream.  For example, a `String[]` looks like `["abc", "xyz"]`.

When an object's type must be emitted, it is emitted as a meta-object field `"@type":"package.class"` in the object.  When read, this tells the GroovyJsonReader what class to instantiate.  On the Javascript side, this can be ignored.

If an object is referenced more than once, or references an object that has not yet been defined, (say A points to B, and B points to C, and C points to A), it emits a `"@ref":n` where 'n' is the object's integer identity (with a corresponding meta entry `"@id":n` defined on the referenced object).  Only referenced objects have IDs in the JSON output, reducing the JSON String length.

### Performance
**groovy-io** was written with performance in mind.  As the tests run, a log is written of the time it takes to serialize / deserialize and compares it to `ObjectInputStream / ObjectOutputStream`.

### Usage
**groovy-io** can be used directly on JSON Strings or with Groovy's Streams.

_Example 1: String to Groovy object_

    Object obj = GroovyJsonReader.jsonToGroovy('["Hello, World"]')

This will convert the JSON String to a Groovy Object graph.  In this case, it would consist of an `Object[]` of one `String` element.

_Example 2: Groovy object to JSON String_

    Employee emp
    // Emp fetched from database
    String json = GroovyJsonWriter.objectToJson(emp)

This example will convert the `Employee` instance to a JSON String.  If the `GroovyJsonReader` were used on this `String`, it would reconstitute a Groovy `Employee` instance.

_Example 3: `InputStream` to Groovy object_

    GroovyJsonReader jr = new GroovyJsonReader(inputStream)
    Employee emp = (Employee) jr.readObject()

In this example, an `InputStream` (could be from a File, the Network, etc.) is supplying an unknown amount of JSON.  The `GroovyJsonReader` is used to wrap the stream to parse it, and return the Groovy object graph it represents.

_Example 4: Groovy Object to `OutputStream`_

    Employee emp
    // emp obtained from database
    GroovyJsonWriter jw = new GroovyJsonWriter(outputStream)
    jw.write(emp)
    jw.close()

In this example, a Groovy object is written to an output stream in JSON format.

### Non-typed Usage
**groovy-io** provides the choice to use the generic "Map of Maps" representation of an object graph, akin to a Javascript associative array.  When reading from a JSON String or `InputStream` of JSON, the `GroovyJsonReader` can be constructed like this:

    Map graph = GroovyJsonReader.jsonToMaps(String json)

-- or --

    GroovyJsonReader jr = new GroovyJsonReader(InputStream, true)
    Map map = (Map) jr.readObject()

This will return an untyped object representation of the JSON String as a `Map` of Maps, where the fields are the `Map` keys (Strings), and the field values are the associated Map's values. In this representation the `Map` instance returned is actually a `JsonObject` instance (from **groovy-io**).  This `JsonObject` implements the `Map` interface permitting access to the entire object.  Cast to a `JsonObject`, you can see the type information, position within the JSON stream, and other information.

This 'Maps' representation can be re-written to a JSON String or Stream and _the output JSON will be equivalent to the original input JSON stream_.  This permits a JVM receiving JSON strings / streams that contain class references which do not exist in the JVM that is parsing the JSON, to completely read / write the stream.  Additionally, the Maps can be modified before being written, and the entire graph can be re-written in one collective write.  _Any object model can be read, modified, and then re-written by a JVM that does not contain any of the classes in the JSON data!_

### Optional Arguments (Features)

Both the `GroovyJsonWriter` and `GroovyJsonReader` allow you to pass in an optional arguments `Map<String, Object>`.  This `Map` has well known keys (constants from `GroovyJsonWriter` / `GroovyJsonWriter`).  To enable the respective feature, first create a `Map`.  Then place the well known key in the `Map` and associate the appropriate setting as the value.  Below is a complete list of features and some example usages.  Shown in Groovy for brevity.

    Map args = [
            (GroovyJsonWriter.SHORT_META_KEYS):true,
            (GroovyJsonWriter.TYPE_NAME_MAP):[
                'java.util.ArrayList':'alist', 
                'java.util.LinkedHashMap':'lmap', 
                (TestObject.class.getName()):'testO'
            ]
    ]
    String json = GroovyJsonWriter.objectToJson(list, args)
        
In this example, we create an 'args' `Map`, set the key `GroovyJsonWriter.SHORT_META_KEYS` to `true` and set the `GroovyJsonWriter.TYPE_NAME_MAP` to a `Map` that will be used to substitute class names for short-hand names.         

#### All of the values below are public constants from `GroovyJsonWriter`, used by placing them as keys in the arguments map.

    DATE_FORMAT             // Set this format string to control the format dates are 
                            // written. Example: "yyyy/MM/dd HH:mm".  Can also be a 
                            // DateFormat instance.  Can also be the constant 
                            // JsonWriter.ISO_DATE_FORMAT or 
                            // JsonWriter.ISO_DATE_TIME_FORMAT 
    TYPE                    // Set to boolean true to force all data types to be 
                            // output, even where they could have been omitted.
    PRETTY_PRINT            // Force nicely formatted JSON output 
                            // (See http://jsoneditoronline.org for example format)
    FIELD_SPECIFIERS        // Set to a Map<Class, List<String>> which is used to 
                            // control which fields of a class are output. 
    ENUM_PUBLIC_ONLY        // If set, indicates that private variables of ENUMs are not 
                            // serialized.
    WRITE_LONGS_AS_STRINGS  // If set, longs are written in quotes (Javascript safe).
                            // JsonReader will automatically convert Strings back
                            // to longs.  Any Number can be set from a String.
    TYPE_NAME_MAP           // If set, this map will be used when writing @type values.
                            // Allows short-hand abbreviations for type names.
    SHORT_META_KEYS         // If set, then @type => @t, @keys => @k, @items => @e,
                            // @ref => @r, and @id => @i 

#### All of the values below are public constants from `GroovyJsonReader`, used by placing them as keys in the arguments map.

    USE_MAPS        // If set to boolean true, the read-in JSON will be 
                    // turned into a Map of Maps (JsonObject) representation. Note
                    // that calling the JsonWriter on this root Map will indeed
                    // write the equivalent JSON stream as was read.
    TYPE_NAME_MAP   // If set, this map will be used when writing @type values. 
                    // Allows short-hand abbreviations of type names.
    UNKNOWN_TYPE    // Set to null (or leave out), unknown objects are returned as Maps.
                    // Set to String class name, and unknown objects will be created
                    // as with this class name, and the fields will be set on it.
                    // Set to false, and an exception will be thrown when an unknown
                    // object type is encountered.  The location in the JSON will
                    // be given.

### Customization
New APIs have been added to allow you to associate a custom reader / writer class to a particular class if you want it to be read / written specially in the JSON output.  This approach allows you to customize the JSON format for classes for which you do not have the source code.

### Javascript
Included is a small Javascript utility that will take a JSON output stream created by the JSON writer and substitute all `@ref's` for the actual pointed to object.  It's a one-line call - `resolveRefs(json)`.  This will substitute `@ref` tags in the JSON for the actual pointed-to object.  In addition, the `@keys` / `@items` will also be converted into Javascript Maps and Arrays.  Finally, there is a Javascript API that will convert a full Javascript object graph to JSON, (even if it has cycles within the graph).  This will maintain the proper graph-shape when sending it from the client back to the server.

### What's next?
Even though **groovy-io** is perfect for Groovy / Javascript serialization, there are other great uses for it:

### Cloning
Many projects use `GroovyJsonWriter` to write an object to JSON, then use the `GroovyJsonReader` to read it in, perfectly cloning the original object graph:

    Employee emp
    // emp obtained from database
    Employee deepCopy = (Employee) cloneObject(emp)

    public Object cloneObject(Object root)
    {
        return GroovyJsonReader.jsonToGroovy(GroovyJsonWriter.objectToJson(root))
    }

### Debugging
Instead of doing `println` debugging, call `GroovyJsonWriter.objectToJson(obj)` and print that String out.  It will reveal the object graph in all it's glory.

### Pretty-Printing JSON
Use `GroovyJsonWriter.formatJson()` API to format a passed in JSON string to a nice, human readable format.  Also, when writing JSON data, use the `GroovyJsonWriter.objectToJson(o, args)` API, where args is a `Map` with a key of `GroovyJsonWriter.PRETTY_PRINT` and a value of 'true' (`boolean` or `String`).  When run this way, the JSON written by the `GroovyJsonWriter` will be formatted in a nice, human readable format.

### RESTful support
**groovy-io** can be used as the fundamental data transfer method between a Javascript / JQuery / Ajax client and a web server in a RESTful fashion. Used this way, you can create more active sites like Google's GMail, MyOtherDrive online backup, etc.

See https://github.com/jdereg/json-command-servlet for a light-weight servlet that processes Ajax / XHR calls.

Featured on http://json.org.
 * 1.1.0 
  * `GroovyJsonReader.UNKNOWN_OBJECT` added as an option to indicate what to do when an unknown object is encountered in the JSON.  Default is a `Map` will be created.  However, you can set this argument to a `String` class name to instantiate, or set it to false to force an exception to be thrown.
  * **New Feature**: Short class names to reduce the size of the output JSON. This allows you to, for example, substitute `java.util.HashMap` with `hmap` so that it will appear in the JSON as `"@type":"hmap"`.  Pass the substitution map to the `GroovyJsonWriter` (or reader) as an entry in the args `Map` with the key of `GroovyJsonWriter.TYPE_NAME_MAP` and the value as a `Map` instance with String class names as the keys and short-names as the values. The same map can be passed to the `GroovyJsonReader` and it will properly read the substituted types.
  * **New Feature**: Short meta-key names to reduce the size of the output JSON.  The `@type` key name will be shortened to `@t`, `@id` => `@i`, `@ref` => `@r`, `@keys` => `@k`, `@items` => `@e`.  Put a key in the `args` `Map` as `GroovyJsonWriter.SHORT_META_KEYS` with the value `true`.   
 * 1.0.7
  * Bug fix: Using a CustomReader in a Collection with at least two identical elements causes an exception (submitted by @KaiHufenbach).    
 * 1.0.6
  * Added new flag `GroovyJsonWriter.WRITE_LONGS_AS_STRINGS` which forces long/Long's to be written as Strings.  When sending JSON data to a Javascript, longs can lose precision because Javascript only maintains 53-bits of info (Javascript uses IEEE 754 `double` for numbers).  The precision is lost due to some of the bits used for maintaining an exponent.  With this flag set, longs will be sent as Strings, however, on return back to a Java server, json-io allows Strings to be set right back into long (fields, array elements, collections, etc.)
 * 1.0.5
   * Performance improvement: caching the custom reader / writers associated to given classes.
   * Ease of use: `groovy-io` throws a `JsonIoException` (unchecked) instead of checked exception `IOException`.  This allows more flexibility in terms of error handling for the user.
   * Code cleanup: Moved reflection related code from `GroovyJsonReader` into separate `MetaUtils` class.
   * Code cleanup: Moved `FastPushbackReader` from `GroovyJsonReader` into separate class.
   * Code cleanup: Moved JSON parsing code from `GroovyJsonReader` into separate `JsonParser` class.
   * Code cleanup: Moved built-in readers from `GroovyJsonReader` to separate `Readers` class.
   * Code cleanup: Moved resolver code (code that marshals map of maps to Java instances) into separate `Resolver` classes.
 * 1.0.4
   * `GroovyJsonReader.newInstance()` API made public
   * Bumped version of junit from 4.11 to 4.12
   * Added additional tests to ensure that null and "" can be properly assigned to primitive values (matching behavior of java-util's `Converter.convert()` API).
 * 1.0.3
  * More simplifications to source code using more of Groovy's capabilities.
 * 1.0.2
  * Initial release

by John DeRegnaucourt
