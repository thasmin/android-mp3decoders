package com.axelby.mp3decoders;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MainActivityTest {

	@Test
	public void testReadStreamedFile() throws IOException, InterruptedException {
		final File f = File.createTempFile("test", "txt");

		Thread writer = new Thread(new Runnable() {
			@Override
			public void run() {
				FileWriter out = null;
				try {
					out = new FileWriter(f);
					out.write("123");
					Thread.sleep(1000);
					out.write("abc");
				} catch (IOException | InterruptedException e) {
					Assert.fail("writer thread fail: " + e.getMessage());
				} finally {
					try {
						if (out != null)
							out.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}

			}
		});
		writer.start();

		Thread reader = new Thread(new Runnable() {
			@Override
			public void run() {

				byte[] b = new byte[3];
				int read = 0;
				PatientFileInputStream in = null;
				try {
					in = new PatientFileInputStream(f);

					while (read < 6) {
						read += in.read(b);
						if (read == 3) {
							Assert.assertArrayEquals("first read 123", new byte[]{'1', '2', '3'}, b);
						} else if (read == 6) {
							Assert.assertArrayEquals("second read abc", new byte[]{'a', 'b', 'c'}, b);
						} else {
							Assert.fail("read wrong number of bytes");
						}
					}
				} catch (IOException e) {
					Assert.fail("reader thread fail: " + e.getMessage());
				} finally {
					if (in != null)
						try {
							in.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
				}

			}

		});
		reader.start();

		writer.join();
		reader.join();
	}
}