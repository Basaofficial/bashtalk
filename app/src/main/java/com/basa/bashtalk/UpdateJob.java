package com.basa.bashtalk;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Provjera novih verzija u pozadini (i kad je aplikacija zatvorena), svakih ~4 h kad ima interneta. */
public class UpdateJob extends JobService {

    static final int JOB_ID = 4242;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(4 * 3600_000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try { Updater.checkNow(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(p, false);
        }, "update-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
