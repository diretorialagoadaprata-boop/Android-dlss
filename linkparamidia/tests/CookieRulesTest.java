package br.com.linkparamidia;
import java.nio.charset.StandardCharsets;
public final class CookieRulesTest {
  static int checks;
  static void assertOk(boolean value){checks++;if(!value)throw new AssertionError("cookie rules "+checks);}
  static void expectInvalid(byte[] input){
    checks++;
    try{CookieRules.validate(input);throw new AssertionError("accepted invalid Netscape cookie file");}
    catch(IllegalArgumentException correct){}
  }
  public static void main(String[] args) {
    String valid="# Netscape HTTP Cookie File\n"
        +".youtube.com\tTRUE\t/\tTRUE\t0\tVISITOR_INFO1_LIVE\tvalue\n";
    CookieRules.validate(valid.getBytes(StandardCharsets.UTF_8));
    assertOk(valid.contains("youtube.com"));
    expectInvalid("not a cookie file".getBytes(StandardCharsets.UTF_8));
    expectInvalid("# Netscape HTTP Cookie File\n.google.com\tTRUE\t/\tTRUE\t0\tSID\tvalue\n".getBytes(StandardCharsets.UTF_8));
    expectInvalid("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\n".getBytes(StandardCharsets.UTF_8));
    expectInvalid(new byte[CookieRules.MAX_BYTES+1]);
    expectInvalid(new byte[0]);
    assertOk(DownloadOutcome.requiresSession("Sign in to confirm you're not a bot"));
    assertOk(DownloadOutcome.requiresSession("O YouTube exige verificação de acesso"));
    assertOk(!DownloadOutcome.requiresSession("Video unavailable"));
    System.out.println("PASS "+checks+" session validation checks");
  }
}
