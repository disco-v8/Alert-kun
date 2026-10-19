package direct.kabu.alertkun.mail.pop3;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All inputs are generated synthetic fixtures, never real mail. */
public final class MimeFixtures {
    private static void ascii(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.US_ASCII)); }
    private static void fill(OutputStream out, int bytes, boolean lines) throws IOException {
        byte[] buffer = new byte[1024]; Arrays.fill(buffer, (byte)'x');
        if (lines) { buffer[1022] = '\r'; buffer[1023] = '\n'; }
        while (bytes > 0) { int n = Math.min(bytes, buffer.length); out.write(buffer, 0, n); bytes -= n; }
    }
    public static void write(File file, String mode) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 8192)) {
            if (mode.equals("header")) {
                ascii(out, "X-Fixture: "); fill(out, 15 * 1024 * 1024, false);
                ascii(out, "\r\nContent-Type: text/plain\r\n\r\nsmall\r\n"); return;
            }
            if (mode.equals("folded-header")) {
                ascii(out, "X-Fixture: x\r\n");
                for (int i=0;i<1000;i++) ascii(out, " " + repeat('x',100) + "\r\n");
                ascii(out, "\r\nsmall\r\n"); return;
            }
            if (mode.equals("headers")) {
                for (int i=0;i<101;i++) ascii(out, "X-Fixture-"+i+": x\r\n");
                ascii(out, "\r\nsmall\r\n"); return;
            }
            if (mode.equals("line")) {
                ascii(out,"X-Fixture: "); fill(out,8193,false); ascii(out,"\r\n\r\nsmall\r\n"); return;
            }
            if (mode.equals("plain-large")) {
                ascii(out,"Content-Type: text/plain; charset=utf-8\r\n\r\n"); fill(out,2*1024*1024,false); return;
            }
            if (mode.equals("malformed")) {
                ascii(out,"Content-Type: multipart/mixed; boundary=fixture\r\n\r\nNo boundary\r\n"); return;
            }
            ascii(out,"MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=fixture\r\n\r\n");
            if (mode.equals("preamble")) fill(out,15*1024*1024,true);
            if (mode.equals("nested") || mode.equals("depth17")) {
                int count=mode.equals("nested") ? 4096 : 17;
                for(int i=0;i<count;i++) {
                    ascii(out,"--"+(i==0?"fixture":"nested"+(i-1))+"\r\nContent-Type: multipart/mixed; boundary=nested"+i+"\r\n\r\n");
                }
                ascii(out,"--nested"+(count-1)+"\r\nContent-Type: text/plain\r\n\r\nsmall\r\n");
                for(int i=count-1;i>=0;i--) ascii(out,"--nested"+i+"--\r\n");
            } else {
                int count=mode.equals("parts")?200000:mode.equals("parts257")?257:1;
                for(int i=0;i<count;i++) ascii(out,"--fixture\r\nContent-Type: text/plain\r\n\r\nx\r\n");
                if(mode.equals("attachment")) {
                    ascii(out,"--fixture\r\nContent-Type: image/jpeg\r\nContent-Disposition: attachment; filename=fixture.jpg\r\n\r\n");
                    fill(out,10*1024*1024,true); ascii(out,"\r\n");
                }
            }
            ascii(out,"--fixture--\r\n");
            if(mode.equals("epilogue")) fill(out,15*1024*1024,true);
        }
    }
    private static String repeat(char c,int n) { char[] a=new char[n]; Arrays.fill(a,c); return new String(a); }
}
