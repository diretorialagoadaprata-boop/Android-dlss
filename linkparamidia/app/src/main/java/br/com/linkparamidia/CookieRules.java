package br.com.linkparamidia;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Validates user-owned, Netscape-format session exports without ever exposing values. */
public final class CookieRules {
    private CookieRules(){}
    public static final int MAX_BYTES=2*1024*1024;

    public static void validate(byte[] source) {
        if(source==null || source.length==0 || source.length>MAX_BYTES)
            throw new IllegalArgumentException("Arquivo de sessão vazio ou maior que 2 MB.");
        String content=new String(source,StandardCharsets.UTF_8);
        if(content.indexOf('\0')>=0)throw new IllegalArgumentException("Arquivo de sessão inválido.");
        String[] lines=content.split("\\r?\\n",-1);
        int valid=0,youtube=0;
        boolean header=false;
        for(String line:lines) {
            if(line.startsWith("# Netscape HTTP Cookie File") ||
               line.startsWith("# HTTP Cookie File"))header=true;
            String raw=line.startsWith("#HttpOnly_")?line.substring(10):line;
            if(raw.trim().isEmpty() || (raw.startsWith("#")&&!line.startsWith("#HttpOnly_")))continue;
            String[] parts=raw.split("\\t",-1);
            if(parts.length!=7)throw new IllegalArgumentException("Use um arquivo cookies.txt no formato Netscape.");
            String domain=parts[0].toLowerCase(Locale.ROOT);
            if(!domain.matches("\\.?[a-z0-9.-]+"))
                throw new IllegalArgumentException("Domínio de cookie inválido.");
            if(parts[5].isEmpty())throw new IllegalArgumentException("Nome do cookie ausente.");
            if(domain.equals("youtube.com")||domain.endsWith(".youtube.com"))youtube++;
            valid++;
        }
        if(!header || valid==0 || youtube==0)
            throw new IllegalArgumentException("O arquivo precisa ser um cookies.txt Netscape contendo cookies do YouTube.");
    }
}
