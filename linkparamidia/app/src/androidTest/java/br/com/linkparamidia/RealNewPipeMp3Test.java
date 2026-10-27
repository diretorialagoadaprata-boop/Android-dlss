package br.com.linkparamidia;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Locale;
import static org.junit.Assert.*;

/** Network end-to-end proof. A normal APK smoke test is not sufficient. */
@RunWith(AndroidJUnit4.class)
public final class RealNewPipeMp3Test {
    @Test public void fourSecondYouTubeAudioMustBecomeRealMp3() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File staging=new File(context.getCacheDir(),"actual-newpipe-proof");
        if(!staging.exists())assertTrue(staging.mkdirs());
        String target="https://www.youtube.com/watch?v=jNQXAC9IVRw";
        ClipRules.Spec spec=new ClipRules.Spec(target,false,true,0,128,"00:00:00","00:00:04");
        Log.i("LPM_REAL_MP3","Starting NewPipe video->CDN->local yt-dlp/FFmpeg test");
        try {
            YoutubeDLRequest request=NewPipeFallback.mediaRequest(spec,staging);
            Log.i("LPM_REAL_MP3","NewPipe extracted direct CDN media URL");
            YoutubeDL.getInstance().init(context);
            FFmpeg.getInstance().init(context);
            YoutubeDL.getInstance().execute(request);
            File[] files=staging.listFiles(f->f.isFile() &&
                f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")&&f.length()>1024);
            assertNotNull(files);
            assertTrue("MP3 was never created by NewPipe fallback",files.length>0);
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try {
                retriever.setDataSource(files[0].getAbsolutePath());
                String milliseconds=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                assertNotNull("No real MP3 duration available",milliseconds);
                ClipRules.verifyDuration(Long.parseLong(milliseconds),spec);
            } finally {retriever.release();}
            Log.i("LPM_REAL_MP3","PASS: real public YouTube MP3 created and 4-sec section verified");
        } catch(Exception ex) {
            Log.e("LPM_REAL_MP3","FAIL: "+ex.getClass().getSimpleName()+": "+
                (ex.getMessage()==null?"No explanation":ex.getMessage().replaceAll("https?://[^\\s]+","[URL]")),ex);
            throw ex;
        } finally {
            File[] files=staging.listFiles();
            if(files!=null)for(File f:files)f.delete();
            staging.delete();
        }
    }
}
