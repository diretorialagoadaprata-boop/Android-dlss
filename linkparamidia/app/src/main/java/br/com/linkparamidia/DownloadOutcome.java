package br.com.linkparamidia;
import java.util.Locale;

/** Clear outcomes. A download that saved nothing cannot report zero failures. */
public final class DownloadOutcome {
    private DownloadOutcome() {}
    public static int failures(int saved,int rejected,String error,boolean cancelled){
        if(cancelled)return Math.max(0,rejected);
        if(saved==0)return Math.max(1,rejected);
        if(error!=null&&!error.trim().isEmpty())return Math.max(1,rejected);
        return rejected;
    }
    public static String publicError(String raw){
        if(raw==null||raw.trim().isEmpty())return "Nenhum arquivo foi produzido.";
        String lower=raw.toLowerCase(Locale.ROOT);
        if(lower.contains("sign in to confirm")||lower.contains("not a bot")||lower.contains("confirm you're not")||lower.contains("confirmar que voc")){
            return "O YouTube exige verificação de acesso para este vídeo. O programa não conseguiu obter o áudio. Nenhum MP3 foi salvo.";
        }
        if(lower.contains("private video")||lower.contains("requires authentication")||lower.contains("login required")){
            return "O conteúdo exige autenticação na plataforma. Nenhum arquivo foi salvo.";
        }
        if(lower.contains("unsupported url"))return "Este endereço não é suportado pelo extrator.";
        if(lower.contains("video unavailable"))return "Vídeo indisponível na origem.";
        String safe=raw.replace('\r',' ').replace('\n',' ').trim();
        return safe.length()>280?safe.substring(0,280):safe;
    }
    public static boolean requiresSession(String message) {
        if(message==null)return false;
        String m=message.toLowerCase(Locale.ROOT);
        return m.contains("not a bot") || m.contains("sign in to confirm")
            || m.contains("exige verificação de acesso")
            || m.contains("requires authentication")
            || m.contains("exige autenticação");
    }
    public static String summary(int saved,int rejected,String error,boolean cancelled){
        if(cancelled)return "Processamento cancelado.";
        if(saved==0)return "Download NÃO realizado.";
        if(error!=null||rejected>0)return "Download concluído parcialmente.";
        return "Download concluído.";
    }
}
