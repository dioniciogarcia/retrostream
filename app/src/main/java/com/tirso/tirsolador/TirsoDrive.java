package com.tirso.tirsolador;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Process-level TDLib client; UI callbacks and catalog mutations are serialized on main. */
final class TirsoDrive {
    enum Stage { CONFIG, CONNECTING, QR, PASSWORD, CODE, READY, ERROR, OTHER }
    interface Listener { void changed(boolean catalogChanged); }
    static final class Source {
        final Console console;
        final List<TdApi.Chat> candidates=new ArrayList<>();
        long chatId;
        String title="", issue=T.t("Sin grupo asignado");
        Source(Console c){console=c;}
    }
    private static TirsoDrive instance;
    static synchronized TirsoDrive get(Context context){if(instance==null)instance=new TirsoDrive(context.getApplicationContext());return instance;}
    private final Context context;
    private final SharedPreferences prefs;
    private final SecretStore secrets;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Client client;
    private Listener listener;
    private final Map<Long,TdApi.Chat> chats=new HashMap<>();
    final EnumMap<Console,Source> sources=new EnumMap<>(Console.class);
    private final Map<Integer,TdApi.File> files=new HashMap<>();
    private final Set<Integer> requestedDownloads=new HashSet<>();
    private final Set<Integer> watchedFiles=new HashSet<>();
    Stage stage=Stage.CONFIG;
    String status=T.t("Conecta tu biblioteca de Telegram"), qrLink="", error="";
    boolean discovering,syncing;
    long accountId;
    private int generation;
    private int clientGeneration;
    private boolean starting,logoutRequested,restartRequested,parametersSent,qrRequested;
    private int lastDownloadPercent=-1;
    private final java.util.LinkedHashSet<Console> refreshQueue=new java.util.LinkedHashSet<>();
    private final Set<Integer> deletingFiles=new HashSet<>();
    private final Map<Integer,Game> playAfterDelete=new HashMap<>();
    private TirsoDrive(Context context){
        this.context=context;prefs=context.getSharedPreferences("tirsodrive",Context.MODE_PRIVATE);secrets=new SecretStore(context);
        for(Console c:Console.values())sources.put(c,new Source(c));
    }
    void attach(Listener listener){this.listener=listener;}
    void detach(Listener listener){if(this.listener==listener)this.listener=null;}
    private void publish(boolean catalog){if(listener!=null)listener.changed(catalog);}
    boolean skipped(){return prefs.getBoolean("offlineSkipped",false);}
    void skip(){prefs.edit().putBoolean("offlineSkipped",true).apply();}
    void enterConnection(){prefs.edit().putBoolean("offlineSkipped",false).apply();start();}
    boolean onboarded(){return prefs.getBoolean("onboarded",false);}
    void finishOnboarding(){if(stage==Stage.READY)prefs.edit().putBoolean("onboarded",true).putBoolean("offlineSkipped",false).apply();}
    static boolean validHash(String hash){return hash!=null&&hash.matches("[a-fA-F0-9]{32}");}
    private String hash() throws Exception {
        String local=secrets.get("apiHash");return local.isEmpty()?BuildConfig.TELEGRAM_API_HASH.trim():local;
    }
    void configureHash(String hash){
        if(!validHash(hash)){error=T.t("El API hash debe tener 32 caracteres hexadecimales.");publish(false);return;}
        try{
            secrets.put("apiHash",hash);error="";
            if(client!=null){restartRequested=true;generation++;syncing=false;discovering=false;checked(new TdApi.Close());}
            else start();
        }catch(Exception e){error=T.t("No se pudo guardar la configuración protegida del dispositivo.");publish(false);}
    }
    void start(){
        if(client!=null){retry();return;}if(starting)return;
        try{if(!validHash(hash())){stage=Stage.CONFIG;status=T.t("Configura TirsoDrive para generar el QR");publish(false);return;}}
        catch(Exception e){stage=Stage.ERROR;error=T.t("No se pudo abrir la configuración cifrada. Revisa las credenciales en Ajustes.");publish(false);return;}
        starting=true;parametersSent=false;qrRequested=false;stage=Stage.CONNECTING;status=T.t("Conectando con Telegram…");publish(false);
        new Thread(()->{
            try{
                System.loadLibrary("tdjni");
                Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamEmpty()));
                Client.execute(new TdApi.SetLogVerbosityLevel(0));
                main.post(()->{
                    try{
                        int session=++clientGeneration;
                        client=Client.create(object->main.post(()->{if(session==clientGeneration)update(object);}),
                            e->main.post(()->{if(session==clientGeneration)nativeError();}),
                            e->main.post(()->{if(session==clientGeneration)nativeError();}));
                        starting=false;retry();
                    }catch(Throwable e){starting=false;stage=Stage.ERROR;error=T.t("No se pudo crear la sesión nativa de Telegram.");publish(false);}
                });
            }catch(Throwable e){main.post(()->{starting=false;stage=Stage.ERROR;error=T.t("No se pudo iniciar TDLib en este dispositivo.");publish(false);});}
        },"tdlib-start").start();
    }
    private void nativeError(){error=T.t("Telegram encontró un problema de conexión. Puedes reintentar.");publish(false);}
    private void send(TdApi.Function<?> function,Consumer<TdApi.Object> result){
        Client current=client;if(current==null)return;
        current.send(function,object->main.post(()->{if(current==client)result.accept(object);}));
    }
    private void checked(TdApi.Function<?> function){send(function,object->{if(object instanceof TdApi.Error)fail((TdApi.Error)object);});}
    private void fail(TdApi.Error failure){
        if(failure.message.contains("API_ID_INVALID")||failure.message.contains("API_HASH_INVALID"))error=T.t("Telegram rechazó las credenciales de la aplicación. Revisa el API hash de TirsoDrive.");
        else if(failure.message.contains("PASSWORD_HASH_INVALID"))error=T.t("La contraseña de verificación adicional no es correcta.");
        else if(failure.message.contains("PHONE_CODE_INVALID"))error=T.t("El código no es correcto. Comprueba el último código recibido.");
        else if(failure.message.startsWith("FLOOD_WAIT"))error=T.t("Telegram solicita esperar antes de reintentar.");
        else error=T.t("No se pudo completar la solicitud a Telegram (código ")+failure.code+").";
        publish(false);
    }
    void retry(){if(client==null){start();return;}error="";send(new TdApi.GetAuthorizationState(),this::authorization);}
    private void update(TdApi.Object object){
        if(object instanceof TdApi.UpdateAuthorizationState)authorization(((TdApi.UpdateAuthorizationState)object).authorizationState);
        else if(object instanceof TdApi.UpdateNewChat){TdApi.Chat c=((TdApi.UpdateNewChat)object).chat;chats.put(c.id,c);}
        else if(object instanceof TdApi.UpdateChatTitle){TdApi.UpdateChatTitle u=(TdApi.UpdateChatTitle)object;TdApi.Chat c=chats.get(u.chatId);if(c!=null)c.title=u.title;}
        else if(object instanceof TdApi.UpdateFile)fileUpdated(((TdApi.UpdateFile)object).file);
        else if(object instanceof TdApi.UpdateMessageSendSucceeded){
            TdApi.UpdateMessageSendSucceeded u=(TdApi.UpdateMessageSendSucceeded)object;Done<Long> d=pendingUploads.remove(u.oldMessageId);if(d!=null)d.done(u.message.id,null);
        }else if(object instanceof TdApi.UpdateMessageSendFailed){
            TdApi.UpdateMessageSendFailed u=(TdApi.UpdateMessageSendFailed)object;Done<Long> d=pendingUploads.remove(u.oldMessageId);if(d!=null)d.done(0L,errorText(u.error));
        }
    }
    private void authorization(TdApi.Object object){
        if(object instanceof TdApi.Error){fail((TdApi.Error)object);return;}
        error="";
        if(object instanceof TdApi.AuthorizationStateWaitTdlibParameters){
            if(parametersSent)return;parametersSent=true;
            stage=Stage.CONNECTING;status=T.t("Preparando tu sesión protegida…");
            try{
                TdApi.SetTdlibParameters p=new TdApi.SetTdlibParameters();
                p.useTestDc=false;p.databaseDirectory=new File(context.getFilesDir(),"telegram/database").getAbsolutePath();
                p.filesDirectory=new File(context.getFilesDir(),"telegram/files").getAbsolutePath();p.databaseEncryptionKey=secrets.databaseKey();
                p.useFileDatabase=true;p.useChatInfoDatabase=true;p.useMessageDatabase=true;p.useSecretChats=false;
                p.apiId=BuildConfig.TELEGRAM_API_ID;p.apiHash=hash();p.systemLanguageCode="es";p.deviceModel=android.os.Build.MODEL;
                p.systemVersion=android.os.Build.VERSION.RELEASE;p.applicationVersion=BuildConfig.VERSION_NAME;checked(p);
            }catch(Exception e){stage=Stage.ERROR;error=T.t("No se pudo preparar el almacenamiento seguro de Telegram.");}
        }else if(object instanceof TdApi.AuthorizationStateWaitPhoneNumber){
            if(qrRequested)return;qrRequested=true;stage=Stage.CONNECTING;status=T.t("Generando código QR…");checked(new TdApi.RequestQrCodeAuthentication(new long[0]));
        }else if(object instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation){
            stage=Stage.QR;status=T.t("Escanea el QR desde Telegram en tu teléfono");qrLink=((TdApi.AuthorizationStateWaitOtherDeviceConfirmation)object).link;
        }else if(object instanceof TdApi.AuthorizationStateWaitPassword){stage=Stage.PASSWORD;qrLink="";status=T.t("Tu cuenta requiere la contraseña de verificación en dos pasos");
        }else if(object instanceof TdApi.AuthorizationStateWaitCode){stage=Stage.CODE;qrLink="";status=T.t("Telegram solicita un código de verificación");
        }else if(object instanceof TdApi.AuthorizationStateReady){
            stage=Stage.READY;qrLink="";status=T.t("Sesión conectada");
            if(accountId==0)send(new TdApi.GetMe(),me->{
                if(me instanceof TdApi.User){
                    long previous=prefs.getLong("accountId",0);accountId=((TdApi.User)me).id;
                    if(previous!=accountId){new Library(context).clearTelegram();publish(true);}
                    prefs.edit().putLong("accountId",accountId).apply();discover();
                }else if(me instanceof TdApi.Error)fail((TdApi.Error)me);
            });
        }else if(object instanceof TdApi.AuthorizationStateLoggingOut){stage=Stage.CONNECTING;status=T.t("Cerrando sesión…");
        }else if(object instanceof TdApi.AuthorizationStateClosed){
            client=null;clientGeneration++;stage=Stage.CONFIG;qrLink="";accountId=0;generation++;syncing=false;discovering=false;chats.clear();
            for(Source s:sources.values()){s.chatId=0;s.title="";s.issue=T.t("Sin grupo asignado");s.candidates.clear();}files.clear();requestedDownloads.clear();watchedFiles.clear();
            if(logoutRequested||restartRequested){logoutRequested=false;restartRequested=false;start();}
            else{status=T.t("Sesión cerrada. Vuelve a conectar.");}
        }else if(object instanceof TdApi.AuthorizationStateClosing){stage=Stage.CONNECTING;status=T.t("Cerrando la conexión…");
        }else{stage=Stage.OTHER;qrLink="";status=T.t("Telegram requiere un paso adicional. Completa la verificación en su aplicación o vuelve a conectar.");}
        publish(false);
    }
    void password(String password){error="";checked(new TdApi.CheckAuthenticationPassword(password));}
    void code(String code){error="";checked(new TdApi.CheckAuthenticationCode(code));}
    void logout(){
        stage=Stage.CONNECTING;status=T.t("Cerrando sesión…");
        generation++;syncing=false;discovering=false;logoutRequested=true;requestedDownloads.clear();watchedFiles.clear();files.clear();
        prefs.edit().remove("binding_"+accountId).putBoolean("onboarded",false).putBoolean("offlineSkipped",false).remove("accountId").apply();
        new Library(context).clearTelegram();accountId=0;chats.clear();for(Source s:sources.values()){s.chatId=0;s.candidates.clear();s.title="";}
        publish(true);checked(new TdApi.LogOut());
    }
    void discover(){
        if(stage!=Stage.READY||accountId==0||discovering||syncing)return;
        discovering=true;error="";status=T.t("Buscando grupos principales y archivados…");
        int token=++generation;publish(false);
        loadList(new TdApi.ChatListMain(),0,token,()->loadList(new TdApi.ChatListArchive(),0,token,()->assign(token)));
    }
    private void loadList(TdApi.ChatList list,int page,int token,Runnable finished){
        if(token!=generation)return;
        if(page>=1000){discovering=false;error=T.t("La lista de chats superó el límite de esta versión. No se modificaron las fuentes.");publish(false);return;}
        send(new TdApi.LoadChats(list,100),object->{
            if(token!=generation)return;
            if(object instanceof TdApi.Ok)loadList(list,page+1,token,finished);
            else if(object instanceof TdApi.Error&&((TdApi.Error)object).code==404){
                send(new TdApi.GetChats(list,10000),result->{
                    if(token!=generation)return;
                    if(result instanceof TdApi.Chats)hydrate(((TdApi.Chats)result).chatIds,0,token,finished);
                    else{discovering=false;if(result instanceof TdApi.Error)fail((TdApi.Error)result);}
                });
            }
            else{discovering=false;if(object instanceof TdApi.Error)fail((TdApi.Error)object);}
        });
    }
    private void hydrate(long[] ids,int index,int token,Runnable finished){
        if(token!=generation)return;
        while(index<ids.length&&chats.containsKey(ids[index]))index++;
        if(index>=ids.length){finished.run();return;}
        int next=index+1;
        send(new TdApi.GetChat(ids[index]),result->{
            if(token!=generation)return;
            if(result instanceof TdApi.Chat){TdApi.Chat chat=(TdApi.Chat)result;chats.put(chat.id,chat);}
            hydrate(ids,next,token,finished);
        });
    }
    private void assign(int token){
        if(token!=generation)return;
        JSONObject saved;
        try{saved=new JSONObject(prefs.getString("binding_"+accountId,"{}"));}catch(Exception e){saved=new JSONObject();}
        int found=0;
        for(Source source:sources.values()){
            source.candidates.clear();source.chatId=0;source.title="";
            for(TdApi.Chat chat:chats.values())if(TelegramCatalog.group(chat)&&TelegramCatalog.consoleFor(chat.title)==source.console)source.candidates.add(chat);
            long existing=saved.optLong(source.console.name(),0);
            if(existing!=0&&chats.containsKey(existing)&&TelegramCatalog.group(chats.get(existing))&&(TelegramCatalog.consoleFor(chats.get(existing).title)==null||TelegramCatalog.consoleFor(chats.get(existing).title)==source.console)){source.chatId=existing;source.title=chats.get(existing).title;}
            else if(source.candidates.size()==1){source.chatId=source.candidates.get(0).id;source.title=source.candidates.get(0).title;}
            if(source.console==Console.GBA)for(TdApi.Chat candidate:source.candidates)if(candidate.title.replaceAll("[^a-zA-Z0-9]","").equalsIgnoreCase("DDROMSGBA")){source.chatId=candidate.id;source.title=candidate.title;}
            source.issue=source.chatId!=0?T.t("Asignado"):source.candidates.size()>1?T.t("Elige el grupo correcto"):T.t("Sin grupo asignado");
            if(source.chatId!=0)found++;
        }
        persistBindings();discovering=false;status=found+T.t(" consolas con grupo asignado");publish(false);
        if(found>0)sync();else drainRefresh();
    }
    private void persistBindings(){
        JSONObject value=new JSONObject();try{for(Source s:sources.values())if(s.chatId!=0)value.put(s.console.name(),s.chatId);}catch(Exception ignored){ }
        prefs.edit().putString("binding_"+accountId,value.toString()).apply();
    }
    List<TdApi.Chat> choices(Console c){
        Source source=sources.get(c);List<TdApi.Chat> result=new ArrayList<>();
        if(source.candidates.size()>1)result.addAll(source.candidates);
        else for(TdApi.Chat chat:chats.values())if(TelegramCatalog.group(chat)&&TelegramCatalog.selectable(chat.title))result.add(chat);
        result.sort((a,b)->a.title.compareToIgnoreCase(b.title));return result;
    }
    void bind(Console c,long id){
        if(stage!=Stage.READY||syncing||discovering)return;TdApi.Chat chat=chats.get(id);
        if(chat==null||!TelegramCatalog.group(chat)||!TelegramCatalog.selectable(chat.title))return;
        for(Source other:sources.values())if(other.console!=c&&other.chatId==id){error=T.t("Ese grupo ya está asignado a ")+other.console.title+".";publish(false);return;}
        Source source=sources.get(c);source.chatId=id;source.title=chat.title;source.issue=T.t("Asignado");persistBindings();error="";publish(false);
    }
    void unbind(Console c){if(syncing||discovering)return;Source s=sources.get(c);s.chatId=0;s.title="";s.issue=T.t("Sin grupo asignado");persistBindings();publish(false);}
    void sync(){
        if(stage!=Stage.READY||accountId==0||discovering||syncing)return;
        List<Source> bound=new ArrayList<>();for(Source s:sources.values())if(s.chatId!=0)bound.add(s);
        if(bound.isEmpty()){error=T.t("No hay grupos asignados. Elige las fuentes de tus consolas.");publish(false);return;}
        syncing=true;error="";int token=++generation;
        history(bound,0,0,new HashSet<>(),new ArrayList<>(),token);
    }
    void refresh(Console console){
        if(console==null){sync();return;}
        refreshQueue.add(console);
        if(stage==Stage.READY)drainRefresh();
    }
    private void drainRefresh(){
        if(stage!=Stage.READY||syncing||discovering||refreshQueue.isEmpty())return;
        Console console=refreshQueue.iterator().next();Source source=sources.get(console);
        if(source.chatId==0){
            // Re-discover exact aliases, including newly introduced GBA/GBC/MAME groups.
            List<TdApi.Chat> matches=new ArrayList<>();for(TdApi.Chat chat:chats.values())if(TelegramCatalog.group(chat)&&TelegramCatalog.consoleFor(chat.title)==console)matches.add(chat);
            if(matches.size()==1){source.chatId=matches.get(0).id;source.title=matches.get(0).title;source.issue=T.t("Asignado");persistBindings();}
            if(source.chatId==0){refreshQueue.remove(console);status=console.title+T.t(": revisa el grupo en Fuentes");publish(false);drainRefresh();return;}
        }
        refreshQueue.remove(console);syncing=true;error="";
        List<Game> retained=new ArrayList<>();for(Game g:new Library(context).games)if(g.telegramAccount==accountId&&g.console!=console)retained.add(g);
        List<Source> one=new ArrayList<>();one.add(source);history(one,0,0,new HashSet<>(),retained,++generation);
    }
    private void history(List<Source> bound,int index,long cursor,Set<Long> seen,List<Game> games,int token){
        if(token!=generation||!syncing)return;
        if(index>=bound.size()){
            try{new Library(context).replaceTelegram(games);status=T.t("Catálogo actualizado: ")+games.size()+T.t(" juegos");error="";syncing=false;publish(true);main.post(this::drainRefresh);}
            catch(Exception e){syncing=false;error=T.t("No se pudo guardar el catálogo. Se conservó la biblioteca anterior.");publish(false);}return;
        }
        if(games.size()>=50000||seen.size()>=100000){syncing=false;error=T.t("El grupo supera la capacidad de esta versión. Se conservó el catálogo anterior.");publish(false);return;}
        Source source=bound.get(index);status=T.t("Leyendo ")+source.console.title+" · "+games.size()+T.t(" juegos encontrados");publish(false);
        send(new TdApi.GetChatHistory(source.chatId,cursor,0,100,false),object->{
            if(token!=generation||!syncing)return;
            if(object instanceof TdApi.Error){syncing=false;fail((TdApi.Error)object);return;}
            if(!(object instanceof TdApi.Messages)){syncing=false;error=T.t("Telegram no devolvió un historial válido.");publish(false);return;}
            TdApi.Message[] messages=((TdApi.Messages)object).messages;
            int added=0;long oldest=cursor;
            for(TdApi.Message message:messages){
                oldest=message.id;if(!seen.add(message.id))continue;added++;
                Game game=TelegramCatalog.game(accountId,source.console,message);if(game!=null)games.add(game);
            }
            if(messages.length==0||added==0||oldest==cursor)history(bound,index+1,0,new HashSet<>(),games,token);
            else history(bound,index,oldest,seen,games,token);
        });
    }
    void cancelSync(){if(syncing||discovering){generation++;syncing=false;discovering=false;status=T.t("Actualización cancelada. Se conservó el catálogo anterior.");publish(false);}}
    void inspectFile(Game game){if(stage==Stage.READY&&game.telegramAccount==accountId&&game.fileId>0){watchedFiles.add(game.fileId);send(new TdApi.GetFile(game.fileId),r->{if(r instanceof TdApi.File)fileUpdated((TdApi.File)r);});}}
    TdApi.File file(Game game){TdApi.File f=files.get(game.fileId);return f!=null&&f.local.isDownloadingCompleted&&!new File(f.local.path).isFile()?null:f;}
    boolean owns(Game game){Source s=sources.get(game.console);return stage==Stage.READY&&game.telegramAccount==accountId&&s.chatId==game.chatId&&game.fileId>0;}
    void download(Game game){
        if(deletingFiles.contains(game.fileId)){playAfterDelete.put(game.fileId,game);return;}
        if(!owns(game)){error=T.t("Conecta la cuenta y el grupo de este juego antes de jugar.");publish(false);return;}
        android.os.StatFs space=new android.os.StatFs(context.getFilesDir().getAbsolutePath());
        if(space.getAvailableBytes()<game.fileSize+64L*1024*1024){error=T.t("No hay suficiente espacio libre en la TV para este archivo.");publish(false);return;}
        requestedDownloads.add(game.fileId);watchedFiles.add(game.fileId);lastDownloadPercent=-1;error="";
        send(new TdApi.GetFile(game.fileId),r->{
            if(r instanceof TdApi.File&&((TdApi.File)r).local.isDownloadingCompleted&&!new File(((TdApi.File)r).local.path).isFile()){
                deletingFiles.add(game.fileId);send(new TdApi.DeleteFile(game.fileId),ignored->{deletingFiles.remove(game.fileId);startDownload(game);});
            }else startDownload(game);
        });
    }
    private void startDownload(Game game){
        if(!requestedDownloads.contains(game.fileId)||!owns(game))return;
        send(new TdApi.DownloadFile(game.fileId,16,0,0,false),r->{
            if(r instanceof TdApi.File)fileUpdated((TdApi.File)r);
            else if(r instanceof TdApi.Error){requestedDownloads.remove(game.fileId);fail((TdApi.Error)r);}
        });
    }
    void cancelDownload(Game game){checked(new TdApi.CancelDownloadFile(game.fileId,false));requestedDownloads.remove(game.fileId);releaseRom(game.fileId,game.telegramAccount);}
    private void fileUpdated(TdApi.File file){
        if(!watchedFiles.contains(file.id))return;
        files.put(file.id,file);
        if(!requestedDownloads.contains(file.id)){publish(false);return;}
        int percent=(int)Math.min(100,file.local.downloadedSize*100/Math.max(1,Math.max(file.size,file.expectedSize)));
        if(file.local.isDownloadingCompleted){requestedDownloads.remove(file.id);status=T.t("Listo para jugar");publish(false);}
        else if(percent!=lastDownloadPercent){lastDownloadPercent=percent;publish(false);}
    }
    void releaseRom(int fileId,long account){main.post(()->{
        if(fileId<1||account!=accountId||client==null)return;
        deletingFiles.add(fileId);files.remove(fileId);requestedDownloads.remove(fileId);watchedFiles.remove(fileId);
        send(new TdApi.DeleteFile(fileId),result->{deletingFiles.remove(fileId);Game next=playAfterDelete.remove(fileId);publish(false);if(next!=null)download(next);});
    });}

    // ------------------------------------------------------------------ save backups (Telegram channel)
    static final String BACKUP_TITLE="TD - RetroStream - Save file back-ups";
    static final int KEEP_BACKUPS=10;
    interface Done<V>{void done(V value,String error);}
    static final class Backup{String sha="",title="",console="",fileName="";long messageId,size;int fileId,date;}
    private final Map<Long,Done<Long>> pendingUploads=new HashMap<>();
    private static String norm(String s){return s==null?"":s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private TdApi.Chat findBackupChat(){
        long saved=prefs.getLong("backupChat_"+accountId,0);
        TdApi.Chat c=saved!=0?chats.get(saved):null;if(c!=null)return c;
        for(TdApi.Chat chat:chats.values())if(TelegramCatalog.group(chat)&&norm(chat.title).equals(norm(BACKUP_TITLE)))return chat;
        return null;
    }
    private String errorText(TdApi.Object r){
        if(r instanceof TdApi.Error)return T.t("Telegram respondió: ")+((TdApi.Error)r).message;
        return T.t("Telegram no devolvió una respuesta válida.");
    }
    private <V> Done<V> once(Done<V> cb,V empty){
        final boolean[] fired={false};
        Done<V> wrapped=(v,e)->{if(fired[0])return;fired[0]=true;cb.done(v,e);};
        main.postDelayed(()->wrapped.done(empty,T.t("Telegram tardó demasiado en responder. Inténtalo de nuevo.")),90000);
        return wrapped;
    }
    /** Finds this account's save-backup channel, creating it the first time. */
    void backupChannel(Done<Long> callback){
        Done<Long> cb=once(callback,0L);
        if(stage!=Stage.READY||accountId==0){cb.done(0L,T.t("Conecta TirsoDrive para usar las copias de partidas en Telegram."));return;}
        TdApi.Chat found=findBackupChat();
        if(found!=null){prefs.edit().putLong("backupChat_"+accountId,found.id).apply();cb.done(found.id,null);return;}
        int token=generation;
        loadList(new TdApi.ChatListMain(),0,token,()->loadList(new TdApi.ChatListArchive(),0,token,()->{
            TdApi.Chat again=findBackupChat();
            if(again!=null){prefs.edit().putLong("backupChat_"+accountId,again.id).apply();cb.done(again.id,null);return;}
            send(new TdApi.CreateNewSupergroupChat(BACKUP_TITLE,false,true,"RetroStream · copias automáticas de partidas guardadas",null,0,false),r->{
                if(r instanceof TdApi.Chat){TdApi.Chat chat=(TdApi.Chat)r;chats.put(chat.id,chat);prefs.edit().putLong("backupChat_"+accountId,chat.id).apply();cb.done(chat.id,null);}
                else cb.done(0L,errorText(r));
            });
        }));
    }
    void uploadBackup(long chat,File zip,String caption,Done<Long> callback){
        Done<Long> cb=once(callback,0L);
        TdApi.InputMessageDocument content=new TdApi.InputMessageDocument(
            new TdApi.InputDocument(new TdApi.InputFileLocal(zip.getAbsolutePath()),null,true),new TdApi.FormattedText(caption,new TdApi.TextEntity[0]));
        send(new TdApi.SendMessage(chat,null,null,null,null,content),r->{
            if(r instanceof TdApi.Message)pendingUploads.put(((TdApi.Message)r).id,cb);else cb.done(0L,errorText(r));
        });
    }
    private Backup parseBackup(TdApi.Message m){
        if(!(m.content instanceof TdApi.MessageDocument))return null;
        TdApi.MessageDocument d=(TdApi.MessageDocument)m.content;
        if(d.caption==null||d.document==null||d.document.document==null||!d.caption.text.startsWith("#RSSAVE"))return null;
        Backup b=new Backup();
        for(String line:d.caption.text.split("\n")){
            int i=line.indexOf(':');if(i<0)continue;String k=line.substring(0,i),v=line.substring(i+1).trim();
            if(k.equals("sha"))b.sha=v;else if(k.equals("title"))b.title=v;else if(k.equals("console"))b.console=v;
        }
        if(b.sha.isEmpty()||b.title.isEmpty())return null;
        b.messageId=m.id;b.date=m.date;b.fileName=d.document.fileName;b.fileId=d.document.document.id;
        b.size=Math.max(d.document.document.size,d.document.document.expectedSize);return b;
    }
    /** Every backup in the channel, newest first. */
    void listBackups(long chat,Done<List<Backup>> callback){
        Done<List<Backup>> cb=once(callback,null);
        pageBackups(chat,0,new ArrayList<>(),new HashSet<>(),cb);
    }
    private void pageBackups(long chat,long cursor,List<Backup> out,Set<Long> seen,Done<List<Backup>> cb){
        send(new TdApi.GetChatHistory(chat,cursor,0,100,false),r->{
            if(!(r instanceof TdApi.Messages)){cb.done(null,errorText(r));return;}
            TdApi.Messages page=(TdApi.Messages)r;int added=0;long oldest=cursor;
            for(TdApi.Message m:page.messages){oldest=m.id;if(!seen.add(m.id))continue;added++;Backup b=parseBackup(m);if(b!=null)out.add(b);}
            if(page.messages.length==0||added==0||oldest==cursor||seen.size()>20000){out.sort((a,b)->Integer.compare(b.date,a.date));cb.done(out,null);}
            else pageBackups(chat,oldest,out,seen,cb);
        });
    }
    void downloadBackup(Backup b,Done<File> callback){
        Done<File> cb=once(callback,null);
        send(new TdApi.DownloadFile(b.fileId,32,0,0,true),r->{
            if(r instanceof TdApi.File&&((TdApi.File)r).local.isDownloadingCompleted)cb.done(new File(((TdApi.File)r).local.path),null);
            else cb.done(null,errorText(r));
        });
    }
    void releaseBackup(Backup b){checked(new TdApi.DeleteFile(b.fileId));}
    /** Keeps the newest [KEEP_BACKUPS] backups per game; older ones are removed from the channel. */
    void pruneBackups(long chat,List<Backup> all){
        Map<String,List<Long>> byGame=new HashMap<>();
        for(Backup b:all){List<Long> l=byGame.get(b.sha);if(l==null){l=new ArrayList<>();byGame.put(b.sha,l);}l.add(b.messageId);}
        List<Long> old=new ArrayList<>();
        for(Backup b:all){List<Long> l=byGame.get(b.sha);}
        Map<String,Integer> rank=new HashMap<>();
        for(Backup b:all){int n=rank.containsKey(b.sha)?rank.get(b.sha):0;rank.put(b.sha,n+1);if(n>=KEEP_BACKUPS)old.add(b.messageId);}
        if(old.isEmpty())return;long[] ids=new long[old.size()];for(int i=0;i<ids.length;i++)ids[i]=old.get(i);
        checked(new TdApi.DeleteMessages(chat,ids,true));
    }
    boolean ready(){return stage==Stage.READY&&accountId!=0;}
}
