package com.tirso.tirsolador;

import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Small file helpers shared by the app (saves themselves are handled by SaveManager). */
final class GameStorage {
    static final int MAX_STATE=64*1024*1024,MAX_SAVE=32*1024*1024;
    private GameStorage(){}
    static void copy(InputStream in,OutputStream out,long limit) throws IOException {
        byte[] b=new byte[65536];long total=0;int n;
        while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IOException(T.t("Archivo demasiado grande"));out.write(b,0,n);}
    }
    static byte[] read(File f,int limit)throws IOException{try(InputStream in=new FileInputStream(f)){return read(in,limit);}}
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out,limit);return out.toByteArray();}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    static String hash(byte[] b)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(b));}
    static String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    static void write(File target,byte[] b)throws IOException{
        AtomicFile a=new AtomicFile(target);FileOutputStream out=null;
        try{out=a.startWrite();out.write(b);a.finishWrite(out);}catch(IOException e){if(out!=null)a.failWrite(out);throw e;}
    }
    static void copyAtomic(File source,File target)throws IOException{
        AtomicFile a=new AtomicFile(target);FileOutputStream out=null;
        try(InputStream in=new FileInputStream(source)){out=a.startWrite();copy(in,out,MAX_STATE+1024);a.finishWrite(out);}
        catch(IOException e){if(out!=null)a.failWrite(out);throw e;}
    }
}
