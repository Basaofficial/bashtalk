package com.basa.bashtalk;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Glasna SOS sirena na alarm kanalu (čuje se i kad je zvuk utišan). */
public class Siren {

    private final AudioManager am;
    private volatile boolean on;
    private int savedVol = -1;

    public Siren(Context c) {
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isOn() { return on; }

    public synchronized void start() {
        if (on) return;
        on = true;
        try {
            savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) { }
        new Thread(this::loop, "siren").start();
    }

    public synchronized void stop() {
        if (!on) return;
        on = false;
        try { if (savedVol >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0); } catch (Exception ignored) { }
    }

    private void loop() {
        final int rate = 22050;
        AudioTrack t = null;
        try {
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    Math.max(min, rate), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            t.play();
            short[] buf = new short[rate / 10];
            double phase = 0;
            long n = 0;
            while (on) {
                for (int i = 0; i < buf.length; i++, n++) {
                    double sec = (n % (rate * 2L)) / (double) rate;           // 2 s ciklus
                    double f = sec < 1 ? 650 + 750 * sec : 1400 - 750 * (sec - 1);
                    phase += 2 * Math.PI * f / rate;
                    buf[i] = (short) (Math.sin(phase) * 28000);
                }
                t.write(buf, 0, buf.length);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (t != null) { t.stop(); t.release(); } } catch (Exception ignored) { }
        }
    }
}
