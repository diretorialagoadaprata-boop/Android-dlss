package br.com.linkparamidia;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Pure business rules; no Android dependency. */
public final class ClipRules {
    private ClipRules(){}
    public static long parse(String input) {
        if (input == null || input.trim().isEmpty()) return -1;
        String[] items=input.trim().split(":",-1);
        if(items.length<2 || items.length>3) throw new IllegalArgumentException("Horário: use MM:SS ou HH:MM:SS.");
        long value=0;
        for(int i=0;i<items.length;i++){
            if(!items[i].matches("[0-9]{1,6}")) throw new IllegalArgumentException("Horário inválido: "+input);
            int p=Integer.parseInt(items[i]);
            if(i>0 && p>59) throw new IllegalArgumentException("Minutos e segundos devem ser menores que 60.");
            value=Math.addExact(Math.multiplyExact(value,60),p);
        }
        return Math.multiplyExact(value,1000);
    }
    public static final class Spec {
        public final String url;
        public final boolean playlist,mp3;
        public final int height,bitrate;
        public final long start,end;
        public Spec(String url,boolean playlist,boolean mp3,int height,int bitrate,String start,String end){
            this.url=url==null?"":url.trim();this.playlist=playlist;this.mp3=mp3;
            this.height=height;this.bitrate=bitrate;this.start=parse(start);this.end=parse(end);
            try {
                URI uri=new URI(this.url);
                if(!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost()==null)
                    throw new IllegalArgumentException("Cole um link válido.");
            } catch(java.net.URISyntaxException ex){throw new IllegalArgumentException("Link inválido.");}
            if((this.start==-1)!=(this.end==-1))throw new IllegalArgumentException("Informe início e fim, ou deixe os dois em branco.");
            if(this.start>=0 && this.end<=this.start)throw new IllegalArgumentException("Fim deve ser posterior ao início.");
            if(height!=0 && (height<144||height>8640))throw new IllegalArgumentException("Resolução inválida.");
            if(bitrate<64||bitrate>320)throw new IllegalArgumentException("Bitrate inválido.");
        }
        public boolean clip(){return start>=0;}
        public long expectedMs(){return clip()?end-start:-1;}
        public String section(){
            if(!clip())throw new IllegalStateException("Intervalo não definido.");
            return "*"+time(start)+"-"+time(end);
        }
    }
    public static String time(long millis){
        long s=millis/1000;
        return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);
    }
    public static List<String> makeArgs(Spec spec, File staging){
        if(staging==null||!staging.isDirectory())throw new IllegalArgumentException("Diretório temporário ausente.");
        List<String> out=new ArrayList<>(Arrays.asList(
            "--ignore-config","--no-overwrites","--no-mtime","--abort-on-error",
            "--retries","3","--fragment-retries","3",
            "--windows-filenames","--trim-filenames","88",
            "--output",new File(staging,"%(playlist_index)s_%(title).80B_%(id)s.%(ext)s").getAbsolutePath(),
            spec.playlist?"--yes-playlist":"--no-playlist"));
        if(spec.mp3) {
            out.addAll(Arrays.asList("--format","bestaudio/best","--extract-audio","--audio-format","mp3","--audio-quality",spec.bitrate+"K"));
        } else {
            String h=spec.height==0?"":"[height<="+spec.height+"]";
            out.addAll(Arrays.asList("--format","bestvideo"+h+"+bestaudio/best"+h,"--merge-output-format","mp4","--recode-video","mp4"));
        }
        if(spec.clip())out.addAll(Arrays.asList("--download-sections",spec.section(),"--force-keyframes-at-cuts"));
        return out;
    }
    public static void verifyDuration(long durationMs,Spec spec){
        if(durationMs<=0)throw new IllegalArgumentException("Duração não identificada.");
        if(!spec.clip())return;
        long expected=spec.expectedMs();
        long tolerance=Math.max(500,Math.min(1800,expected/100));
        if(Math.abs(durationMs-expected)>tolerance)
            throw new IllegalArgumentException("O arquivo não respeitou o recorte: solicitado "+time(expected)+", recebido "+time(durationMs)+".");
    }
    public static String safeName(String value) {
        String name=value==null?"":value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        while(name.endsWith(".")||name.endsWith(" "))name=name.substring(0,name.length()-1);
        if(name.isEmpty())name="midia";
        int d=name.lastIndexOf('.');
        String ext=d>0?name.substring(d):"";
        String prefix=d>0?name.substring(0,d):name;
        if(prefix.length()>106)prefix=prefix.substring(0,106);
        return prefix+ext;
    }
}