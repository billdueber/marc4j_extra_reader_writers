# marc-extra-readers-writers -- extra readers/writers for marc4j

This is a just a place where people can, if they like, stick extra import/export routines that integrate with marc4j in a place outside the marc4j distribution itself. It's mostly for formats that aren't official standards and hence probably don't belong in the core marc4j code.

What's here now:

## MARC-in-JSON conversion to/from marc4j record

MARC-in-JSON is a JSON format for MARC records (just as MARC-XML is an XML format for MARC records, described by Ross Singer at http://dilettantes.code4lib.org/blog/2010/09/a-proposal-to-serialize-marc-in-json/. 

It's nice in that it doesn't have the length limitations of binary marc, can be easy to read, and is well-suited to querying via JSONPath or JSONQuery. It can also quite a bit faster and smaller than using MARC-XML (depending on your environment, obviously).

```java
   import org.marc4j.MarcInJSON;
   
   // Presume you've got a marc4j record object, r
   
   // Turn it into a hash
   HashMap   h = MarcInJSON.record_to_hash(r);   
   
   // Turn it into a hash and then use Jackson to turn it into json
   String json = MarcInJSON.record_to_marc_in_json(r);
   
   // Go the other way
   
   Record r2 = MARCInJSON.new_from_marc_in_json(json);
   Record r3 = MARCInJSON.new_from_hash(h);
```

## Aleph Sequential Reader

Aleph sequential is a format output by Ex Libris' Aleph ILS software. It's a line-based format, and (like MARC-XML) suffers none of the length limitations of binary MARC and is easy for a human to read. Aleph outputs it a *lot* faster than MARC-XML, though, so it can be useful.

Note that only a reader is provided.

By default, the record key is inserted into the record as the sole 001 field. You can stick it elsewhere; see the javadocs.

```java

  import org.marc4j.MarcAlephSequentialReader;
  
  
  MarcAlephSequentialReader reader = new MarcAlephSequentialReader(inputstream);
  while (reader.hasNext()) {
    r = reader.next();
    // blah blah blah
  }
```