# simplify-fast-xml-reader
read big xml, about 150mb/s speed up to my hardware read speed

See Main.java for usage

```java
// init
// args[0] for file path
 XmlReader reader = new XmlReader(args[0]);
 Thread.currentThread().setPriority(7);
 long size = reader.fileSize();
 // buffer the string you want to handle
 byte[] nodeArr = XmlReader.stringToBytes("node");
 reader.listen(XmlReader.EVENT_TYPE.START, (status) -> {
    // if the tag name is node
    if(status.testTag(nodeArr)) {
		  nodeCount++;
      // to string will cost a lot
      XmlReader.bytesToString(status.getAttr(lastAttr));
    }
 });
reader.listen(XmlReader.EVENT_TYPE.END, (status) -> {

});
reader.listen(XmlReader.EVENT_TYPE.TEXT, (status) -> {

});
long last = System.currentTimeMillis();
// start the task blocked
reader.read();
System.out.println(nodeCount);
System.out.println(size*1000/(System.currentTimeMillis() - last)/1024/1024);
```
