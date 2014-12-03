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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class MainActivity extends Activity {
	private TextView _stateText;
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
			playStream(new MPG123Stream(), getFilesDir() + "/loop1.mp3");
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
			while ((samples = _decoder.readSamples(pcm)) > 0) {
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

		Piper piper = null;
		int rate = 0;
		int numChannels = 0;

		try {
			piper = new Piper(filename);
			piper.start();

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

			short[] pcm = new short[1000 * 5];
			while (true) {
				if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
					Thread.sleep(50);
					continue;
				}

				int samples = _decoder.readSamples(pcm);
				if (samples == -1) {
					Thread.sleep(50);
					continue;
				}
				if (samples == 0)
					break;
				if (samples > 0) {
					_track.write(pcm, 0, samples);

					if (_decoder.getPosition() > 6f)
						streamSeekTo(piper, rate, numChannels, 1f);
				}
			}
		} catch (InterruptedException e) {
			Log.e("mp3decoders", "InterruptedException", e);
		} catch (IOException e) {
			Log.e("mp3decoders", "IOException", e);
		} finally {
			if (piper != null)
				piper.release();
			_decoder.close();
			waitAndCloseTrack();
		}

		Log.i("mp3decoders", "done loading audiotrack");
		changeState("finished playing");
	}

	// trash the current track, seek, wait until seeking is done, then recreate the track
	private void streamSeekTo(Piper piper, int rate, int numChannels, float seekToSeconds) throws InterruptedException {
		_track.pause();
		_track.flush();
		_track.release();
		_track = null;

		_decoder = _streamer = new MPG123Stream();

		int offset = _streamer.seekFrameOffset(seekToSeconds);
		piper.seekTo(offset);
		while (!piper.doneSeeking())
			Thread.sleep(50);

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

	// pipes data from a file to the parent class's stream
	public class Piper {
		Thread _streamerThread = null;
		RandomAccessFile _file = null;
		long _seekToOffset = -1;
		long _furthestRead = 0; // the number of bytes we've "downloaded"

		public Piper(String filename) throws FileNotFoundException {
			_file = new RandomAccessFile(filename, "r");
			_streamerThread = new Thread(_streamerRunnable, "streamer");
		}
		public void start() { _streamerThread.start(); }
		public void release() {
			try {
				_file.close();
			} catch (IOException ignored) { }
		}
		public void seekTo(long offset) {
			_seekToOffset = offset;
			if (!_streamerThread.isAlive()) {
				_streamerThread = new Thread(_streamerRunnable, "streamer");
				_streamerThread.start();
			}
		}
		public boolean doneSeeking() { return _seekToOffset == -1; }

		Runnable _streamerRunnable = new Runnable() {
			@Override
			public void run() {
				try {
					while (true) {
						// if seeking and we've already "downloaded" enough bytes to seek, just seek
						// otherwise continue downloading until we've got enough
						if (_seekToOffset != -1 &&_furthestRead > _seekToOffset) {
							_file.seek(_seekToOffset);
							_seekToOffset = -1;
						}

						// figure out how much of the file we have "downloaded"
						// download 10000 bytes if we're at the edge of the file
						int size = 10000;
						if (_furthestRead > _file.getFilePointer())
							size = (int) (_furthestRead - _file.getFilePointer());
						byte[] c = new byte[size];

						int read = _file.read(c);
						if (read == -1)
							break;
						_streamer.feed(c, read);
						_furthestRead = _file.getFilePointer();

						//changeState(String.format("at %d bytes, %f seconds", _furthestRead, _streamer.getPosition()));

						Thread.sleep(100);
					}
				} catch (IOException | InterruptedException ignored) { }
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
		changeState("init");

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
