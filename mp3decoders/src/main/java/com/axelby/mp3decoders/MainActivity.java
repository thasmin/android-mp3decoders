package com.axelby.mp3decoders;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

public class MainActivity extends Activity {
	private TextView _stateText;
	private AudioTrack _track = null;

	private Runnable mpg123Runnable = new Runnable() {
		@Override
		public void run() {
			MPG123 mpg123 = new MPG123(getFilesDir() + "/loop1.mp3");

			_track = new AudioTrack(AudioManager.STREAM_MUSIC,
					mpg123.getRate(),
					mpg123.getNumChannels() == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					mpg123.getRate() * 2,
					AudioTrack.MODE_STREAM);
			double secs = 1.0 * mpg123.getLength() / mpg123.getRate();
			Log.i("jlayerplayer", "secs: " + secs);
			Log.i("jlayerplayer", "secs per frame: " + mpg123.getSecondsPerFrame());
			Log.i("jlayerplayer", "length: " + mpg123.getLength());
			Log.i("jlayerplayer", "secs per frame * length: " + mpg123.getSecondsPerFrame() * mpg123.getLength());

			MediaPlayer mp = MediaPlayer.create(MainActivity.this, R.raw.loop1);
			Log.i("jlayerplayer", "mediaplayer duration: " + mp.getDuration());

			_track.setPositionNotificationPeriod((int) (1000.0f * 1000.0f * mpg123.getSecondsPerFrame()));
			RadioGroup rg = (RadioGroup) findViewById(R.id.playbackRate);
			switch (rg.getCheckedRadioButtonId()) {
				case R.id.rate15:
					_track.setPlaybackRate((int) (mpg123.getRate() * 1.5));
					break;
				case R.id.rate20:
					_track.setPlaybackRate((int) (mpg123.getRate() * 2.0));
					break;
			}
			_track.setPlaybackPositionUpdateListener(playbackPositionListener);


			_track.play();
			changeState("playing");

			try {
				long start = System.currentTimeMillis();
				short[] pcm = new short[1024 * 5];
				while (mpg123.readSamples(pcm, 0, pcm.length) > 0) {
					if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
						Thread.sleep(50);
						continue;
					}
					if (!isTimingOnly()) {
						_track.write(pcm, 0, pcm.length);
						Log.i("jlayerplayer", "wrote " + (pcm.length / 2) + " frames");
					}
				}
				long end = System.currentTimeMillis();
				Log.i("jlayerplayer", "jlayer decoded in " + (end - start) + " milliseconds");

			} catch (InterruptedException e) {
				Log.e("jlayerplayer", "InterruptedException", e);
			} finally {
				mpg123.close();
				waitAndCloseTrack();
			}

			Log.i("jlayerplayer", "done loading audiotrack");
			changeState("finished playing");
		}
	};

	private Runnable jLayerRunnable = new Runnable() {
		@Override
		public void run() {
			InputStream loop1 = null;
			try {
				Log.i("jlayerplayer", "in loader thread");

				loop1 = getResources().openRawResource(R.raw.loop1);
				Decoder decoder = new Decoder();
				Bitstream bitstream = new Bitstream(loop1);

				Header header = bitstream.readFrame();
				_track = createTrackFromHeader(header);
				_track.play();
				changeState("playing");

				boolean done = false;
				long start = System.currentTimeMillis();
				while (!done) {
					if (_track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
						Thread.sleep(50);
						continue;
					}

					SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
					short[] pcm = frame.getBuffer();
					bitstream.closeFrame();

					if (!isTimingOnly()) {
						_track.write(pcm, 0, pcm.length);
						Log.i("jlayerplayer", "wrote " + (pcm.length / 2) + " frames");
					}

					header = bitstream.readFrame();
					if (header == null)
						done = true;
				}
				long end = System.currentTimeMillis();
				Log.i("jlayerplayer", "jlayer decoded in " + (end - start) + " milliseconds");

				Log.i("jlayerplayer", "done loading audiotrack");
				changeState("finished playing");
			} catch (BitstreamException e) {
				Log.e("jlayerplayer", "bitstreamexception", e);
			} catch (DecoderException e) {
				Log.e("jlayerplayer", "decoderexception", e);
			} catch (InterruptedException e) {
				Log.e("jlayerplayer", "InterruptedException", e);
			} finally {
				try {
					if (loop1 != null)
						loop1.close();
				} catch (IOException e) {
					Log.e("jlayerplayer", "ioexception", e);
				}

				waitAndCloseTrack();
			}

		}
	};

	private boolean isTimingOnly() {
		CheckBox timingOnly = (CheckBox)findViewById(R.id.timing);
		return timingOnly.isChecked();
	}

	private void waitAndCloseTrack() {
		if (_track != null) {
			try {
				_track.stop();
				while (_track.getPlaybackHeadPosition() != 0)
					Thread.sleep(10);
			} catch (InterruptedException e) {
				Log.e("jlayerplayer", "InterruptedException", e);
			}

			_track.release();
			_track = null;
		}
	}

	private AudioTrack createTrackFromHeader(Header header) {
		AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC,
				header.frequency(),
				AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT,
				header.bitrate() * 2,
				AudioTrack.MODE_STREAM);
		Log.i("jlayerplayer", "ms per frame: " + header.ms_per_frame());
		track.setPositionNotificationPeriod((int) (1000.0f * header.ms_per_frame()));
		RadioGroup rg = (RadioGroup) findViewById(R.id.playbackRate);
		switch (rg.getCheckedRadioButtonId()) {
			case R.id.rate15:
				track.setPlaybackRate((int) (header.frequency() * 1.5));
				break;
			case R.id.rate20:
				track.setPlaybackRate((int) (header.frequency() * 2.0));
				break;
		}
		track.setPlaybackPositionUpdateListener(playbackPositionListener);
		return track;
	}

	private AudioTrack.OnPlaybackPositionUpdateListener playbackPositionListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioTrack audioTrack) {
			changeState("marker reached");
		}

		@Override
		public void onPeriodicNotification(AudioTrack audioTrack) {
			changeState("periodic notification at " + audioTrack.getPlaybackHeadPosition());
		}
	};

	private AudioManager.OnAudioFocusChangeListener audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
		@Override
		public void onAudioFocusChange(int state) {
			Log.i("jlayerplayer", "audiofocus state " + state);
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
				Log.i("jlayerplayer", "paused");
			}
		}
	};

	private View.OnClickListener playJLayerHandler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track != null) {
				_track.play();
				Log.i("jlayerplayer", "resumed");
				return;
			}

			if (!requestAudioFocus())
				return;
			new Thread(jLayerRunnable).start();
			Log.i("jlayerplayer", "started JLayer thread");
		}
	};

	private View.OnClickListener playMPG123Handler = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (_track != null) {
				_track.play();
				Log.i("jlayerplayer", "resumed");
				return;
			}

			if (!requestAudioFocus())
				return;
			new Thread(mpg123Runnable).start();
			Log.i("jlayerplayer", "started MPG123 thread");
		}
	};

	private boolean requestAudioFocus() {
		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		int result = audioManager.requestAudioFocus(audioFocusListener,
				AudioManager.STREAM_MUSIC,
				AudioManager.AUDIOFOCUS_GAIN);
		if(result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			Log.d("jlayerplayer", "Can't get audio focus");
			return false;
		}
		Log.i("jlayerplayer", "audiofocus request granted");
		return true;
	}

	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

		findViewById(R.id.playJLayer).setOnClickListener(playJLayerHandler);
		findViewById(R.id.playMPG123).setOnClickListener(playMPG123Handler);
		findViewById(R.id.pause).setOnClickListener(pauseHandler);
		_stateText = (TextView) findViewById(R.id.state);
		changeState("init");

		try {
			InputStream loop1 = getResources().openRawResource(R.raw.loop1);
			FileOutputStream out = new FileOutputStream(getFilesDir() + "/loop1.mp3");
			byte[] buffer = new byte[1024];
			int len;
			while ((len = loop1.read(buffer)) != -1)
				out.write(buffer, 0, len);
			loop1.close();
		} catch (FileNotFoundException e) {
			Log.e("jlayerplayer", "FileNotFoundException", e);
		} catch (IOException e) {
			Log.e("jlayerplayer", "IOException", e);
		}
	}

}
