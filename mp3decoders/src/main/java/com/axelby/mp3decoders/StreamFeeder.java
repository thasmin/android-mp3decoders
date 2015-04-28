package com.axelby.mp3decoders;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;

/**
* pipes data from a file to the decoder
*/
public class StreamFeeder {
	final String _filename;
	final IMediaDecoder _decoder;

	Thread _feederThread = null;
	long _fileOffset = 0;

	private static ArrayList<String> _doneFiles = new ArrayList<>(2);
	public static void doneStreamingFile(String filename) { if (!_doneFiles.contains(filename)) _doneFiles.add(filename); }
	public static void clearDoneFiles() { _doneFiles.clear(); }
	
	public StreamFeeder(String filename, IMediaDecoder decoder) throws FileNotFoundException {
		this(filename, decoder, 0);
	}

	public StreamFeeder(String filename, IMediaDecoder decoder, long initialOffset) throws FileNotFoundException {
		_filename = filename;
		_decoder = decoder;
		_fileOffset = initialOffset;
		_feederThread = new Thread(_feederRunnable, "feeder");
		_feederThread.start();
	}

	public String getFilename() { return _filename; }

	public void finish() {
		_feederThread.interrupt();
		try {
			_feederThread.join();
		} catch (InterruptedException ignored) {}
	}

	Runnable _feederRunnable = new Runnable() {
		@Override
		public void run() {
			RandomAccessFile file = null;
			try {
				// make sure the file exists
				if (!new File(_filename).exists()) {
					Thread.sleep(50);
				}
				file = new RandomAccessFile(_filename, "r");

				file.seek(_fileOffset);
				while (file.getFilePointer() != _fileOffset)
					Thread.sleep(50);

				while (true) {
					// read the available bytes from the file and feed them to the mp3 decoder
					long length = file.length();
					int size = (int) (length - file.getFilePointer());
					if (size == 0 && _doneFiles.contains(_filename))
						break;
					if (size == 0) {
						Thread.sleep(50);
						continue;
					}

					byte[] c = new byte[size];
					int read = file.read(c);
					if (read > 0)
						_decoder.feed(c, read);

					Thread.sleep(50);
				}

				_decoder.completeStream();
			} catch (IOException e) {
				Log.e("mp3decoders", "unable to feed decoder", e);
			} catch (InterruptedException ignored) {
			} finally {
				try {
					if (file != null)
						file.close();
				} catch (IOException e) {
					Log.e("mp3decoders", "unable to close feed decoder file", e);
				}
			}
		}
	};
}
