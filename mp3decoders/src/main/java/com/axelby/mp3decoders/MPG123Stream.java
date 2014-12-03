package com.axelby.mp3decoders;

public class MPG123Stream extends MPG123 {
	static {
		MPG123.initializeLibrary();
	}

	public MPG123Stream() { _handle = openStream(); }
	public void feed(byte[] buffer, int count) { MPG123.feed(_handle, buffer, count); }

}
