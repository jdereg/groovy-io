package com.cedarsoftware.util.io

import org.junit.BeforeClass
import org.junit.Test

class TestCustomReaderIdentity
{
	@BeforeClass
	public static void setUp(){

		GroovyJsonReader.addReader(CustomReaderClass.class,  new JsonTypeReader() {
			public Object read(Object jOb, Deque<JsonObject<String, Object>> stack)
			{
				CustomReaderClass customClass = new CustomReaderClass();
				customClass.setTest("blab");
				return customClass;
			}
		});

	}


	/**
	 * This test uses a customReader to read two identical instances in a collection.
	 * A customWriter is not necessary.
	 */
	@Test
	public void testCustomReaderSerialization(){

		List<CustomReaderClass> elements = new LinkedList<>();
		CustomReaderClass element = new CustomReaderClass();
		element.setTest("hallo");

		elements.add(element);
		elements.add(element);


		String json = GroovyJsonWriter.objectToJson(elements);


		Object obj = GroovyJsonReader.jsonToGroovy(json);

	}

	/**
	 * This test does not use a customReader to read two identical instances in a collection.
	 * A customWriter is not necessary.
	 */
	@Test
	public void testSerializationOld(){
		List<WithoutCustomReaderClass> elements = new LinkedList<>();
		WithoutCustomReaderClass element = new WithoutCustomReaderClass();
		element.setTest("hallo");

		elements.add(element);
		elements.add(element);


		String json = GroovyJsonWriter.objectToJson(elements);


		Object obj = GroovyJsonReader.jsonToGroovy(json);
	}

	@Test
	public void testInSet(){
		Set<WithoutCustomReaderClass> set = new HashSet<>();

		CustomReaderClass element = new CustomReaderClass();
		element.setTest("hallo");

		WithoutCustomReaderClass e1 = new WithoutCustomReaderClass();
		e1.setCustomReaderInner(element);

		WithoutCustomReaderClass e2 = new WithoutCustomReaderClass();
		e2.setCustomReaderInner(element);

		set.add(e1);
		set.add(e2);

		String json = GroovyJsonWriter.objectToJson(set);


		Object obj = GroovyJsonReader.jsonToGroovy(json);
	}

	@Test
	public void testInArray(){
		CustomReaderClass[] array = new CustomReaderClass[2];
		CustomReaderClass element = new CustomReaderClass();
		element.setTest("hallo");

		array[0] = element;
		array[1] = element;

		String json = GroovyJsonWriter.objectToJson(array);


		Object obj = GroovyJsonReader.jsonToGroovy(json);
	}
}
