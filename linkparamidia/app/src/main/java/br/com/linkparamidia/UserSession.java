package br.com.linkparamidia;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.Arrays;

/**
 * Optional user-authorized session. Cookies never leave private app storage
 * except when yt-dlp uses them with explicitly pasted YouTube links.
 */
public final class UserSession {
    private UserSession(){}
    private static final String FILE_NAME="youtube-session-cookies.txt";
    public static File file(Context context) {
        return new File(context.getFilesDir(),FILE_NAME);
    }
    public static boolean exists(Context context) {
        return file(context).isFile() && file(context).length()>0;
    }
    public static void clear(Context context) {
        File f=file(context);
        if(f.exists() && !f.delete())throw new IllegalStateException("Não foi possível apagar a sessão.");
    }
    public static synchronized void importFile(Context context,Uri chosen) throws IOException {
        if(chosen==null)throw new IOException("Nenhum arquivo selecionado.");
        byte[] data;
        try(InputStream input=context.getContentResolver().openInputStream(chosen)) {
            if(input==null)throw new IOException("Não foi possível abrir o arquivo.");
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            byte[] part=new byte[32768];int count;
            while((count=input.read(part))!=-1) {
                if(b.size()+count>CookieRules.MAX_BYTES)throw new IOException("Arquivo maior que 2 MB.");
                b.write(part,0,count);
            }
            data=b.toByteArray();
        }
        try{
            CookieRules.validate(data);
            File destination=file(context);
            File temp=new File(context.getFilesDir(),"session-import.tmp");
            try(FileOutputStream output=new FileOutputStream(temp)) {
                output.write(data);
                output.flush();output.getFD().sync();
            }
            temp.setReadable(false,false);temp.setReadable(true,true);
            temp.setWritable(false,false);temp.setWritable(true,true);
            if(destination.exists()&&!destination.delete())throw new IOException("Não foi possível substituir a sessão.");
            if(!temp.renameTo(destination))throw new IOException("Não foi possível salvar a sessão.");
        } finally {
            Arrays.fill(data,(byte)0);
        }
    }
}
