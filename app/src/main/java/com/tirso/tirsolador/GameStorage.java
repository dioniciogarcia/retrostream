package com.tirso.tirsolador;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Private game data keyed by ROM digest. Atomic states and verified portable backups. */
final class GameStorage {
    static final int MAX_STATE=64*1024*1024,MAX_SAVE=32*1024*1024;
    final File rom,dir,workspace;
    final String digest,revision,coreName;
    final Console console;
    GameStorage(Context context,File input) throws Exception {
        this(context,input,input.getName());
    }
    GameStorage(Context context,File input,String name) throws Exception {
        this(context,input,name,Console.NDS);
    }
    GameStorage(Context context,File input,String name,Console console) throws Exception {
        this.console=console;revision=CoreRegistry.revision(context,console);coreName=CoreRegistry.name(context,console);
        workspace=java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(),"play-").toFile();
        File prepared;
        try{prepared=ArchiveContent.prepare(input,name,console,workspace);
            if(prepared.length()<1||prepared.length()>ArchiveContent.LIMIT)throw new IOException(T.t("Tamaño de ROM no válido"));
        }catch(Exception e){ArchiveContent.removeTree(workspace);throw e;}
        rom=prepared;digest=hash(rom);dir=new File(context.getFilesDir(),"saves/"+CoreRegistry.key(console)+"/"+digest);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException(T.t("No se puede crear la carpeta de guardado"));
    }
    void cleanup(){ArchiveContent.removeTree(workspace);}
    File battery(){return new File(dir,"battery.sav");}
    File stateFile(){return new File(dir,"slot1.tss");}
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
    byte[] wrapState(byte[] state)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeUTF("TIRSO-DS-STATE-1");out.writeUTF(digest);out.writeUTF(revision);out.writeUTF(hash(state));out.writeInt(state.length);out.write(state);return bytes.toByteArray();
    }
    byte[] unwrapState(byte[] bytes)throws Exception{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(!in.readUTF().equals("TIRSO-DS-STATE-1")||!in.readUTF().equals(digest)||!in.readUTF().equals(revision))throw new IOException(T.t("El estado pertenece a otro juego o versión del núcleo"));
        String checksum=in.readUTF();int n=in.readInt();if(n<16||n>MAX_STATE||n!=in.available())throw new IOException(T.t("Estado incompleto"));
        byte[] result=new byte[n];in.readFully(result);if(!hash(result).equals(checksum))throw new IOException(T.t("Estado dañado"));return result;
    }
    void saveState(byte[] bytes)throws Exception{saveStateFile(stateFile(),bytes);}
    void saveBackupState(byte[] bytes)throws Exception{saveStateFile(new File(dir,"backup-session.tss"),bytes);}
    private void saveStateFile(File target,byte[] bytes)throws Exception{
        AtomicFile a=new AtomicFile(target);FileOutputStream file=null;
        try{file=a.startWrite();DataOutputStream out=new DataOutputStream(file);
            out.writeUTF("TIRSO-DS-STATE-1");out.writeUTF(digest);out.writeUTF(revision);out.writeUTF(hash(bytes));out.writeInt(bytes.length);out.write(bytes);out.flush();a.finishWrite(file);
        }catch(Exception e){if(file!=null)a.failWrite(file);throw e;}
    }
    byte[] loadState()throws Exception{return loadStateFile(stateFile());}
    byte[] loadBackupState()throws Exception{File f=new File(dir,"backup-session.tss");return f.exists()?loadStateFile(f):null;}
    private byte[] loadStateFile(File target)throws Exception{
        try(DataInputStream in=new DataInputStream(new FileInputStream(target))){
            if(!in.readUTF().equals("TIRSO-DS-STATE-1")||!in.readUTF().equals(digest)||!in.readUTF().equals(revision))throw new IOException(T.t("El estado pertenece a otro juego o versión del núcleo"));
            String checksum=in.readUTF();int n=in.readInt();if(n<16||n>MAX_STATE||n!=in.available())throw new IOException(T.t("Estado incompleto"));
            byte[] bytes=new byte[n];in.readFully(bytes);if(!hash(bytes).equals(checksum))throw new IOException(T.t("Estado dañado"));return bytes;
        }
    }
    void backup(OutputStream stream)throws Exception{
        Map<String,File> files=new LinkedHashMap<>();
        collect(dir,files);
        if(files.isEmpty())throw new IOException(T.t("Aún no hay partidas ni estados para respaldar"));
        JSONObject hashes=new JSONObject();for(Map.Entry<String,File>e:files.entrySet())hashes.put(e.getKey(),hash(e.getValue()));
        JSONObject manifest=new JSONObject().put("schema",1).put("rom",digest).put("core",revision).put("files",hashes);
        try(ZipOutputStream out=new ZipOutputStream(stream)){
            out.putNextEntry(new ZipEntry("manifest.json"));out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();
            for(Map.Entry<String,File>e:files.entrySet()){
                out.putNextEntry(new ZipEntry(e.getKey()));try(InputStream in=new FileInputStream(e.getValue())){copy(in,out,MAX_STATE+1024);}out.closeEntry();
            }
        }
    }
    void restore(InputStream stream)throws Exception{
        Map<String,byte[]> files=new LinkedHashMap<>();byte[] meta=null;int count=0;long total=0;
        try(ZipInputStream in=new ZipInputStream(stream)){
            ZipEntry e;while((e=in.getNextEntry())!=null){
                String name=e.getName();if(++count>257||e.isDirectory()||!safe(name))throw new IOException(T.t("Formato de respaldo no válido"));
                if(files.containsKey(name)||(name.equals("manifest.json")&&meta!=null))throw new IOException(T.t("Archivo duplicado en respaldo"));
                byte[] b=read(in,name.equals("manifest.json")?262144:name.equals("battery.sav")?MAX_SAVE:MAX_STATE+1024);
                total+=b.length;if(total>128L*1024*1024)throw new IOException(T.t("Respaldo demasiado grande"));
                if(name.equals("manifest.json"))meta=b;else files.put(name,b);
            }
        }
        if(meta==null||files.isEmpty())throw new IOException(T.t("Respaldo incompleto"));
        JSONObject manifest=new JSONObject(new String(meta,StandardCharsets.UTF_8));JSONObject hashes=manifest.getJSONObject("files");
        if(manifest.getInt("schema")!=1||!digest.equals(manifest.getString("rom"))||!revision.equals(manifest.getString("core"))||hashes.length()!=files.size())throw new IOException(T.t("El respaldo no pertenece a este juego o versión del núcleo"));
        for(Map.Entry<String,byte[]>e:files.entrySet()){
            if(!hash(e.getValue()).equals(hashes.getString(e.getKey())))throw new IOException(T.t("Respaldo dañado"));
            if(e.getKey().endsWith(".tss"))unwrapState(e.getValue());
        }
        Map<String,File> old=new LinkedHashMap<>();boolean preserve=false;
        try{
            for(String name:files.keySet()){
                File f=new File(dir,name),saved=null;
                if(f.exists()){saved=File.createTempFile("restore-old-",".bak",dir.getParentFile());old.put(name,saved);copyAtomic(f,saved);}else old.put(name,null);
            }
            try{for(Map.Entry<String,byte[]>e:files.entrySet()){File target=new File(dir,e.getKey());File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException(T.t("No se pudo crear la carpeta del respaldo"));write(target,e.getValue());}}
            catch(Exception x){
                for(Map.Entry<String,File>e:old.entrySet())try{File f=new File(dir,e.getKey());if(e.getValue()==null){if(f.exists()&&!f.delete())throw new IOException(T.t("No se pudo revertir la restauración"));}else copyAtomic(e.getValue(),f);}
                catch(IOException rollback){preserve=true;x.addSuppressed(rollback);}throw x;
            }
        }finally{if(!preserve)for(File f:old.values())if(f!=null)f.delete();}
    }
    private static boolean safe(String path){
        if(path==null||path.isEmpty()||path.length()>1024||path.startsWith("/")||path.contains("\\")||path.indexOf(0)>=0)return false;
        String[]parts=path.split("/",-1);if(parts.length>16)return false;for(String p:parts)if(p.isEmpty()||p.equals(".")||p.equals(".."))return false;return true;
    }
    private void collect(File folder,Map<String,File> files)throws Exception{
        File[] children=folder.listFiles();if(children==null)return;
        for(File f:children){String path=dir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');
            if(!safe(path)||!f.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator))throw new IOException(T.t("Ruta de guardado no válida"));
            if(f.isDirectory()){collect(f,files);continue;}
            if(f.getName().endsWith(".tmp")||f.getName().endsWith(".new")||f.getName().endsWith(".bak"))continue;
            if(f.length()>MAX_STATE+1024||files.size()>=256)throw new IOException(T.t("Los guardados superan el límite del respaldo"));files.put(path,f);
        }
        long size=0;for(File f:files.values())size+=f.length();if(size>128L*1024*1024)throw new IOException(T.t("Respaldo demasiado grande"));
    }
}
