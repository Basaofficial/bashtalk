package com.basa.bashtalk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

/**
 * - Status instalacije ažuriranja (traži potvrdu korisnika ako treba)
 * - Nakon ažuriranja / restarta telefona ponovo pokreće pozadinski rad
 */
public class UpdateReceiver extends BroadcastReceiver {

    static final String ACTION_STATUS = "com.basa.bashtalk.INSTALL_STATUS";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (ACTION_STATUS.equals(a)) {
            handleStatus(c, i);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) || Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            UpdateJob.schedule(c);
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                android.content.SharedPreferences pr = c.getSharedPreferences("bashtalk", Context.MODE_PRIVATE);
                if (pr.getBoolean("abOff", false)) {
                    // podsjetnik: vrati Samsung Auto Blocker
                    Intent ab = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openAB", true);
                    PendingIntent p = PendingIntent.getActivity(c, 79, ab, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    Updater.notify(c, "✅ Ažurirano na " + Updater.currentName(c), "🛡️ Dodirni i ponovo uključi Auto Blocker", p);
                } else {
                    Updater.notify(c, "✅ #Bash Talk ažuriran", "Nova verzija " + Updater.currentName(c) + " je instalirana.",
                            Updater.openApp(c));
                }
            }
            if (c.getSharedPreferences("bashtalk", Context.MODE_PRIVATE).getBoolean("bg", false)) {
                try {
                    Intent s = new Intent(c, TalkService.class).setAction(TalkService.ACTION_FG);
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
                } catch (Exception ignored) { }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void handleStatus(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.emit("confirm");
            boolean shown = false;
            if (MainActivity.visible) {
                try { c.startActivity(confirm); shown = true; } catch (Exception ignored) { }
            }
            if (!shown) {
                PendingIntent p = PendingIntent.getActivity(c, 78, confirm,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Updater.notify(c, "⬆️ Ažuriranje spremno", "Dodirni za instalaciju nove verzije #Bash Talk", p);
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            try {
                JSONObject x = new JSONObject();
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                x.put("msg", st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Instalacija otkazana" : (m != null ? m : "Instalacija nije uspjela"));
                Updater.emit("error", x);
            } catch (Exception ignored) { }
        }
    }
}
