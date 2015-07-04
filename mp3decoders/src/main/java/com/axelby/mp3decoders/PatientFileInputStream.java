package com.axelby.mp3decoders;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * file input stream that waits for more data if there are none available
 * call interrupt or interrupt thread to stop
 */
public class PatientFileInputStream extends FileInputStream {
	protected boolean _interrupted = false;

	public PatientFileInputStream(File file) throws FileNotFoundException {
		super(file);
	}

	@SuppressWarnings("unused")
	public PatientFileInputStream(FileDescriptor fd) {
		super(fd);
	}

	@SuppressWarnings("unused")
	public PatientFileInputStream(String path) throws FileNotFoundException {
		super(path);
	}

	@SuppressWarnings("unused")
	public void interrupt() { _interrupted = true; }

	// keep thread sleeping until
	private void waitForAvailable() throws IOException {
		while (!_interrupted && available() <= 0) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	@Override
	public int read() throws IOException {
		waitForAvailable();
		return super.read();
	}

	@Override
	public int read(@NotNull byte[] buffer, int byteOffset, int byteCount) throws IOException {
		waitForAvailable();
		return super.read(buffer, byteOffset, byteCount);
	}

	@Override
	public int read(@NotNull byte[] buffer) throws IOException {
		waitForAvailable();
		return super.read(buffer);
	}
}
