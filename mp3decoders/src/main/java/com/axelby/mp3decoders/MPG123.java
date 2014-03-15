package com.axelby.mp3decoders;

public class MPG123 {
	static {
		System.loadLibrary("mpg123");
	}

	static boolean _initialized = false;
	private static native int init();
	private static native String getErrorMessage(int error);
	private static native long openFile(String filename);
	private static native void delete(long handle);
	private static native int readSamples(long handle, short[] buffer, int offset, int numSamples);
	private static native int skipSamples(long handle, int numSamples);
	private static native int seek(long handle, long sampleOffset);
	private static native long getPosition(long handle);
	private static native long getPositionInFrames(long handle);
	private static native double getSecondsPerFrame(long handle);
	private static native int getNumChannels(long handle);
	private static native int getRate(long handle);
	private static native long getLength(long handle);
	private static native long getOutputBlockSize(long handle);
	public static native int[] getSupportedRates();

	long _handle = 0;
	public MPG123(String filename) {
		if (!_initialized)
			MPG123.init();

		_handle = openFile(filename);
	}

	public void close() {
		if (_handle != 0)
			MPG123.delete(_handle);
	}

	public int readSamples(short[] buffer, int offset, int numSamples) {
		return MPG123.readSamples(_handle, buffer, offset, numSamples);
	}
	public int skipSamples(int numSamples) {
		return MPG123.skipSamples(_handle, numSamples);
	}
	public int seek(long sampleOffset) {
		return MPG123.seek(_handle, sampleOffset);
	}
	public long getPositionInFrames() {
		return MPG123.getPositionInFrames(_handle);
	}
	public long getPosition() {
		return MPG123.getPosition(_handle);
	}
	public double getSecondsPerFrame() {
		return MPG123.getSecondsPerFrame(_handle);
	}
	public int getNumChannels() {
		return MPG123.getNumChannels(_handle);
	}
	public int getRate() {
		return MPG123.getRate(_handle);
	}
	public long getLength() {
		return MPG123.getLength(_handle);
	}
	public long getOutputBlockSize() {
		return MPG123.getOutputBlockSize(_handle);
	}
}
