package com.tirso.tirsolador;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.sevenz.*;

/** Bounded extraction into a disposable workspace. Entry paths never become disk paths. */
final class ArchiveContent {
    static final long LIMIT=512L*1024*1024;
    static boolean rom(Console console,String name){String lower=name.toLowerCase(Locale.ROOT);return !lower.endsWith(".zip")&&!lower.endsWith(".7z")&&TelegramCatalog.supported(console,name);}
    static String base(String name)throws IOException{
        String s=new File(name.replace('\\','/')).getName();if(s.isEmpty()||s.equals(".")||s.equals(".."))throw new IOException(T.t("Nombre de archivo no válido"));return s;
    }
    static File prepare(File source,String name,Console console,File workspace)throws Exception{
        String lower=name.toLowerCase(Locale.ROOT);
        if(console==Console.MAME&&lower.endsWith(".zip")){
            File target=new File(workspace,base(name));try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(target)){GameStorage.copy(in,out,LIMIT);}return target;
        }
        if(lower.endsWith(".zip")){
            try(ZipFile zip=new ZipFile(source)){
                ZipEntry chosen=null;Enumeration<? extends ZipEntry> entries=zip.entries();int count=0;
                while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(++count>10000)throw new IOException(T.t("Archivo con demasiadas entradas"));
                    if(!e.isDirectory()&&rom(console,e.getName())){if(chosen!=null)throw new IOException(T.t("El archivo contiene varias ROMs; usa un archivo por juego"));chosen=e;}}
                if(chosen==null)throw new IOException(T.t("No hay una ROM de ")+console.title+T.t(" en este archivo"));
                File target=new File(workspace,base(chosen.getName()));try(InputStream in=zip.getInputStream(chosen);OutputStream out=new FileOutputStream(target)){GameStorage.copy(in,out,LIMIT);}return target;
            }
        }
        if(lower.endsWith(".7z")){
            try(SevenZFile archive=SevenZFile.builder().setFile(source).setMaxMemoryLimitKb(65536).get()){
                if(console==Console.MAME){
                    File target=new File(workspace,base(name.substring(0,name.length()-3)+".zip"));int count=0;long total=0;
                    try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(target))){
                        SevenZArchiveEntry e;while((e=archive.getNextEntry())!=null){if(++count>10000)throw new IOException(T.t("Archivo con demasiadas entradas"));if(e.isDirectory())continue;
                            String entry=base(e.getName());zip.putNextEntry(new ZipEntry(entry));byte[] b=new byte[65536];int n;
                            while((n=archive.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException(T.t("Archivo demasiado grande"));zip.write(b,0,n);}zip.closeEntry();
                        }
                    }return target;
                }
                File target=null;int count=0;SevenZArchiveEntry e;
                while((e=archive.getNextEntry())!=null){if(++count>10000)throw new IOException(T.t("Archivo con demasiadas entradas"));if(e.isDirectory()||!rom(console,e.getName()))continue;
                    if(target!=null)throw new IOException(T.t("El archivo contiene varias ROMs; usa un archivo por juego"));target=new File(workspace,base(e.getName()));
                    try(OutputStream out=new FileOutputStream(target)){byte[]b=new byte[65536];long total=0;int n;while((n=archive.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException(T.t("Archivo demasiado grande"));out.write(b,0,n);}}
                }
                if(target==null)throw new IOException(T.t("No hay una ROM de ")+console.title+T.t(" en este archivo"));return target;
            }
        }
        if(!rom(console,name))throw new IOException(T.t("Archivo no compatible con ")+console.title);
        return source;
    }
    static void removeTree(File root){if(root==null)return;File[]children=root.listFiles();if(children!=null)for(File f:children)removeTree(f);root.delete();}
}
