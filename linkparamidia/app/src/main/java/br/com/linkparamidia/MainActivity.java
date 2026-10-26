package br.com.linkparamidia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.*;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.util.*;

public final class MainActivity extends Activity {
    static final int PICK=4401;
    static final int NAVY=Color.rgb(19,39,66), BLUE=Color.rgb(25,95,188),
        BG=Color.rgb(246,248,251), MUTED=Color.rgb(86,103,120);
    EditText link,start,end;
    Spinner media,quality,bitrate;
    CheckBox playlist;
    TextView folderInfo,status,result;
    ProgressBar progress;
    Button download,cancel,inspect;
    Uri folder;
    DownloadWorker worker;
    boolean startAfterFolder=false;
    final ArrayList<Integer> heights=new ArrayList<>();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        String location=getPreferences(MODE_PRIVATE).getString("folder","");
        if(!location.isEmpty()) folder=Uri.parse(location);
        buildUi();
        Intent in=getIntent();
        if(Intent.ACTION_SEND.equals(in.getAction()) && "text/plain".equals(in.getType())) {
            String shared=in.getStringExtra(Intent.EXTRA_TEXT);
            if(shared!=null) link.setText(shared);
        }
    }
    int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    GradientDrawable bg(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(11));return d;
    }
    TextView text(String name,int size,boolean bold){
        TextView t=new TextView(this);t.setText(name);t.setTextSize(size);t.setTextColor(bold?NAVY:MUTED);
        t.setPadding(0,dp(7),0,dp(7));if(bold)t.setTypeface(null,Typeface.BOLD);return t;
    }
    EditText field(String hint) {
        EditText e=new EditText(this);
        e.setTextSize(15);e.setSingleLine(true);e.setHint(hint);
        e.setBackground(bg(Color.WHITE));e.setPadding(dp(12),dp(12),dp(12),dp(12));return e;
    }
    Button button(String name,int color){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);
        b.setTextColor(Color.WHITE);b.setBackground(bg(color));b.setMinHeight(dp(48));return b;
    }
    Spinner choices(List<String> items) {
        Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items));
        s.setBackground(bg(Color.WHITE));s.setMinimumHeight(dp(45));return s;
    }
    void add(LinearLayout root,View view,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);root.addView(view,p);
    }
    void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(76));scroll.addView(root);setContentView(scroll);
        TextView title=text("Link para Mídia",27,true);add(root,title,0);
        add(root,text("Vídeos, playlists e trechos no celular.",14,false),0);
        add(root,text("LINK DO VÍDEO OU PLAYLIST",12,true),18);
        link=field("Cole o link aqui");add(root,link,2);
        Button paste=button("Colar da área de transferência",MUTED);add(root,paste,7);
        paste.setOnClickListener(v->{
            ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data=cb.getPrimaryClip();
            if(data!=null && data.getItemCount()>0)link.setText(data.getItemAt(0).coerceToText(this));
        });
        playlist=new CheckBox(this);
        playlist.setText("Baixar playlist inteira (arquivos separados)");playlist.setTextColor(NAVY);add(root,playlist,7);
        add(root,text("FORMATO",12,true),11);
        media=choices(Arrays.asList("MP4 — Vídeo","MP3 — Somente áudio"));add(root,media,2);
        add(root,text("QUALIDADE DO VÍDEO",12,true),12);
        quality=choices(Arrays.asList("Melhor disponível"));add(root,quality,2);
        showQuality(Arrays.asList(0,4320,2160,1440,1080,720,480,360,240,144));
        inspect=button("Consultar qualidades disponíveis",MUTED);add(root,inspect,7);
        inspect.setOnClickListener(v->inspectQuality());
        add(root,text("QUALIDADE DO MP3",12,true),10);
        bitrate=choices(Arrays.asList("320 kbps","256 kbps","192 kbps","128 kbps"));add(root,bitrate,2);
        add(root,text("A conversão não aumenta a qualidade do áudio original.",12,false),2);
        add(root,text("RECORTE OPCIONAL",12,true),12);
        add(root,text("Sem horários: arquivo completo. Com horários: só o trecho indicado.",13,false),0);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        start=field("Início 00:00:00");end=field("Fim 00:04:00");
        start.setInputType(InputType.TYPE_CLASS_TEXT);end.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams h=new LinearLayout.LayoutParams(0,-2,1);h.rightMargin=dp(5);
        row.addView(start,h);LinearLayout.LayoutParams h2=new LinearLayout.LayoutParams(0,-2,1);h2.leftMargin=dp(5);
        row.addView(end,h2);add(root,row,5);
        add(root,text("PASTA DE DESTINO",12,true),13);
        folderInfo=text(folder==null?"Nenhuma pasta escolhida":"Pasta selecionada ✓",14,false);add(root,folderInfo,0);
        Button pick=button("Escolher pasta",MUTED);add(root,pick,5);
        pick.setOnClickListener(v->pickFolder());
        download=button("BAIXAR E CONVERTER",BLUE);add(root,download,18);
        cancel=button("Cancelar",MUTED);cancel.setEnabled(false);add(root,cancel,8);
        download.setOnClickListener(v->begin());
        cancel.setOnClickListener(v->{if(worker!=null)worker.cancel();});
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);add(root,progress,14);
        status=text("Pronto para começar.",14,false);add(root,status,4);
        result=text("",15,true);add(root,result,1);
    }
    void pickFolder(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK);
    }
    @Override protected void onActivityResult(int request,int code,Intent data) {
        super.onActivityResult(request,code,data);
        if(request==PICK && code==RESULT_OK && data!=null){
            Uri chosen=data.getData();
            if(chosen!=null)try {
                getContentResolver().takePersistableUriPermission(chosen,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                folder=chosen;folderInfo.setText("Pasta selecionada ✓");
                getPreferences(MODE_PRIVATE).edit().putString("folder",folder.toString()).apply();
                if(startAfterFolder){startAfterFolder=false;begin();}
            }catch(Exception e){folder=null;status.setText("Pasta sem permissão de escrita. Escolha outra.");}
        }
        startAfterFolder=false;
    }
    void busy(boolean b){download.setEnabled(!b);inspect.setEnabled(!b);cancel.setEnabled(b);}
    void begin(){
        try {
            if(folder==null){startAfterFolder=true;pickFolder();return;}
            int selected=quality.getSelectedItemPosition();
            int h=heights.get(selected);
            int rate=Integer.parseInt(bitrate.getSelectedItem().toString().split(" ")[0]);
            ClipRules.Spec spec=new ClipRules.Spec(link.getText().toString(),playlist.isChecked(),media.getSelectedItemPosition()==1,h,rate,
                start.getText().toString(),end.getText().toString());
            busy(true);progress.setProgress(0);result.setText("");
            worker=new DownloadWorker(this,new DownloadWorker.Events(){
                @Override public void status(String msg){status.setText(msg);}
                @Override public void progress(int p){progress.setProgress(p);}
                @Override public void finished(int saved,int rejected,String error){
                    busy(false);
                    status.setText(DownloadOutcome.summary(saved,rejected,error,false)
                        +(error==null?"":" "+DownloadOutcome.publicError(error)));
                    result.setText("Arquivos salvos: "+saved+"   |   Falhas: "+rejected);
                }
            });
            worker.begin(spec,folder);
        } catch(Exception e){
            busy(false);Toast.makeText(this,e.getMessage()==null?"Falha ao iniciar":e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    void showQuality(List<Integer> options){
        heights.clear();heights.addAll(options);
        ArrayList<String> values=new ArrayList<>();
        for(int h:heights)values.add(h==0?"Melhor disponível":"Até "+h+"p");
        quality.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));
    }
    void inspectQuality() {
        try{new ClipRules.Spec(link.getText().toString(),false,false,0,320,"","");}
        catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();return;}
        if(playlist.isChecked()){status.setText("Na playlist, a qualidade é aplicada individualmente a cada vídeo.");return;}
        inspect.setEnabled(false);status.setText("Consultando a origem…");
        String url=link.getText().toString();
        new Thread(()->{
            try{
                EngineSetup.prepare(getApplicationContext(), m->runOnUiThread(()->status.setText(m)));
                YoutubeDLRequest req=new YoutubeDLRequest(url);req.addOption("--no-playlist");
                Object info=YoutubeDL.getInstance().getInfo(req);
                Object formats=info.getClass().getMethod("getFormats").invoke(info);
                Set<Integer> sizes=new HashSet<>();
                if(formats instanceof Iterable)for(Object f:(Iterable<?>)formats){
                    try{
                        Object h=f.getClass().getMethod("getHeight").invoke(f);
                        if(h instanceof Number && ((Number)h).intValue()>=144)sizes.add(((Number)h).intValue());
                    }catch(Exception ignored){}
                }
                ArrayList<Integer> ordered=new ArrayList<>(sizes);ordered.sort(Collections.reverseOrder());ordered.add(0,0);
                runOnUiThread(()->{
                    showQuality(ordered);inspect.setEnabled(true);
                    status.setText(ordered.size()>1?"Qualidades consultadas. Selecione uma opção.":"Somente a melhor qualidade foi identificada.");
                });
            }catch(Exception ex){
                runOnUiThread(()->{inspect.setEnabled(true);status.setText("Consulta indisponível. Você pode usar melhor disponível.");});
            }
        },"LPM-formatos").start();
    }
}