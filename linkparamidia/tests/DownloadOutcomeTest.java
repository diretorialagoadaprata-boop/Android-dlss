package br.com.linkparamidia;
public final class DownloadOutcomeTest {
    static int n=0;
    static void yes(boolean ok){n++;if(!ok)throw new AssertionError("outcome #"+n);}
    public static void main(String[] a){
        yes(DownloadOutcome.failures(0,0,null,false)==1);
        yes(DownloadOutcome.failures(0,0,"Sign in to confirm you're not a bot",false)==1);
        yes(DownloadOutcome.failures(0,4,null,false)==4);
        yes(DownloadOutcome.failures(3,0,"one failed",false)==1);
        yes(DownloadOutcome.failures(3,0,null,false)==0);
        yes(DownloadOutcome.failures(0,0,null,true)==0);
        yes(DownloadOutcome.publicError("ERROR: Sign in to confirm you're not a bot").contains("Nenhum MP3 foi salvo"));
        yes(DownloadOutcome.publicError("Video unavailable").contains("indisponível"));
        yes(DownloadOutcome.publicError(null).contains("Nenhum arquivo"));
        yes(DownloadOutcome.summary(0,0,"error",false).contains("NÃO"));
        yes(DownloadOutcome.summary(2,1,"error",false).contains("parcialmente"));
        yes(DownloadOutcome.summary(4,0,null,false).contains("concluído"));
        System.out.println("PASS "+n+" outcome checks");
    }
}
