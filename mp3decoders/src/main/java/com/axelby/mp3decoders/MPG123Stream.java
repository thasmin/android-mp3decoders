package com.axelby.mp3decoders;

public class MPG123Stream extends MPG123 {
	static {
		MPG123.initializeLibrary();
	}

	public MPG123Stream() { _handle = openStream(); }
	public void feed(byte[] buffer, int count) { MPG123.feed(_handle, buffer, count); }
	@Override public int readSamples(short[] buffer) { return MPG123.readFrame(_handle, buffer); }
	public int seekFrameOffset(float position) { return MPG123.seekFrameOffset(_handle, position); }

}
