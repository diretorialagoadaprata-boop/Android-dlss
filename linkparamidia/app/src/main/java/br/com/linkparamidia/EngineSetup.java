package br.com.linkparamidia;

import android.content.Context;
import android.content.SharedPreferences;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import java.util.function.Consumer;

/** Prepares internal tools, then updates yt-dlp without asking the user to configure a browser. */
public final class EngineSetup {
    private EngineSetup() {}
    private static final Object LOCK = new Object();
    private static final long UPDATE_INTERVAL = 12L * 60 * 60 * 1000;

    public static void prepare(Context context, Consumer<String> reporter) throws Exception {
        synchronized (LOCK) {
            YoutubeDL.getInstance().init(context.getApplicationContext());
            FFmpeg.getInstance().init(context.getApplicationContext());
            SharedPreferences prefs = context.getSharedPreferences("linkparamidia_runtime", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long last = prefs.getLong("last_ytdlp_update_attempt", 0L);
            if (last > 0 && now - last >= 0 && now - last < UPDATE_INTERVAL) return;
            prefs.edit().putLong("last_ytdlp_update_attempt", now).apply();
            reporter.accept("Verificando atualizações do extrator...");
            try {
                YoutubeDL.getInstance().updateYoutubeDL(
                    context.getApplicationContext(), YoutubeDL.UpdateChannel._NIGHTLY);
                reporter.accept("Extrator atualizado. Preparando o áudio...");
            } catch (Exception problem) {
                reporter.accept("Atualização indisponível. Tentando com a versão já instalada...");
            }
        }
    }
}
