package br.com.linkparamidia;
import java.io.File;
import java.util.*;
public final class ClipRulesTest {
  static int n=0;
  static void ok(boolean value){n++;if(!value)throw new AssertionError("check "+n);}
  static void bad(Runnable r){n++;try{r.run();throw new AssertionError("expected invalid");}catch(IllegalArgumentException expected){}}
  public static void main(String[] a){
    ClipRules.Spec four=new ClipRules.Spec("https://youtu.be/test",false,false,2160,320,"00:00:00","00:04:00");
    ok(four.clip());ok(four.expectedMs()==240000);ok(four.section().equals("*00:00:00-00:04:00"));
    ok(ClipRules.parse("04:03:21")==14601000);ok(ClipRules.parse("04:00")==240000);
    bad(()->ClipRules.parse("1:99"));bad(()->ClipRules.parse("aaa"));bad(()->ClipRules.parse("99"));
    bad(()->new ClipRules.Spec("https://a.com",false,true,0,320,"00:10","00:09"));
    bad(()->new ClipRules.Spec("https://a.com",false,true,0,320,"00:10",""));
    bad(()->new ClipRules.Spec("file:///etc/passwd",false,true,0,320,"",""));
    List<String> args=ClipRules.makeArgs(four,new File(System.getProperty("java.io.tmpdir")));
    ok(args.contains("--download-sections"));ok(args.contains(four.section()));
    ok(args.contains("--force-keyframes-at-cuts"));ok(args.contains("--no-playlist"));
    ok(args.contains("bestvideo[height<=2160]+bestaudio/best[height<=2160]"));
    ClipRules.Spec mp3=new ClipRules.Spec("https://a.com",true,true,0,192,"00:01:30","00:03:45");
    ok(mp3.expectedMs()==135000);ok(ClipRules.makeArgs(mp3,new File(System.getProperty("java.io.tmpdir"))).contains("--yes-playlist"));
    ok(ClipRules.makeArgs(mp3,new File(System.getProperty("java.io.tmpdir"))).contains("192K"));
    ClipRules.verifyDuration(240500,four);bad(()->ClipRules.verifyDuration(14601000,four));bad(()->ClipRules.verifyDuration(-1,four));
    ok(ClipRules.safeName("a/b:c.mp4").equals("a_b_c.mp4"));
    ok(ClipRules.safeName("n".repeat(180)+".mp4").length()<=110);
    ok(!ClipRules.makeArgs(new ClipRules.Spec("https://a.com",false,false,144,320,"",""),new File(System.getProperty("java.io.tmpdir"))).contains("--download-sections"));
    System.out.println("PASS "+n+" unit checks");
  }
}