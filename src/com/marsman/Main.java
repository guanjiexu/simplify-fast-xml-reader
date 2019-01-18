package com.marsman;

import java.io.IOException;

public class Main {
	public static int nodeCount;

    public static void main(String[] args) throws IOException, InterruptedException {
	    XmlReader reader = new XmlReader(args[0]);
	    Thread.currentThread().setPriority(7);
		long size = reader.fileSize();
		byte[] nodeArr = XmlReader.stringToBytes("node");
	    reader.listen(XmlReader.EVENT_TYPE.START, (status) -> {
			if(status.testTag(nodeArr)) {
				nodeCount++;
			}
        });
	    reader.listen(XmlReader.EVENT_TYPE.END, (status) -> {

		});
	    reader.listen(XmlReader.EVENT_TYPE.TEXT, (status) -> {

		});
	    long last = System.currentTimeMillis();
	    reader.read();
	    System.out.println(nodeCount);
	    System.out.println(size*1000/(System.currentTimeMillis() - last)/1024/1024);
    }
}
