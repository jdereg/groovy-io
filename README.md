groovy-io
=======

Perfect [Groovy](http://groovy.codehaus.org/) serialization to and from JSON format (available on [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cgroovy-io)). To include in your project:
```
<dependency>
  <groupId>com.cedarsoftware</groupId>
  <artifactId>groovy-io</artifactId>
  <version>1.0.3</version>
</dependency>
```
<a class="coinbase-button" data-code="f5ab44535dc53e81b79e71f123ebdf42" data-button-style="custom_large" data-custom="json-io" href="https://coinbase.com/checkouts/f5ab44535dc53e81b79e71f123ebdf42">Feed hungry developers...</a><script src="https://coinbase.com/assets/button.js" type="text/javascript"></script>

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

### Customization
New APIs have been added to allow you to associate a custom reader / writer class to a particular class if you want it to be read / written specially in the JSON output.  This approach allows you to customize the JSON format for classes for which you do not have the source code.

#### Dates
To specify an alternative date format for `GroovyJsonWriter`:

    Map args = [(GroovyJsonWriter.DATE_FORMAT):GroovyJsonWriter.ISO_DATE_TIME]
    String json = GroovyJsonWriter.objectToJson(root, args)

In this example, the ISO `yyyy/MM/ddTHH:mm:ss` format is used to format dates in the JSON output. The 'value' associated to the 'DATE_FORMAT' key can be `GroovyJsonWriter.ISO_DATE_TIME`, `GroovyJsonWriter.ISO_DATE`, a date format String pattern (eg. `yyyy/MM/dd HH:mm`), or a `java.text.Format` instance.

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
 * 1.0.3
  * More simplifications to source code using more of Groovy's capabilities.
 * 1.0.2
  * Initial release

by John DeRegnaucourt
