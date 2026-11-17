package com.basa.bashtalk;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Barometar (sprat) i detekcija pada (slobodan pad → udar → bez pokreta).
 */
public class SensorHub implements SensorEventListener {

    public interface Sink {
        void onFall();
        void onPressure(float hPa);
    }

    private final SensorManager sm;
    private final Sink sink;
    private Sensor acc, bar;
    private boolean fallOn;

    private float pEma = Float.NaN;
    private long lastP;

    // stanje detekcije pada
    private int state;
    private long ffStart, ffEnd, impactAt, cooldownUntil;
    private boolean moved;

    public SensorHub(Context c, Sink sink) {
        this.sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        this.sink = sink;
        if (sm != null) {
            acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            bar = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        }
    }

    public boolean hasBarometer() { return bar != null; }

    public void start(boolean fall) {
        if (sm == null) return;
        if (bar != null) sm.registerListener(this, bar, SensorManager.SENSOR_DELAY_NORMAL);
        setFall(fall);
    }

    public void setFall(boolean on) {
        if (sm == null || acc == null) return;
        if (on && !fallOn) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        if (!on && fallOn) {
            sm.unregisterListener(this, acc);
            state = 0;
        }
        fallOn = on;
    }

    public void stop() {
        if (sm != null) sm.unregisterListener(this);
        fallOn = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = SystemClock.elapsedRealtime();
        if (e.sensor.getType() == Sensor.TYPE_PRESSURE) {
            float v = e.values[0];
            pEma = Float.isNaN(pEma) ? v : pEma * 0.9f + v * 0.1f;
            if (now - lastP > 1000) {
                lastP = now;
                sink.onPressure(pEma);
            }
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER || now < cooldownUntil) return;
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double g = Math.sqrt(x * x + y * y + z * z);

        switch (state) {
            case 0: // čekaj slobodan pad (< 0.3 g bar 80 ms)
                if (g < 3.0) {
                    if (ffStart == 0) ffStart = now;
                    else if (now - ffStart >= 80) { state = 1; ffEnd = now; }
                } else {
                    ffStart = 0;
                }
                break;
            case 1: // udar (> 2.5 g) u roku 1.2 s
                if (g > 25.0) {
                    state = 2;
                    impactAt = now;
                    moved = false;
                } else if (now - ffEnd > 1200) {
                    state = 0;
                    ffStart = 0;
                }
                break;
            case 2: // nakon 1.5 s smirivanja, 8 s bez pokreta
                long dt = now - impactAt;
                if (dt > 1500 && Math.abs(g - 9.81) > 3.5) moved = true;
                if (moved) {
                    state = 0;
                    ffStart = 0;
                } else if (dt > 9500) {
                    state = 0;
                    ffStart = 0;
                    cooldownUntil = now + 60000;
                    sink.onFall();
                }
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
