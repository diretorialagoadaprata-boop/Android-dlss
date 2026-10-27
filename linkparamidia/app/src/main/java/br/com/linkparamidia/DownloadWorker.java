package br.com.linkparamidia;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.ffmpeg.FFmpeg;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Validates files and requested clip duration BEFORE writing them to the selected folder. */
public final class DownloadWorker {
  public interface Events {
    void status(String message);
    void progress(int percentage);
    void finished(int saved,int rejected,String error);
  }
  final Context app;
  final Events callback;
  final Handler handler=new Handler(Looper.getMainLooper());
  final AtomicBoolean cancelled=new AtomicBoolean(false);
  final String processId="linkparamidia-"+UUID.randomUUID();
  Thread thread;
  public DownloadWorker(Context app,Events callback){
    this.app=app.getApplicationContext();this.callback=callback;
  }
  void emit(String m){handler.post(()->callback.status(m));}
  void progress(int p){handler.post(()->callback.progress(Math.min(100,Math.max(0,p))));}
  void finish(int saved,int rejected,String error){handler.post(()->callback.finished(saved,rejected,error));}
  public synchronized void begin(ClipRules.Spec spec,Uri folder){
    if(thread!=null && thread.isAlive())throw new IllegalStateException("Já existe um download em andamento.");
    if(folder==null)throw new IllegalArgumentException("Escolha uma pasta.");
    cancelled.set(false);
    thread=new Thread(()->execute(spec,folder),"LPM-transfer");
    thread.start();
  }
  public void cancel() {
    cancelled.set(true);
    try{YoutubeDL.getInstance().destroyProcessById(processId);}catch(Exception ignored){}
    emit("Cancelamento solicitado…");
  }
  void execute(ClipRules.Spec spec,Uri folder){
    int saved=0,rejected=0;
    String error=null;
    File staging=new File(app.getCacheDir(),"lpm-"+UUID.randomUUID());
    try{
      if(!staging.mkdirs())throw new IOException("Não foi possível preparar o armazenamento temporário.");
      emit("Preparando ferramentas…");
      EngineSetup.prepare(app, this::emit);
      if(cancelled.get())throw new InterruptedException("Cancelado.");
      YoutubeDLRequest req=new YoutubeDLRequest(spec.url);
      JavascriptEngine.configure(app,req,spec.url);
      List<String> args=ClipRules.makeArgs(spec,staging);
      Set<String> parameters=new HashSet<>(Arrays.asList(
        "--retries","--fragment-retries","--trim-filenames","--output","--format",
        "--merge-output-format","--recode-video","--audio-format","--audio-quality","--download-sections"));
      for(int i=0;i<args.size();i++){
        String name=args.get(i);
        if(parameters.contains(name)){
          if(++i>=args.size())throw new IllegalArgumentException("Parâmetros de download incompletos.");
          req.addOption(name,args.get(i));
        }else req.addOption(name);
      }
      emit(spec.playlist?"Baixando playlist…":"Baixando arquivo…");
      try{
        YoutubeDLResponse response=YoutubeDL.getInstance().execute(req,processId,
          new Function3<Float,Long,String,Unit>(){
            @Override public Unit invoke(Float pct,Long eta,String line){
              if(pct!=null)progress(pct.intValue());
              return Unit.INSTANCE;
            }
          });
        String output=response==null?"":(response.getOut()+"\n"+response.getErr());
        if(output.contains("ERROR:") || output.contains("Sign in to confirm"))
          error=DownloadOutcome.publicError(output);
      }catch(Exception ex){error=DownloadOutcome.publicError(message(ex));}
      if(!cancelled.get()){
        File[] produced=staging.listFiles(f->f.isFile()&&
          (f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")||
           f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")));
        if(produced==null||produced.length==0){
          if(error==null)error="Nenhum arquivo compatível foi produzido.";
        }else{
          Arrays.sort(produced,Comparator.comparing(File::getName));
          for(File media:produced){
            if(cancelled.get())break;
            boolean isMp3=media.getName().toLowerCase(Locale.ROOT).endsWith(".mp3");
            if(isMp3!=spec.mp3)continue;
            try{
              emit("Conferindo: "+media.getName());
              if(media.length()<1024)throw new IOException("Arquivo incompleto.");
              ClipRules.verifyDuration(duration(media),spec);
              export(media,folder);
              saved++;
              emit("Salvo: "+media.getName());
            }catch(Exception ex){
              rejected++;
              emit("Arquivo rejeitado: "+message(ex));
            }
          }
        }
      }
    }catch(InterruptedException ex){error="Operação cancelada.";Thread.currentThread().interrupt();}
    catch(Exception ex){error=message(ex);}
    finally{
      if(cancelled.get())error="Operação cancelada.";
      delete(staging);
      rejected=DownloadOutcome.failures(saved,rejected,error,cancelled.get());
      finish(saved,rejected,error);
    }
  }
  long duration(File file)throws Exception{
    MediaMetadataRetriever r=new MediaMetadataRetriever();
    try{
      r.setDataSource(file.getAbsolutePath());
      String ms=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      if(ms==null)throw new IOException("Não foi possível conferir duração.");
      return Long.parseLong(ms);
    }finally{r.release();}
  }
  void export(File source,Uri root)throws Exception{
    ContentResolver resolver=app.getContentResolver();
    Uri document=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root));
    String filename=ClipRules.safeName(source.getName());
    String mime=filename.toLowerCase(Locale.ROOT).endsWith(".mp3")?"audio/mpeg":"video/mp4";
    Uri output=DocumentsContract.createDocument(resolver,document,mime,filename);
    if(output==null)throw new IOException("A pasta recusou a criação do arquivo.");
    try(FileInputStream in=new FileInputStream(source);
        OutputStream out=resolver.openOutputStream(output,"w")){
      if(out==null)throw new IOException("Não foi possível abrir a pasta para escrita.");
      byte[] chunk=new byte[131072];int n;
      while((n=in.read(chunk))!=-1){
        if(cancelled.get())throw new InterruptedException("Cancelado.");
        out.write(chunk,0,n);
      }
      out.flush();
    }catch(Exception error){
      try{DocumentsContract.deleteDocument(resolver,output);}catch(Exception ignored){}
      throw error;
    }
  }
  static String message(Throwable t){
    String m=t.getMessage();if(m==null||m.isEmpty())return t.getClass().getSimpleName();
    if(m.contains("Sign in to confirm"))return DownloadOutcome.publicError(m);
    return m.length()>360?m.substring(0,360):m;
  }
  static void delete(File folder){
    if(folder==null||!folder.exists())return;
    File[] files=folder.listFiles();
    if(files!=null)for(File f:files)delete(f);
    folder.delete();
  }
}