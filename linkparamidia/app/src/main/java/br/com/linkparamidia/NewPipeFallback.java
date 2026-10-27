package br.com.linkparamidia;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Completely free, secondary extractor based on GPLv3 NewPipeExtractor.
 *
 * IMPORTANT: This fallback does not claim to defeat account requirements.
 * It returns a candidate CDN URL, then reuses the *local* yt-dlp/FFmpeg media
 * downloader. No proxy, paid API or external conversion server is used.
 *
 * NewPipeExtractor (C) TeamNewPipe contributors, GPL-3.0-or-later.
 * Source: https://github.com/TeamNewPipe/NewPipeExtractor
 */
public final class NewPipeFallback {
    private NewPipeFallback() {}
    private static final int MAX_METADATA_BYTES = 12 * 1024 * 1024;
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean ready;

    private static final class AndroidHttpDownloader extends Downloader {
        @Override public Response execute(Request request) throws IOException {
            URI uri;
            try { uri = new URI(request.url()); }
            catch(Exception ex) { throw new IOException("Endereço de extração inválido.",ex); }
            if (!"https".equalsIgnoreCase(uri.getScheme()))
                throw new IOException("O extrator alternativo só usa conexões HTTPS.");
            HttpURLConnection connection=(HttpURLConnection)new URL(request.url()).openConnection();
            connection.setConnectTimeout(14000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(request.httpMethod());
            connection.setRequestProperty("Accept-Encoding","identity");
            if(request.headers()!=null){
                for(Map.Entry<String,List<String>> header:request.headers().entrySet()){
                    if(header.getKey()==null || header.getValue()==null)continue;
                    for(String value:header.getValue()){
                        if(value!=null)connection.addRequestProperty(header.getKey(),value);
                    }
                }
            }
            byte[] body=request.dataToSend();
            if(body!=null && body.length>0){
                connection.setDoOutput(true);
                try(java.io.OutputStream out=connection.getOutputStream()){out.write(body);}
            }
            try {
                int status=connection.getResponseCode();
                URL finalUrl=connection.getURL();
                if(!"https".equalsIgnoreCase(finalUrl.getProtocol()))
                    throw new IOException("Redirecionamento sem HTTPS não permitido.");
                String responseBody="";
                InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
                if(input!=null){
                    try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buf=new byte[16384];int count;
                        while((count=in.read(buf))!=-1){
                            if(out.size()+count>MAX_METADATA_BYTES)
                                throw new IOException("Resposta de metadados excede o limite permitido.");
                            out.write(buf,0,count);
                        }
                        responseBody=out.toString(StandardCharsets.UTF_8.name());
                    }
                }
                return new Response(status,connection.getResponseMessage(),
                    connection.getHeaderFields(),responseBody,finalUrl.toString());
            } finally {connection.disconnect();}
        }
    }

    static void init() {
        if(ready)return;
        synchronized(INIT_LOCK){
            if(!ready){
                NewPipe.init(new AndroidHttpDownloader());
                ready=true;
            }
        }
    }

    private static String usable(String content,boolean url){
        if(!url || content==null) return null;
        try {
            URI u=new URI(content);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null) return null;
            return content;
        }catch(Exception ex){return null;}
    }

    /** Fallback is restricted to individual videos, never whole playlists. */
    public static YoutubeDLRequest mediaRequest(ClipRules.Spec spec,java.io.File staging)
        throws Exception {
        if(!JavascriptEngine.isYouTube(spec.url)||spec.playlist)
            throw new IllegalArgumentException("Motor alternativo disponível apenas para vídeo individual do YouTube.");
        init();
        StreamInfo info=StreamInfo.getInfo(spec.url);
        String content=null;
        if(spec.mp3){
            int highest=-1;
            for(AudioStream stream:info.getAudioStreams()){
                String possible=usable(stream.getContent(),stream.isUrl());
                if(possible==null)continue;
                int score=Math.max(0,stream.getAverageBitrate());
                if(content==null||score>highest){content=possible;highest=score;}
            }
        }else{
            int highest=-1;
            for(VideoStream stream:info.getVideoStreams()){
                if(stream.isVideoOnly())continue;
                String possible=usable(stream.getContent(),stream.isUrl());
                if(possible==null)continue;
                int height=stream.getHeight();
                if(spec.height>0 && height>spec.height)continue;
                if(content==null || height>highest){content=possible;highest=height;}
            }
        }
        if(content==null)throw new IOException(spec.mp3
            ?"NewPipe não encontrou um fluxo de áudio acessível para este vídeo."
            :"NewPipe não encontrou vídeo com áudio integrado na resolução solicitada.");

        String safeTitle=ClipRules.safeName(info.getName());
        if(safeTitle.length()>90)safeTitle=safeTitle.substring(0,90);
        String identifier=ClipRules.safeName(info.getId());
        YoutubeDLRequest request=new YoutubeDLRequest(content);
        request.addOption("--ignore-config");
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-overwrites");
        request.addOption("--retries","2");
        request.addOption("--fragment-retries","2");
        request.addOption("--output",new java.io.File(staging,safeTitle+"_"+identifier+".%(ext)s").getAbsolutePath());
        if(spec.mp3){
            request.addOption("--extract-audio");
            request.addOption("--audio-format","mp3");
            request.addOption("--audio-quality",spec.bitrate+"K");
        }else{
            request.addOption("--recode-video","mp4");
        }
        if(spec.clip()){
            request.addOption("--download-sections",spec.section());
            request.addOption("--force-keyframes-at-cuts");
        }
        return request;
    }
}
