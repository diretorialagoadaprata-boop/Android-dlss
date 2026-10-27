package br.com.linkparamidia;

import android.content.Context;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Official yt-dlp challenge environment. The QuickJS Android ARM64 executable is
 * shipped as a native-lib file so Android can execute it from a non-writable
 * app package directory (Android 10+ blocks executable files in writable dirs).
 */
public final class JavascriptEngine {
    private JavascriptEngine() {}

    public static boolean isYouTube(String url) {
        try {
            String host=new URI(url).getHost();
            if(host==null)return false;
            host=host.toLowerCase(Locale.ROOT);
            return host.equals("youtu.be") || host.equals("youtube.com")
                || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com")
                || host.endsWith(".youtube-nocookie.com");
        } catch(Exception e){ return false; }
    }

    public static File executable(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir,"libqjs.so");
    }

    public static String check(Context context) {
        File qjs=executable(context);
        if(!qjs.isFile())return "Biblioteca JavaScript QuickJS ausente no APK.";
        if(!qjs.canExecute())return "O Android não permite executar a biblioteca JavaScript.";
        try {
            Process p=new ProcessBuilder(qjs.getAbsolutePath(),"-e","print('LPM_QJS_OK')")
                .redirectErrorStream(true).start();
            boolean done=p.waitFor(7,TimeUnit.SECONDS);
            if(!done) {
                p.destroyForcibly();
                return "Teste do interpretador JavaScript excedeu o limite.";
            }
            String output;
            try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                output=r.readLine();
            }
            if(p.exitValue()!=0 || output==null || !output.contains("LPM_QJS_OK")){
                return "Interpretador JavaScript não iniciou: "+(output==null?"sem saída":output);
            }
            Log.i("LPM_ENGINE","QJS_OK");
            return null;
        } catch(Exception error){
            return "Falha ao iniciar QuickJS: "+error.getClass().getSimpleName()+
                (error.getMessage()==null?"":" "+error.getMessage());
        }
    }

    public static void configure(Context context,YoutubeDLRequest request,String url) {
        if(!isYouTube(url))return;
        String problem=check(context);
        if(problem!=null)throw new IllegalStateException(problem);
        request.addOption("--js-runtimes","quickjs:"+executable(context).getAbsolutePath());
        request.addOption("--remote-components","ejs:github");
    }
}
