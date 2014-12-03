package com.axelby.mp3decoders;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class MainActivity extends Activity {
	private TextView _stateText;
	private TextView _state2Text;
	private TextView _state3Text;
	private AudioTrack _track = null;
	IMediaDecoder _decoder;
	MPG123Stream _streamer;

	private Runnable vorbisRunnable = new Runnable() {
		@Override
		public void run() {
			playFromDecoder(new Vorbis(getFilesDir() + "/loop1_ogg.ogg"));
		}
	};

	private Runnable mpg123Runnable = new Runnable() {
		@Override
		public void run() {
			playFromDecoder(new MPG123(getFilesDir() + "/loop1.mp3"));
		}
	};

	private Runnable mpg123StreamRunnable = new Runnable() {
		@Override
		public void run() {
			playStream(new MPG123Stream(), getFilesDir() + "/streamed.mp3");
		}
	};

	private double hannWindow(float n, float N) {
		return 0.5 * (1 - Math.cos(2 * Math.PI * n / (N-1)));
	}
	private int olaIndex(int index, int windowSize, int overlap) {
		int overlapnum = index / (windowSize + overlap);
		int extra = index - overlapnum * (windowSize + overlap);

		// is it in the beginning section
		int base = overlapnum * windowSize;
		if (extra < windowSize - overlap)
			return base + extra;
		// it is in the overlap
		int compressedoffset = extra - (windowSize - overlap);
		return base + compressedoffset / 2;
	}

	/*
	0       80   100   120        200   220   240          320
	|--------|-----|-----|----------|-----|-----|------------|
	|========|===========|==========|===========|============|
	0       80         100        180         200          280
	*/

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
		_track.setPlaybackPositionUpdateListener(playbackPositionListener);
		_track.play();
		changeState("playing");

		try {
			int total = 0;
			long start = System.currentTimeMillis();
			int samples;
			short[] pcm = new short[1000 * 5];
			while ((samples = _decoder.readFrame(pcm)) > 0) {
				if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
					Thread.sleep(50);
					continue;
				}
				_track.write(pcm, 0, samples);
			}
			long end = System.currentTimeMillis();
			Log.i("mp3decoders", "decoded " + total + " frames in " + (end - start) + " milliseconds");
		} catch (InterruptedException e) {
			Log.e("mp3decoders", "InterruptedException", e);
		} finally {
			_decoder.close();
			waitAndCloseTrack();
		}

		Log.i("mp3decoders", "done loading audiotrack");
		changeState("finished playing");
	}

	private void playStream(IMediaDecoder decoder, String filename) {
		_decoder = _streamer = (MPG123Stream) decoder;

		Thread fakeStreamer = null;
		Feeder feeder;
		int rate;
		int numChannels;

		try {
			fakeStreamer = new Thread(_fakeStream, "fakeStreamer");
			fakeStreamer.start();
			feeder = new Feeder(filename);
			feeder.start();

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
			_track.setPlaybackPositionUpdateListener(playbackPositionListener);
			_track.play();
			changeState("playing");

			streamSeekTo(feeder, rate, numChannels, 5f);

			short[] pcm = new short[1000 * 5];
			while (true) {
				if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
					Thread.sleep(50);
					continue;
				}

				int samples = _decoder.readFrame(pcm);
				if (samples == -1) {
					Thread.sleep(50);
					continue;
				}
				if (samples == 0)
					break;
				if (samples > 0) {
					_track.write(pcm, 0, samples);

					if (_decoder.getPosition() > 6f)
						streamSeekTo(feeder, rate, numChannels, 1f);
				}
			}
		} catch (InterruptedException e) {
			Log.e("mp3decoders", "InterruptedException", e);
		} catch (IOException e) {
			Log.e("mp3decoders", "IOException", e);
		} finally {
			_decoder.close();
			waitAndCloseTrack();

			if (fakeStreamer != null) {
				fakeStreamer.interrupt();
				try {
					fakeStreamer.join();
				} catch (InterruptedException ignored) { }
			}
		}

		Log.i("mp3decoders", "done loading audiotrack");
		changeState("finished playing");
	}

	// trash the current track, seek, wait until seeking is done, then recreate the track
	private void streamSeekTo(Feeder feeder, int rate, int numChannels, float seekToSeconds) throws InterruptedException {
		_track.pause();
		_track.flush();
		_track.release();
		_track = null;

		changeState("seeking to " + seekToSeconds + " seconds");
		int offset = _streamer.getSeekFrameOffset(seekToSeconds);
		while (offset == -1) {
			boolean frameSkipped = true;
			changeState("skipping frames while seeking to " + seekToSeconds + " seconds");
			while (frameSkipped && offset == -1) {
				frameSkipped = _decoder.skipFrame();
				offset = _streamer.getSeekFrameOffset(seekToSeconds);
			}
			changeState("waiting for more bytes while seeking to " + seekToSeconds + " seconds");
			Thread.sleep(50);
		}
		changeState("done seeking to " + seekToSeconds + " seconds");
		_decoder.close();

		_decoder = _streamer = new MPG123Stream();
		feeder.seekTo(offset);

		_track = new AudioTrack(AudioManager.STREAM_MUSIC,
				rate,
				numChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				rate * 2,
				AudioTrack.MODE_STREAM);
		_track.setPositionNotificationPeriod(rate);
		_track.setPlaybackPositionUpdateListener(playbackPositionListener);
		_track.play();
	}

	boolean _doneFeeding = false;
	Runnable _fakeStream = new Runnable() {
		@Override
		public void run() {
			InputStream inStream = null;
			OutputStream outStream = null;
			try {
				_doneFeeding = false;
				File out = new File(getFilesDir() + "/streamed.mp3");
				if (out.exists())
					out.delete();
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
					Thread.sleep(1000);
				}
			} catch (IOException | InterruptedException e) {
				Log.e("mp3decoders", "cannot stream file", e);
			} finally {
				_doneFeeding = true;
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

	// pipes data from a file to the parent class's stream
	public class Feeder {
		Thread _feederThread = null;
		String _filename;
		long _seekToOffset = -1;

		public Feeder(String filename) throws FileNotFoundException {
			_filename = filename;
			_feederThread = new Thread(_feederRunnable, "feeder");
		}
		public void start() { _feederThread.start(); }
		public void seekTo(long offset) {
			_seekToOffset = offset;
			if (!_feederThread.isAlive()) {
				_feederThread = new Thread(_feederRunnable, "streamer");
				_feederThread.start();
			}
		}

		Runnable _feederRunnable = new Runnable() {
			@Override
			public void run() {
				long filePosition = 0;
				RandomAccessFile file = null;
				try {
					while (true) {
						// make sure the file exists
						if (!new File(_filename).exists()) {
							Thread.sleep(50);
							continue;
						}

						file = new RandomAccessFile(_filename, "r");
						file.seek(filePosition);

						// attempt to seek to the proper place
						// if we're not there, wait for more data and try again
						while (_seekToOffset != -1) {
							file.seek(_seekToOffset);
							if (file.getFilePointer() == _seekToOffset)
								_seekToOffset = -1;
							Thread.sleep(50);
						}

						// read the available bytes from the file and feed them to the mp3 decoder
						int size = (int) (file.length() - file.getFilePointer());
						byte[] c = new byte[size];
						int read = file.read(c);
						if (read > 0) {
							_streamer.feed(c, read);
							changeState3("feed decoder up to " + file.getFilePointer() + " bytes");
						}
						else if (read == -1 && _doneFeeding)
							break;

						// save the position so we can jump back here when we reopen it
						filePosition = file.getFilePointer();
						file.close();

						//changeState(String.format("at %d bytes, %f seconds", _furthestRead, _streamer.getPosition()));
						Thread.sleep(50);
					}
				} catch (IOException | InterruptedException e) {
					Log.e("mp3decoders", "unable to feed decoder", e);
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
			_decoder = null;
		}
	}

	private AudioTrack.OnPlaybackPositionUpdateListener playbackPositionListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioTrack audioTrack) {
			changeState("marker reached");
		}

		@Override
		public void onPeriodicNotification(AudioTrack audioTrack) {
			if (_decoder != null)
				changeState(String.format("periodic notification at %.2f, head position %d",
						_decoder.getPosition(), audioTrack.getPlaybackHeadPosition()));
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

	private void changeState3(final CharSequence playerState) {
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				_state3Text.setText(playerState);
			}
		});
	}

	private View.OnClickListener pauseHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track != null) {
				_track.pause();
				Log.i("mp3decoders", "paused");
			}
		}
	};

	private View.OnClickListener playMPG123StreamHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track != null) {
				_track.play();
				Log.i("mp3decoders", "resumed");
				return;
			}

			if (!requestAudioFocus())
				return;
			new Thread(mpg123StreamRunnable).start();
			Log.i("mp3decoders", "started MPG123 Stream thread");
		}
	};

	private View.OnClickListener playMPG123Handler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track != null) {
				_track.play();
				Log.i("mp3decoders", "resumed");
				return;
			}

			if (!requestAudioFocus())
				return;
			new Thread(mpg123Runnable).start();
			Log.i("mp3decoders", "started MPG123 thread");
		}
	};

	private View.OnClickListener playVorbisHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track != null) {
				_track.play();
				Log.i("mp3decoders", "resumed");
				return;
			}

			if (!requestAudioFocus())
				return;
			new Thread(vorbisRunnable).start();
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

		findViewById(R.id.playMPG123Stream).setOnClickListener(playMPG123StreamHandler);
		findViewById(R.id.playMPG123).setOnClickListener(playMPG123Handler);
		findViewById(R.id.playVorbis).setOnClickListener(playVorbisHandler);
		findViewById(R.id.testStreamer).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				MPG123.testStream();
			}
		});
		findViewById(R.id.pause).setOnClickListener(pauseHandler);
		_stateText = (TextView) findViewById(R.id.state);
		_state2Text = (TextView) findViewById(R.id.state2);
		_state3Text = (TextView) findViewById(R.id.state3);
		changeState("init");

		try {
			File streamedFile = new File(getFilesDir() + "/streamed.mp3");
			streamedFile.delete();
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
