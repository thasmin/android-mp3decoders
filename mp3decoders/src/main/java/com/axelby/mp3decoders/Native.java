package com.axelby.mp3decoders;

public class Native {
	protected static native void init();

	static {
		System.loadLibrary("podax-native");
		Native.init();
	}
}
