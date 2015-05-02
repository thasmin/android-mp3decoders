package com.axelby.mp3decoders;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class MainActivity extends Activity {
	private TextView _stateText;
	private TextView _state2Text;

	private float _seekTo = -1f;
	private float _seekbase = 0;
	private AudioTrack _track = null;
	private StreamFeeder _feeder = null;
	private IMediaDecoder _decoder;

	private StreamFeeder _fullFeeder = null;
	private IMediaDecoder _fullDecoder;
	private StreamSkipper _fullSkipper;

	private View.OnClickListener skip1sHandler = new View.OnClickListener() {
		@Override public void onClick(View view) { _seekTo = 1f; }
	};

	private View.OnClickListener skip6sHandler = new View.OnClickListener() {
		@Override public void onClick(View view) { _seekTo = 6f; }
	};

	private View.OnClickListener skipPastEndHandler = new View.OnClickListener() {
		@Override public void onClick(View view) { _seekTo = 20f; }
	};

	private Runnable vorbisRunnable = new Runnable() {
		@Override
		public void run() {
			playFromDecoder(new Vorbis(getFilesDir() + "/loop1_ogg.ogg"));
		}
	};

	private Runnable mpg123Runnable = new Runnable() {
		@Override
		public void run() {
			playStream(new MPG123(getFilesDir() + "/loop1.mp3"), null);
		}
	};

	private Runnable mpg123StreamRunnable = new Runnable() {
		@Override
		public void run() {
			playStream(new MPG123(), getFilesDir() + "/streamed.mp3");
		}
	};

	private void playFromDecoder(IMediaDecoder decoder) {
		_decoder = decoder;

		int rate = _decoder.getRate();
		int numChannels = _decoder.getNumChannels();

		_track = new AudioTrack(AudioManager.STREAM_MUSIC,
				rate,
				numChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				rate * 2,
				AudioTrack.MODE_STREAM);
		_track.setPositionNotificationPeriod(rate);
		_track.setPlaybackPositionUpdateListener(_playbackPositionListener);
		_track.play();
		changeState("playing");

		try {
			short[] pcm = new short[1000 * 5];
			boolean stop = false;
			do {
				if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
					changeState("paused");
					Thread.sleep(50);
					continue;
				}
				int samples = _decoder.readFrame(pcm);
				if (samples > 0)
					_track.write(pcm, 0, samples);
				if (samples == 0) {
					changeState("done");
					stop = true;
				}
			} while (!stop);
		} catch (InterruptedException e) {
			Log.e("mp3decoders", "InterruptedException", e);
		} finally {
			_decoder.close();
			_decoder = null;
			waitAndCloseTrack();
		}

		changeState("finished playing");
	}

	private void playStream(IMediaDecoder decoder, String filename) {
		StreamFeeder.clearDoneFiles();

		_decoder = decoder;
		_seekbase = 0;

		Thread fakeStreamer = null;
		int rate;
		int numChannels;

		boolean isStreaming = filename != null;

		try {
			if (isStreaming) {
				fakeStreamer = new Thread(_fakeStream, "fakeStreamer");
				fakeStreamer.start();
				_fullFeeder = _feeder = new StreamFeeder(filename, decoder);

				// keep the full decoder going for seeking
				_fullDecoder = _decoder;
			}

			// find rate and numchannels - feed until there's enough data for it
			rate = _decoder.getRate();
			numChannels = _decoder.getNumChannels();
			while (rate == 0) {
				Thread.sleep(50);
				rate = _decoder.getRate();
				numChannels = _decoder.getNumChannels();
			}

			// create track
			_track = new AudioTrack(AudioManager.STREAM_MUSIC,
					rate,
					numChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					rate * 2,
					AudioTrack.MODE_STREAM);
			_track.setPositionNotificationPeriod(_decoder.getRate());
			_track.setPlaybackPositionUpdateListener(_playbackPositionListener);
			_track.play();
			changeState("playing");

			short[] pcm = new short[1000 * 5];
			while (true) {
				if (_seekTo >= 0) {
					streamSeekTo(_seekTo);
					_seekTo = -1;
				}

				if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
					Thread.sleep(50);
					continue;
				}

				int samples = _decoder.readFrame(pcm);
				if (samples == -1 && !_decoder.isStreamComplete()) {
					Thread.sleep(50);
					continue;
				}
				if (samples == 0 || (samples == -1 && _decoder.isStreamComplete())) {
					changeState("finished playing");
					break;
				}
				if (samples > 0)
					_track.write(pcm, 0, samples);
			}
		} catch (InterruptedException e) {
			Log.e("mp3decoders", "InterruptedException", e);
		} catch (IOException e) {
			Log.e("mp3decoders", "IOException", e);
		} catch (Exception e) {
			Log.e("mp3decoders", "Exception", e);
		} finally {
			waitAndCloseTrack();

			if (_fullFeeder != null) {
				_fullFeeder.finish();
				_fullFeeder = null;
			}
			if (_feeder != null) {
				_feeder.finish();
				_feeder = null;
			}
			if (fakeStreamer != null) {
				fakeStreamer.interrupt();
				try {
					fakeStreamer.join();
				} catch (InterruptedException ignored) { }
			}
		}

		Log.i("mp3decoders", "done loading audiotrack");
	}

	// trash the current track, seek, wait until seeking is done, then recreate the track
	private void streamSeekTo(float seekToSeconds) throws InterruptedException, FileNotFoundException {
		boolean isStreaming = (_fullDecoder != null);

		_track.pause();
		_track.flush();
		_track.release();
		_track = null;

		int rate = _decoder.getRate();
		int numChannels = _decoder.getNumChannels();

		if (isStreaming) {
			long fileOffset = findSeekFileOffset(seekToSeconds);
			if (fileOffset == -1) {
				Log.e("mp3decoders", "unable to find target offset");
				return;
			}

			// start a new decoder and feeder at the proper offset
			_seekbase = seekToSeconds;
			_decoder = new MPG123();
			_feeder = new StreamFeeder(_feeder.getFilename(), _decoder, fileOffset);

			// continue the original decoder to get later seek offsets
			_fullSkipper = new StreamSkipper(_fullDecoder);
		} else {
			_decoder.seek(seekToSeconds);
		}

		_track = new AudioTrack(AudioManager.STREAM_MUSIC,
				rate,
				numChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				rate * 2,
				AudioTrack.MODE_STREAM);
		_track.setPositionNotificationPeriod(rate);
		_track.setPlaybackPositionUpdateListener(_playbackPositionListener);
		_track.play();
	}

	private long findSeekFileOffset(float seekToSeconds) throws InterruptedException {
		long fileOffset = _fullDecoder.getSeekFrameOffset(seekToSeconds);
		// keep trying to skip frame until offset is found or out of data
		while (fileOffset == -1) {
			boolean skippedFrame = _fullDecoder.skipFrame();
			if (skippedFrame) {
				fileOffset = _fullDecoder.getSeekFrameOffset(seekToSeconds);
				continue;
			}

			// check for out of data
			if (_fullDecoder.isStreamComplete())
				break;
			// sleep to wait for more data
			Thread.sleep(50);
		}
		return fileOffset;
	}

	Runnable _fakeStream = new Runnable() {
		@Override
		public void run() {
			InputStream inStream = null;
			OutputStream outStream = null;
			try {
				File out = new File(getFilesDir() + "/streamed.mp3");
				if (out.exists())
					//noinspection ResultOfMethodCallIgnored
					out.delete();
				//noinspection ResultOfMethodCallIgnored
				out.createNewFile();
				File in = new File(getFilesDir() + "/loop1.mp3");

				byte[] b = new byte[10000];
				int total = 0;
				int read;
				inStream = new FileInputStream(in);
				outStream = new FileOutputStream(out);
				while ((read = inStream.read(b)) != -1) {
					outStream.write(b, 0, read);
					outStream.flush();
					total += read;
					changeState2("downloaded " + total + " bytes");

					Thread.sleep(getStreamSpeedDelay());
				}

				StreamFeeder.doneStreamingFile(out.getAbsolutePath());

				if (_fullFeeder != _feeder) {
					_fullFeeder.finish();
					_fullFeeder = null;
					_fullDecoder.completeStream();
				}
			} catch (IOException | InterruptedException e) {
				Log.e("mp3decoders", "cannot stream file", e);
			} finally {
				try {
					if (inStream != null)
						inStream.close();
				} catch (IOException e) {
					Log.e("mp3decoders", "unable to close fake streamer input stream", e);
				}

				try {
					if (outStream != null)
						outStream.close();
				} catch (IOException e) {
					Log.e("mp3decoders", "unable to close fake streamer output stream", e);
				}
			}
		}
	};

	// sleep for 1000 downloads slightly slower than realtime
	private int getStreamSpeedDelay() {
		RadioGroup speedGroup = (RadioGroup) findViewById(R.id.streamSpeed);
		switch (speedGroup.getCheckedRadioButtonId()) {
			case R.id.streamRealtime: return 250;
			case R.id.streamSlow: return 1000;
			case R.id.streamVerySlow: return 1500;
			default: return 250;
		}
	}

	private void waitAndCloseTrack() {
		if (_track != null) {
			try {
				_track.stop();
				while (_track.getPlaybackHeadPosition() != 0)
					Thread.sleep(10);
			} catch (InterruptedException e) {
				Log.e("mp3decoders", "InterruptedException", e);
			}

			_track.release();
			_track = null;
		}

		if (_decoder != null) {
			_decoder.close();
			_decoder = null;
		}

		if (_fullDecoder != null) {
			_fullDecoder.close();
			_fullDecoder = null;
		}

		if (_fullSkipper != null) {
			_fullSkipper.close();
			_fullSkipper = null;
		}
	}

	private AudioTrack.OnPlaybackPositionUpdateListener _playbackPositionListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioTrack audioTrack) {
			changeState("marker reached");
		}

		@Override
		public void onPeriodicNotification(AudioTrack audioTrack) {
			if (_decoder != null)
				changeState(String.format("periodic notification at %.2f, head position %d",
						_decoder.getPosition() + _seekbase, audioTrack.getPlaybackHeadPosition()));
			else
				changeState("periodic notification at " + audioTrack.getPlaybackHeadPosition());
		}
	};

	private AudioManager.OnAudioFocusChangeListener audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
		@Override
		public void onAudioFocusChange(int state) {
			Log.i("mp3decoders", "audiofocus state " + state);
			if (_track == null)
				return;

			if (state == AudioManager.AUDIOFOCUS_LOSS) {
				_track.pause();
				_track.flush();
			} else if (state == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
					state == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
				_track.pause();
			} else if (state == AudioManager.AUDIOFOCUS_GAIN) {
				_track.play();
			}
		}
	};

	private void changeState(final CharSequence playerState) {
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				_stateText.setText(playerState);
			}
		});
	}

	private void changeState2(final CharSequence playerState) {
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				_state2Text.setText(playerState);
			}
		});
	}

	private View.OnClickListener pauseHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track == null)
				return;

			if (_track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
				Log.i("mp3decoders", "paused");
				_track.pause();
			} else if (_track.getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
				Log.i("mp3decoders", "resumed");
				_track.play();
			}
		}
	};

	private Runnable mediaDecoderRunnable = new Runnable() {
		@Override
		public void run() {
			try {
				MediaExtractor extractor = new MediaExtractor();
				extractor.setDataSource(getFilesDir() + "/loop1.mp3");

				MediaFormat format = extractor.getTrackFormat(0);
				String mime = format.getString(MediaFormat.KEY_MIME);
				int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
				int numChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

				Log.i("mp3decoders", "mime type: " + mime);
				Log.i("mp3decoders", "sample rate: " + sampleRate);
				Log.i("mp3decoders", "num channels: " + numChannels);

				MediaCodec decoder = MediaCodec.createDecoderByType(mime);
				decoder.configure(format, null, null, 0);
				decoder.start();
				changeState("setup mediadecoder");;

				ByteBuffer[] inputBuffers = decoder.getInputBuffers();
				ByteBuffer[] outputBuffers = decoder.getOutputBuffers();

				extractor.selectTrack(0);

				_track = new AudioTrack(AudioManager.STREAM_MUSIC,
						sampleRate,
						numChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
						AudioFormat.ENCODING_PCM_16BIT,
						sampleRate * 2,
						AudioTrack.MODE_STREAM);
				_track.setPositionNotificationPeriod(sampleRate);
				_track.setPlaybackPositionUpdateListener(_playbackPositionListener);
				_track.play();
				changeState("audiotrack setup");

				final int TIMEOUT_US = 10000;
				MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
				boolean inEos = false;
				long firstRead = System.currentTimeMillis();
				int bytesFed = 0;
				while (!Thread.interrupted()) {
					if (_seekTo != -1f) {
						_track.pause();
						_track.flush();
						decoder.flush();
						final int IN_MICROSECONDS = 1000000;
						Log.d("mp3decoders", "preseek sample time: " + extractor.getSampleTime());
						extractor.seekTo((int)(_seekTo * IN_MICROSECONDS), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
						Log.d("mp3decoders", "postseek sample time: " + extractor.getSampleTime());
						_seekTo = -1f;
						_track.play();
					}

					int byteMax = (int) ((System.currentTimeMillis() - firstRead) * getStreamSpeedDecoderFactor());
					boolean readyForRead = byteMax >= bytesFed;
					// input buffers will fill up if decoding while paused
					if (readyForRead && !inEos && _track.getPlayState() != AudioTrack.PLAYSTATE_PAUSED) {
						int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
						if (inIndex >= 0) {
							ByteBuffer buffer = inputBuffers[inIndex];
							int sampleSize = extractor.readSampleData(buffer, 0);
							bytesFed += sampleSize;
							changeState2("bytesFed: " + bytesFed);
							if (sampleSize < 0) {
								decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
								inEos = true;
							} else {
								decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
								extractor.advance();
							}
						}
					}

					int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
					if (outIndex > 0) {
						ByteBuffer buf = outputBuffers[outIndex];
						//_track.write(buf, info.size, AudioTrack.WRITE_BLOCKING);

						final byte[] chunk = new byte[info.size];
						buf.get(chunk);
						buf.clear();
						_track.write(chunk, 0, chunk.length);

						decoder.releaseOutputBuffer(outIndex, false);
					} else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						changeState("INFO_OUTPUT_BUFFERS_CHANGED");
						outputBuffers = decoder.getOutputBuffers();
					} else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						MediaFormat newFormat = decoder.getOutputFormat();
						//changeState("New format " + newFormat);
						_track.setPlaybackRate(newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
					}

					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d("mp3decoders", "outEOS");
						break;
					}
				}

				changeState("done playing");
				decoder.stop();
				decoder.release();
				extractor.release();

			} catch (IOException e) {
				e.printStackTrace();
			} finally {

				if (_track != null) {
					try {
						_track.stop();
						while (_track.getPlaybackHeadPosition() != 0)
							Thread.sleep(10);
					} catch (InterruptedException e) {
						Log.e("mp3decoders", "InterruptedException", e);
					}
					_track.release();
					_track = null;
				}

			}
		}
	};

	// sleep for 1000 downloads slightly slower than realtime
	private int getStreamSpeedDecoderFactor() {
		RadioGroup speedGroup = (RadioGroup) findViewById(R.id.streamSpeed);
		switch (speedGroup.getCheckedRadioButtonId()) {
			case R.id.streamRealtime: return 15;
			case R.id.streamSlow: return 10;
			case R.id.streamVerySlow: return 5;
			default: return 15;
		}
	}

	private View.OnClickListener playMediaDecoderHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (!requestAudioFocus())
				return;
			new Thread(mediaDecoderRunnable, "mediaDecoder").start();
			Log.i("mp3decoders", "started media decoder thread");
		}
	};

	private View.OnClickListener playNativeHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			Native.init();
		}
	};

	private View.OnClickListener playMPG123StreamHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (!requestAudioFocus())
				return;
			new Thread(mpg123StreamRunnable, "mpg123-streamer").start();
			Log.i("mp3decoders", "started MPG123 Stream thread");
		}
	};

	private View.OnClickListener playMPG123Handler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (!requestAudioFocus())
				return;
			new Thread(mpg123Runnable, "mpg123").start();
			Log.i("mp3decoders", "started MPG123 thread");
		}
	};

	private View.OnClickListener playVorbisHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (!requestAudioFocus())
				return;
			new Thread(vorbisRunnable, "vorbis").start();
			Log.i("mp3decoders", "started Vorbis thread");
		}
	};
	private boolean requestAudioFocus() {
		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		int result = audioManager.requestAudioFocus(audioFocusListener,
				AudioManager.STREAM_MUSIC,
				AudioManager.AUDIOFOCUS_GAIN);
		if(result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			Log.d("mp3decoders", "Can't get audio focus");
			return false;
		}
		Log.i("mp3decoders", "audiofocus request granted");
		return true;
	}

	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

		findViewById(R.id.playMediaDecoder).setOnClickListener(playMediaDecoderHandler);
		findViewById(R.id.playNative).setOnClickListener(playNativeHandler);
		findViewById(R.id.playMPG123Stream).setOnClickListener(playMPG123StreamHandler);
		findViewById(R.id.playMPG123).setOnClickListener(playMPG123Handler);
		findViewById(R.id.playVorbis).setOnClickListener(playVorbisHandler);
		findViewById(R.id.skip1s).setOnClickListener(skip1sHandler);
		findViewById(R.id.skip6s).setOnClickListener(skip6sHandler);
		findViewById(R.id.skipPastEnd).setOnClickListener(skipPastEndHandler);
		findViewById(R.id.pause).setOnClickListener(pauseHandler);
		_stateText = (TextView) findViewById(R.id.state);
		_state2Text = (TextView) findViewById(R.id.state2);
		changeState("init");

		try {
			File streamedFile = new File(getFilesDir() + "/streamed.mp3");
			//noinspection ResultOfMethodCallIgnored
			streamedFile.delete();
			//noinspection ResultOfMethodCallIgnored
			streamedFile.createNewFile();
		} catch (IOException e) {
			Log.e("mp3decoders", "unable to recreate streamed file", e);
		}

		try {
			if (!new File(getFilesDir() + "/loop1.mp3").exists()) {
				InputStream loop1 = getResources().openRawResource(R.raw.loop1);
				FileOutputStream out = new FileOutputStream(getFilesDir() + "/loop1.mp3");
				byte[] buffer = new byte[1024];
				int len;
				while ((len = loop1.read(buffer)) != -1)
					out.write(buffer, 0, len);
				loop1.close();
			}
		} catch (FileNotFoundException e) {
			Log.e("mp3decoders", "FileNotFoundException", e);
		} catch (IOException e) {
			Log.e("mp3decoders", "IOException", e);
		}

		try {
			if (!new File(getFilesDir() + "/loop1_ogg.ogg").exists()) {
				InputStream loop1 = getResources().openRawResource(R.raw.loop1_ogg);
				FileOutputStream out = new FileOutputStream(getFilesDir() + "/loop1_ogg.ogg");
				byte[] buffer = new byte[1024];
				int len;
				while ((len = loop1.read(buffer)) != -1)
					out.write(buffer, 0, len);
				loop1.close();
			}
		} catch (FileNotFoundException e) {
			Log.e("mp3decoders", "FileNotFoundException", e);
		} catch (IOException e) {
			Log.e("mp3decoders", "IOException", e);
		}
	}

}
