package com.tirso.tirsolador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {
    private Library library;
    private Artwork artwork;
    private LinearLayout root, body, content;
    private ControllerProfiles controllers;
    private android.hardware.input.InputManager inputManager;
    private android.hardware.input.InputManager.InputDeviceListener inputListener;
    private ScrollView activeScroll;
    private int libraryScroll;
    private String libraryFocus = "";
    private TirsoDrive drive;
    private TirsoDrive.Listener driveListener;
    private TextView downloadLabel,downloadButton;
    private TextView catalogStatus;
    private android.widget.ProgressBar playProgress;
    private String pendingPlay="";
    private int visibleGames=60;
    private android.graphics.Bitmap qrBitmap;
    private String renderedQr="";
    private String lastDriveError="";
    private int text, muted, surface, background, accent, strokeColor;
    private Typeface fontRegular, fontBold;
    private boolean pixel;
    private String page = "home", query = "";
    private Console console;
    private Game selected;
    private boolean favorites;
    private String lastFocus = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        T.init(this);
        TemporaryRoms.sweep(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        library = new Library(this); artwork = new Artwork(this); controllers = new ControllerProfiles(this);
        drive=TirsoDrive.get(this);
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputListener=new android.hardware.input.InputManager.InputDeviceListener(){
            public void onInputDeviceAdded(int id) { }
            public void onInputDeviceChanged(int id) { controllers.disconnect(id); }
            public void onInputDeviceRemoved(int id) { controllers.disconnect(id); }
        };
        inputManager.registerInputDeviceListener(inputListener,null);
        if (state != null) {
            page = state.getString("page", "home"); query = state.getString("query", "");
            favorites = state.getBoolean("favorites"); lastFocus = state.getString("focus", "");
            libraryFocus=state.getString("libraryFocus","");libraryScroll=state.getInt("libraryScroll",0);
            try { console = Console.valueOf(state.getString("console", "")); } catch (IllegalArgumentException ignored) { }
            for (Game g : library.games) if (g.id.equals(state.getString("game"))) selected = g;
        }
        if(!drive.onboarded()&&!drive.skipped())page="welcome";
        render();
        driveListener=catalog->{
            if(catalog){rememberLibrary();library=new Library(this);}
            if(!drive.skipped()&&(drive.stage==TirsoDrive.Stage.QR||drive.stage==TirsoDrive.Stage.PASSWORD||drive.stage==TirsoDrive.Stage.CODE))page="welcome";
            if(page.equals("welcome")||catalog)render();
            else if(page.equals("detail"))updateDownload();
            else if(!drive.error.isEmpty()&&!drive.error.equals(lastDriveError))Toast.makeText(this,drive.error,Toast.LENGTH_LONG).show();
            lastDriveError=drive.error;
            if(catalogStatus!=null&&page.equals("library"))catalogStatus.setText(catalogMessage());
        };
        drive.attach(driveListener);drive.start();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("page", page); state.putString("query", query); state.putBoolean("favorites", favorites);
        state.putString("console", console == null ? "" : console.name());
        state.putString("game", selected == null ? "" : selected.id); state.putString("focus", lastFocus);
        rememberLibrary();state.putString("libraryFocus",libraryFocus);state.putInt("libraryScroll",libraryScroll);
    }
    int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(pixel ? size * 1.12f : size); t.setTextColor(color);
        Typeface face = bold ? fontBold : fontRegular; if (face != null) t.setTypeface(face);
        t.setPadding(0, dp(3), 0, dp(3)); return t;
    }
    private GradientDrawable panel(int color, boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{color,color}); g.setCornerRadius(dp(pixel ? 14 : 22));
        g.setStroke(dp(focused ? 3 : pixel ? 2 : 1), focused ? accent : strokeColor); return g;
    }
    private void focusable(View v, String key, int color, Runnable click) {
        v.setTag(key); v.setFocusable(true); v.setClickable(true); v.setBackground(panel(color, false));
        v.setOnClickListener(x -> click.run());
        v.setOnFocusChangeListener((x, has) -> {
            x.setBackground(panel(color, has));
            if (has) lastFocus = key;
            x.setElevation(dp(has ? 8 : 0));x.animate().scaleX(has?1.025f:1f).scaleY(has?1.025f:1f).setDuration(130).start();
        });
    }
    private TextView button(String title, String key, Runnable click) {
        TextView t = label(title, 14, text, true); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(10), dp(16), dp(10)); focusable(t, key, surface, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44)); p.setMargins(dp(4), dp(4), dp(4), dp(4));
        t.setLayoutParams(p); return t;
    }
    private View icon(String name,String label,String key,Runnable click) {
        IconView v=new IconView(this,name,label,text);focusable(v,key,surface,click);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(44),dp(44));p.setMargins(dp(4),0,dp(4),0);
        v.setLayoutParams(p);return v;
    }
    private void rememberLibrary() {
        if(page.equals("library")) {
            libraryScroll=activeScroll==null?0:activeScroll.getScrollY();
            if(lastFocus.startsWith("game_"))libraryFocus=lastFocus;
        }
    }
    private void cancelPending(){if(!pendingPlay.isEmpty()&&selected!=null&&pendingPlay.equals(selected.id)){org.drinkless.tdlib.TdApi.File f=drive.file(selected);if(f!=null&&f.local.isDownloadingActive)drive.cancelDownload(selected);}pendingPlay="";}
    private void route(String target) { cancelPending();rememberLibrary();page = target; lastFocus = ""; render(); }
    private void backToLibrary() {cancelPending();page="library";lastFocus=libraryFocus;render();}
    private void render() {
        boolean dark = library.dark();
        String th = themeName(); pixel = th.equals("GBA clásico");
        if (th.equals("GBA clásico")) { background = 0xFFF1EFE9; surface = Color.WHITE; text = 0xFF1A1A2E; muted = 0xFF5A5A70; accent = 0xFF7A4DFF; strokeColor = 0xFF1A1A2E; }
        else if (th.equals("Mosaico")) { background = 0xFF1C2447; surface = 0xFF2A3560; text = Color.WHITE; muted = 0xFFC8D1F5; accent = 0xFFFFC857; strokeColor = 0x33FFFFFF; }
        else {
            background = dark ? 0xFF07101F : 0xFFF3F6FC; surface = dark ? 0xFF132036 : Color.WHITE;
            text = dark ? 0xFFF4F7FC : 0xFF142238; muted = dark ? 0xFFABBCD1 : 0xFF52657A;
            accent = dark ? 0xFF2DE2C8 : 0xFF007E89; strokeColor = dark ? 0xFF293751 : 0xFFD6DEE7;
            if (th.equals("Consola completa") && !dark) background = 0xFFF4F1EC;
        }
        try { fontRegular = getResources().getFont(pixel ? R.font.silkscreen_bold : R.font.poppins_regular); fontBold = getResources().getFont(pixel ? R.font.silkscreen_bold : R.font.poppins_medium); } catch (Exception e) { fontRegular = fontBold = null; }
        root = vertical(); root.setPadding(dp(28), dp(14), dp(28), dp(16));
        if (th.equals("Moderno") && dark) root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0C2442, 0xFF07101F, 0xFF0A2A33}));
        else root.setBackgroundColor(background);
        setContentView(root);
        content=null;activeScroll=null;
        LinearLayout header = horizontal();
        if(!page.equals("home")) header.addView(icon("back",T.t("Volver"),"nav_back",this::onBackPressed));
        TextView title=label(page.equals("welcome")?"TirsoDrive":page.equals("library")?(favorites?T.t("Favoritos"):console==null?T.t("Biblioteca"):console.title):"",18,text,true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(!page.equals("welcome")){
            android.widget.TextClock clock=new android.widget.TextClock(this);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("h:mm a");
            clock.setTextColor(muted);clock.setTextSize(pixel?16:17);if(fontBold!=null)clock.setTypeface(fontBold);clock.setPadding(dp(8),0,dp(16),0);header.addView(clock);
        }
        if(page.equals("library")) {
            header.addView(icon("search",T.t("Buscar juegos"),"search",this::search));
            header.addView(icon("refresh",T.t("Refrescar catálogo"),"refresh",this::refreshCatalog));
            header.addView(icon("sort",T.t("Ordenar juegos: ")+(library.descending()?"Z a A":"A a Z"),"sort",this::sortGames));
        }
        if(!page.equals("welcome"))header.addView(icon("star",T.t("Favoritos"),"nav_favorites", () -> {
            console = null; favorites = true; query = ""; libraryFocus="";libraryScroll=0;route("library");
        }));
        if(!page.equals("welcome"))header.addView(icon("settings",T.t("Ajustes"),"nav_settings", () -> route("settings")));
        else header.addView(button(library.dark()?T.t("Modo claro"):T.t("Modo oscuro"),"welcome_color",()->{library.dark(!library.dark());render();}));
        root.addView(header);
        body = vertical(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if(page.equals("welcome"))welcome();
        else if (page.equals("settings")) settings();
        else if (page.equals("detail") && selected != null) detail();
        else if (page.equals("library")) libraryScreen();
        else home();
        if((page.equals("home")||page.equals("library"))&&hasGamepad()){
            TextView hints=label((page.equals("library")?"A "+T.t("Abrir")+"    B "+T.t("Volver")+"    Y "+T.t("Buscar")+"    X "+T.t("Favoritos"):"A "+T.t("Abrir")+"    X "+T.t("Favoritos"))+"    Start "+T.t("Ajustes"),12,muted,true);
            hints.setPadding(dp(4),dp(6),0,0);root.addView(hints);
        }
        root.post(() -> {
            View target = lastFocus.isEmpty() ? null : root.findViewWithTag(lastFocus);
            if (target == null) target = firstContentFocusable(content == null ? body : content);
            if (target != null) target.requestFocus();
            if(page.equals("library")&&activeScroll!=null)activeScroll.scrollTo(0,libraryScroll);
        });
    }
    private View firstContentFocusable(View v) {
        if (v.isFocusable()) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = firstContentFocusable(((ViewGroup) v).getChildAt(i)); if (f != null) return f;
        }
        return null;
    }
    private void connectScreen(){page="welcome";lastFocus="";drive.enterConnection();render();}
    private void welcome(){
        heading(drive.stage==TirsoDrive.Stage.READY?T.t("Tu biblioteca, conectada"):T.t("Tu biblioteca retro, conectada"),drive.status);
        if(!drive.error.isEmpty())body.addView(label(drive.error,13,library.dark()?0xFFFFADB5:0xFFB0263D,false));
        if(drive.stage==TirsoDrive.Stage.READY){
            content=scrollContent();
            for(Console c:Console.values()){
                TirsoDrive.Source source=drive.sources.get(c);
                content.addView(button(c.title+"  ·  "+(source.chatId==0?source.issue:source.title),"source_"+c.name(),()->chooseSource(c)));
            }
            if(drive.discovering||drive.syncing)content.addView(button(T.t("Cancelar actualización"),"cancel_sync",drive::cancelSync));
            else{
                content.addView(button(T.t("Buscar grupos"),"discover_sources",drive::discover));
                content.addView(button(T.t("Actualizar catálogo"),"sync_sources",drive::sync));
                content.addView(button(T.t("Ir a mi biblioteca"),"open_connected_library",()->{drive.finishOnboarding();library=new Library(this);route("home");}));
            }
            content.addView(button(T.t("Cerrar sesión"),"logout",()->new AlertDialog.Builder(this).setTitle(T.t("Cerrar sesión de Telegram"))
                .setMessage(T.t("Se quitará la biblioteca sincronizada de esta cuenta en la TV. Podrás volver a conectar."))
                .setPositiveButton(T.t("Cerrar sesión"),(d,w)->drive.logout()).setNegativeButton(T.t("Cancelar"),null).show()));return;
        }
        content=scrollContent();
        LinearLayout columns=horizontal();columns.setGravity(Gravity.TOP);
        LinearLayout instructions=vertical();instructions.setPadding(0,dp(10),dp(20),0);
        instructions.addView(label(T.t("1  Conectar    2  Encontrar consolas    3  Biblioteca"),12,accent,true));
        instructions.addView(label(T.t("Abre Telegram en tu teléfono y entra en Ajustes → Dispositivos → Vincular dispositivo."),17,text,false));
        instructions.addView(label(T.t("Después buscaremos tus grupos TD - ROMs, incluidos los archivados, y asignaremos cada uno a su consola."),14,muted,false));
        if(drive.stage==TirsoDrive.Stage.CONFIG){
            instructions.addView(button(T.t("Configurar TirsoDrive"),"configure_credentials",this::configureCredentials));
            instructions.addView(label(T.t("Configuración inicial de la aplicación. Tu sesión de Telegram se autoriza después con el QR."),12,muted,false));
        }else if(drive.stage==TirsoDrive.Stage.PASSWORD)instructions.addView(button(T.t("Introducir contraseña"),"auth_password",()->authInput(true)));
        else if(drive.stage==TirsoDrive.Stage.CODE)instructions.addView(button(T.t("Introducir código"),"auth_code",()->authInput(false)));
        else instructions.addView(button(T.t("Reintentar conexión"),"retry_connection",drive::retry));
        if(!drive.error.isEmpty()&&drive.stage!=TirsoDrive.Stage.CONFIG)instructions.addView(button(T.t("Revisar configuración"),"repair_credentials",this::configureCredentials));
        instructions.addView(button(T.t("Continuar sin conexión"),"continue_offline",()->{drive.skip();route("home");}));
        columns.addView(instructions,new LinearLayout.LayoutParams(0,-2,1));
        if(drive.stage==TirsoDrive.Stage.QR&&!drive.qrLink.isEmpty()){
            try{
                if(!drive.qrLink.equals(renderedQr)){qrBitmap=QrCode.encode(drive.qrLink,480);renderedQr=drive.qrLink;}
                ImageView qr=new ImageView(this);qr.setImageBitmap(qrBitmap);qr.setContentDescription(T.t("Código QR para vincular Telegram"));
                qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setBackgroundColor(Color.WHITE);
                columns.addView(qr,new LinearLayout.LayoutParams(dp(230),dp(230)));
            }catch(Exception e){columns.addView(label(T.t("No se pudo dibujar el QR. Reintenta la conexión."),14,muted,false));}
        }
        content.addView(columns);
    }
    private void configureCredentials(){
        EditText hash=new EditText(this);hash.setSingleLine(true);hash.setHint(T.t("API hash de TirsoDrive"));
        hash.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(T.t("Configurar TirsoDrive"))
            .setMessage("API ID: "+BuildConfig.TELEGRAM_API_ID+T.t(". Introduce el API hash una sola vez; se guarda cifrado en esta TV."))
            .setView(hash).setPositiveButton(T.t("Guardar"),(d,w)->{String value=hash.getText().toString().trim();hash.setText("");drive.configureHash(value);})
            .setNegativeButton(T.t("Cancelar"),(d,w)->hash.setText("")).show();
    }
    private void authInput(boolean password){
        EditText input=new EditText(this);input.setSingleLine(true);
        input.setInputType(password?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle(password?T.t("Verificación en dos pasos"):T.t("Código de Telegram")).setView(input)
            .setPositiveButton(T.t("Continuar"),(d,w)->{String value=input.getText().toString();input.setText("");if(password)drive.password(value);else drive.code(value);})
            .setNegativeButton(T.t("Cancelar"),(d,w)->input.setText("")).show();
    }
    private void chooseSource(Console console){
        if(drive.syncing||drive.discovering)return;
        java.util.List<org.drinkless.tdlib.TdApi.Chat> choices=drive.choices(console);
        String[] labels=new String[choices.size()+1];labels[0]=T.t("Sin grupo asignado");
        for(int i=0;i<choices.size();i++)labels[i+1]=choices.get(i).title+" · ID "+choices.get(i).id;
        new AlertDialog.Builder(this).setTitle(console.title).setItems(labels,(d,w)->{
            if(w==0)drive.unbind(console);else drive.bind(console,choices.get(w-1).id);
        }).setNegativeButton(T.t("Cancelar"),null).show();
    }
    private void heading(String title, String subtitle) {
        body.addView(label(title, 28, text, true)); body.addView(label(subtitle, 12, muted, false));
    }
    private LinearLayout scrollContent() {
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true);activeScroll=scroll;
        content = vertical(); content.setPadding(0, dp(10), 0, dp(8)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return content;
    }
    private ImageView logo(Console c) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(c.title);
        int id = getResources().getIdentifier("logo_" + c.logo, "drawable", getPackageName());
        if (id != 0) image.setImageResource(id);
        image.setPadding(dp(14), dp(9), dp(14), dp(9)); return image;
    }
    private String gameCount(int count) { return count+ (count==1?T.t(" juego"):T.t(" juegos")); }
    private String themeName(){String t=library.theme();return t.equals("GBA clásico")||t.equals("Mosaico")||t.equals("Consola completa")?t:"Moderno";}
    private boolean hasGamepad(){
        for(int id:InputDevice.getDeviceIds()){InputDevice d=InputDevice.getDevice(id);if(d!=null&&((d.getSources()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(d.getSources()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK))return true;}
        return false;
    }
    private int brand(Console c){
        switch(c){
            case N64:return 0xFF2F6DF6;case GBA:return 0xFF7A4DFF;case GBC:return 0xFFFF9A2E;case GB:return 0xFF4DB36B;case NDS:return 0xFFFF4D73;
            case SNES:return 0xFFA06BFF;case NES:return 0xFFFF4040;case MD:return 0xFF2AA8FF;default:return 0xFF14C9AA;
        }
    }
    private int shade(int color,float f){return 0xFF000000|((int)(((color>>16)&255)*f)<<16)|((int)(((color>>8)&255)*f)<<8)|(int)((color&255)*f);}
    private int blend(int a,int b,float t){
        int r=(int)(((a>>16)&255)*t+((b>>16)&255)*(1-t)),g=(int)(((a>>8)&255)*t+((b>>8)&255)*(1-t)),bl=(int)((a&255)*t+(b&255)*(1-t));
        return 0xFF000000|(r<<16)|(g<<8)|bl;
    }
    private int onAccent(){String th=themeName();boolean dark=library.dark();return (th.equals("GBA clásico")||(!th.equals("Mosaico")&&!dark))?Color.WHITE:0xFF04121F;}
    private void focusableWith(View v,String key,java.util.function.Function<Boolean,android.graphics.drawable.Drawable> bg,Runnable click){
        v.setTag(key);v.setFocusable(true);v.setClickable(true);v.setBackground(bg.apply(false));v.setOnClickListener(x->click.run());
        v.setOnFocusChangeListener((x,has)->{x.setBackground(bg.apply(has));if(has)lastFocus=key;x.setElevation(dp(has?12:0));x.animate().scaleX(has?1.05f:1f).scaleY(has?1.05f:1f).setDuration(140).start();});
    }
    private android.graphics.drawable.Drawable tileBg(Console c,boolean focused){
        String th=themeName();boolean dark=library.dark();int brand=brand(c);GradientDrawable g;
        if(th.equals("GBA clásico")){g=new GradientDrawable();g.setColor(focused?brand:Color.WHITE);g.setCornerRadius(dp(22));g.setStroke(dp(focused?5:3),0xFF1A1A2E);}
        else if(th.equals("Mosaico")){g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{brand,shade(brand,0.78f)});g.setCornerRadius(dp(26));if(focused)g.setStroke(dp(5),Color.WHITE);}
        else{int base=dark?0xFF0F1B2E:Color.WHITE;g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{blend(brand,base,focused?0.45f:0.2f),base});g.setCornerRadius(dp(28));g.setStroke(dp(focused?3:1),focused?accent:strokeColor);}
        return g;
    }
    private View consoleTile(Console c){
        String th=themeName();boolean dark=library.dark();
        LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(22),dp(14),dp(22),dp(12));
        ImageView logo=logo(c);logo.setPadding(dp(8),0,dp(8),0);
        if(th.equals("Mosaico")||(th.equals("Moderno")&&dark))logo.setColorFilter(Color.WHITE,android.graphics.PorterDuff.Mode.SRC_IN);
        tile.addView(logo,new LinearLayout.LayoutParams(-1,0,1));
        TextView count=label(gameCount(library.count(c)),12,th.equals("Mosaico")?0xE6FFFFFF:muted,true);count.setGravity(Gravity.CENTER);count.setPadding(dp(16),dp(4),dp(16),dp(4));
        if(th.equals("Moderno")){GradientDrawable pill=new GradientDrawable();pill.setColor(dark?0x59000000:0x12000000);pill.setCornerRadius(dp(20));count.setBackground(pill);}
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.gravity=Gravity.CENTER_HORIZONTAL;cp.topMargin=dp(6);tile.addView(count,cp);
        tile.setContentDescription(c.title+", "+library.count(c)+T.t(" juegos"));
        focusableWith(tile,"console_"+c.name(),focused->tileBg(c,focused),()->openConsole(c));
        if(th.equals("GBA clásico")){
            View.OnFocusChangeListener base=tile.getOnFocusChangeListener();
            tile.setOnFocusChangeListener((v,has)->{base.onFocusChange(v,has);if(has)logo.setColorFilter(Color.WHITE,android.graphics.PorterDuff.Mode.SRC_IN);else logo.clearColorFilter();count.setTextColor(has?Color.WHITE:muted);});
        }
        return tile;
    }
    private TextView primary(String title,String key,Runnable click){
        TextView t=label(title,15,onAccent(),true);t.setGravity(Gravity.CENTER);t.setPadding(dp(26),dp(10),dp(26),dp(10));
        focusableWith(t,key,focused->{GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{accent,focused?accent:shade(accent,0.85f)});g.setCornerRadius(dp(pixel?14:22));if(focused)g.setStroke(dp(3),text);return g;},click);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(50));p.setMargins(dp(4),dp(6),dp(4),dp(6));t.setLayoutParams(p);return t;
    }
    private void home() {
        java.util.ArrayList<Console> visible=new java.util.ArrayList<>();
        for(Console c:Console.values())if(library.visible(c))visible.add(c);
        if(visible.isEmpty()) {
            content=scrollContent();content.addView(label(T.t("No hay consolas visibles"),20,text,true));
            content.addView(button(T.t("Elegir consolas"),"empty_settings",()->route("settings")));return;
        }
        if(themeName().equals("Consola completa")){heroHome(visible);return;}
        content=vertical();content.setPadding(0,dp(10),0,0);body.addView(content,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout row = null; int index = 0;
        for (Console c : visible) {
            if (index % 3 == 0) { row = horizontal();content.addView(row,new LinearLayout.LayoutParams(-1,0,1)); }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-1,1); p.setMargins(dp(8),dp(8),dp(8),dp(8));
            row.addView(consoleTile(c), p); index++;
        }
        while(index++%3!=0)row.addView(new View(this),new LinearLayout.LayoutParams(0,-1,1));
    }
    private void openConsole(Console c) {
        console=c;library.homeConsole(c);favorites=false;query="";libraryFocus="";libraryScroll=0;visibleGames=60;route("library");
        drive.refresh(c);
    }
    private Console heroConsole(java.util.List<Console> consoles) {
        for(Console c:consoles)if(c.name().equals(library.homeConsole()))return c;return consoles.get(0);
    }
    private void moveHero(int delta) {
        java.util.ArrayList<Console> consoles=new java.util.ArrayList<>();for(Console c:Console.values())if(library.visible(c))consoles.add(c);
        if(consoles.isEmpty())return;
        int index=consoles.indexOf(heroConsole(consoles));
        library.homeConsole(consoles.get((index+delta+consoles.size())%consoles.size()));lastFocus="hero_console";render();
    }
    private android.graphics.drawable.Drawable heroBg(Console c,boolean focused){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{brand(c),shade(brand(c),0.7f)});g.setCornerRadius(dp(40));
        if(focused)g.setStroke(dp(4),text);return g;
    }
    private android.graphics.drawable.Drawable chipBg(Console c,boolean focused){
        GradientDrawable g=new GradientDrawable();g.setColor(brand(c));g.setCornerRadius(dp(16));if(focused)g.setStroke(dp(3),text);return g;
    }
    private void heroHome(java.util.List<Console> consoles) {
        Console current=heroConsole(consoles);
        if(lastFocus.isEmpty())lastFocus="hero_console";
        content=vertical();body.addView(content,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout row=horizontal();content.addView(row,new LinearLayout.LayoutParams(-1,0,1));
        row.addView(icon("back",T.t("Consola anterior"),"hero_prev",()->moveHero(-1)));
        LinearLayout hero=vertical();hero.setGravity(Gravity.CENTER);hero.setPadding(dp(40),dp(34),dp(40),dp(26));
        ImageView heroLogo=logo(current);heroLogo.setColorFilter(Color.WHITE,android.graphics.PorterDuff.Mode.SRC_IN);
        hero.addView(heroLogo,new LinearLayout.LayoutParams(-1,0,1));
        TextView count=label(current.title+"  ·  "+gameCount(library.count(current)),16,0xE6FFFFFF,true);count.setGravity(Gravity.CENTER);hero.addView(count);
        hero.setContentDescription(current.title+", "+library.count(current)+T.t(" juegos. Abrir biblioteca"));
        focusableWith(hero,"hero_console",f->heroBg(current,f),()->openConsole(current));
        hero.setOnKeyListener((v,key,e)->{
            if(e.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){moveHero(-1);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT){moveHero(1);return true;}return false;
        });
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(14),dp(10),dp(14),dp(10));row.addView(hero,p);
        row.addView(icon("next",T.t("Consola siguiente"),"hero_next",()->moveHero(1)));
        LinearLayout strip=horizontal();strip.setGravity(Gravity.CENTER);strip.setPadding(0,dp(4),0,dp(4));
        for(Console c:consoles){
            ImageView mini=logo(c);mini.setPadding(dp(10),dp(6),dp(10),dp(6));mini.setColorFilter(Color.WHITE,android.graphics.PorterDuff.Mode.SRC_IN);mini.setAlpha(c==current?1f:0.6f);
            focusableWith(mini,"chip_"+c.name(),f->chipBg(c,f),()->{library.homeConsole(c);lastFocus="hero_console";render();});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(88),dp(46));cp.setMargins(dp(5),0,dp(5),0);strip.addView(mini,cp);
        }
        content.addView(strip,new LinearLayout.LayoutParams(-1,-2));
    }
    private String catalogMessage(){
        if(drive.stage!=TirsoDrive.Stage.READY)return T.t("Conecta TirsoDrive para actualizar tu colección");
        if(drive.syncing||drive.discovering)return T.t("Actualizando tu colección…");
        if(!drive.error.isEmpty())return drive.error;
        if(console!=null&&drive.sources.get(console).chatId==0)return T.t("Vincula ")+TelegramCatalog.expectedTitle(console)+T.t(" en TirsoDrive");
        return T.t("Actualizada automáticamente");
    }
    private void libraryScreen() {
        List<Game> games = library.filtered(console, favorites, query);
        LinearLayout summary=horizontal();
        summary.addView(label(games.size()+T.t(" juegos  ·  ")+(library.descending()?"Z – A":"A – Z")+(query.isEmpty()?"":"  ·  "+query),12,muted,false),new LinearLayout.LayoutParams(0,-2,1));
        if(!query.isEmpty())summary.addView(button(T.t("Limpiar"),"clear",()->{query="";libraryScroll=0;lastFocus="";render();}));
        body.addView(summary);
        catalogStatus=label(catalogMessage(),11,muted,false);body.addView(catalogStatus);
        int columns=getResources().getConfiguration().screenWidthDp>=800?6:5;
        LinearLayout grid = scrollContent(); LinearLayout row = null; int index = 0;
        for (Game game : games.subList(0,Math.min(visibleGames,games.size()))) {
            if (index % columns == 0) { row = horizontal(); grid.addView(row); }
            LinearLayout tile = vertical(); tile.setPadding(dp(7),dp(7),dp(7),dp(6));
            FrameLayout art = new FrameLayout(this);
            TextView placeholder = label(game.title,11,muted,true); placeholder.setGravity(Gravity.CENTER);
            art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription(game.title);
            image.setBackgroundColor(surface); art.addView(image, new FrameLayout.LayoutParams(-1, -1));
            // Transparent fallback keeps the title placeholder visible if artwork is unavailable.
            image.setBackgroundColor(Color.TRANSPARENT);
            tile.addView(art, new LinearLayout.LayoutParams(-1,dp(104))); artwork.load(game, image);
            TextView name = label((library.favorite(game) ? "★ " : "") + game.title,11,text,true);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(name);
            focusable(tile, "game_" + game.id, surface, () -> { selected = game;drive.inspectFile(game);route("detail"); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(152),1); p.setMargins(dp(5),dp(5),dp(5),dp(5));
            row.addView(tile, p); index++;
        }
        if (index == 0) grid.addView(label(favorites ? T.t("Guarda juegos desde su ficha para verlos aquí.") : T.t("No hay juegos que coincidan."), 18, muted, false));
        else while (index++ % columns != 0) row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(152),1));
        if(games.size()>visibleGames)grid.addView(button(T.t("Mostrar más juegos"),"load_more",()->{
            int next=visibleGames;visibleGames+=60;lastFocus="game_"+games.get(next).id;render();
        }));
    }
    private void refreshCatalog() {
        if(drive.stage==TirsoDrive.Stage.READY){
            boolean assigned=false;for(TirsoDrive.Source s:drive.sources.values())if(s.chatId!=0)assigned=true;
            if(!assigned){connectScreen();return;}
            if(console!=null&&!favorites)drive.refresh(console);else drive.sync();Toast.makeText(this,drive.syncing?T.t("Actualizando colección…"):drive.status,Toast.LENGTH_SHORT).show();
        }
        else connectScreen();
    }
    private void sortGames() {
        new AlertDialog.Builder(this).setTitle(T.t("Orden alfabético"))
            .setSingleChoiceItems(new String[]{T.t("Título: A → Z"),T.t("Título: Z → A")},library.descending()?1:0,(dialog,which)->{
                library.descending(which==1);libraryScroll=0;lastFocus="sort";dialog.dismiss();render();
            }).setNegativeButton(T.t("Cancelar"),null).show();
    }
    private void detail() {
        heading(selected.console.title, library.demo ? T.t("Ficha de muestra · Sin archivo de juego") : T.t("Información del catálogo"));
        content = scrollContent(); LinearLayout columns = horizontal(); columns.setGravity(Gravity.TOP);
        ImageView cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.FIT_CENTER); cover.setContentDescription(selected.title);
        columns.addView(cover, new LinearLayout.LayoutParams(dp(215), dp(245))); artwork.load(selected, cover);
        LinearLayout info = vertical(); info.setPadding(dp(24), 0, 0, 0);
        info.addView(label(selected.title, 26, text, true));
        info.addView(label(selected.description, 15, muted, false));
        if(selected.telegramAccount!=0){
            downloadLabel=label("",13,muted,false);info.addView(downloadLabel);
            playProgress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);playProgress.setMax(100);playProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));info.addView(playProgress,new LinearLayout.LayoutParams(-1,dp(5)));
            downloadButton=primary(T.t("Jugar"),"download_game",this::playSelected);info.addView(downloadButton);updateDownload();
        }
        info.addView(button(library.favorite(selected) ? T.t("★ Quitar de favoritos") : T.t("☆ Guardar en favoritos"), "favorite_toggle", () -> { library.toggleFavorite(selected); render(); }));
        info.addView(button(T.t("Volver a la biblioteca"), "back_library", this::backToLibrary));
        columns.addView(info, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(columns);
    }
    private void updateDownload(){
        if(selected==null||selected.telegramAccount==0||downloadLabel==null||downloadButton==null)return;
        org.drinkless.tdlib.TdApi.File file=drive.file(selected);
        String size=android.text.format.Formatter.formatFileSize(this,selected.fileSize);
        if(file!=null&&file.local.isDownloadingCompleted){
            downloadLabel.setText(T.t("Listo para jugar · ")+size);downloadButton.setText(T.t("Jugar"));downloadButton.setEnabled(true);if(playProgress!=null)playProgress.setVisibility(View.GONE);
            if(pendingPlay.equals(selected.id)&&page.equals("detail")){pendingPlay="";playDownloaded(file.local.path);}
        }
        else if(cachedRom(selected)!=null){downloadLabel.setText(T.t("Listo para jugar · ")+size);downloadButton.setText(T.t("Jugar"));downloadButton.setEnabled(true);if(playProgress!=null)playProgress.setVisibility(View.GONE);}
        else if(file!=null&&file.local.isDownloadingActive){
            int percent=(int)Math.min(100,file.local.downloadedSize*100/Math.max(1,Math.max(file.size,file.expectedSize)));
            downloadLabel.setText(T.t("Preparando tu juego · ")+percent+"%");downloadButton.setText(T.t("Jugar"));downloadButton.setEnabled(false);if(playProgress!=null){playProgress.setVisibility(View.VISIBLE);playProgress.setIndeterminate(false);playProgress.setProgress(percent);}
        }else if(pendingPlay.equals(selected.id)&&drive.error.isEmpty()){downloadLabel.setText(T.t("Preparando tu juego…"));downloadButton.setEnabled(false);if(playProgress!=null){playProgress.setVisibility(View.VISIBLE);playProgress.setIndeterminate(true);}}else{pendingPlay="";downloadLabel.setText(size+" · "+(drive.owns(selected)?T.t("Desde tu colección TirsoDrive"):T.t("Conecta TirsoDrive para jugar")));downloadButton.setText(T.t("Jugar"));downloadButton.setEnabled(true);if(playProgress!=null)playProgress.setVisibility(View.GONE);}
        if(!drive.error.isEmpty())downloadLabel.setText(drive.error);
    }
    private void playSelected(){
        if(selected==null)return;
        if(TemporaryRoms.busy()){String id=selected.id;downloadLabel.setText(T.t("Preparando la sesión…"));root.postDelayed(()->{if(page.equals("detail")&&selected.id.equals(id))playSelected();},120);return;}
        org.drinkless.tdlib.TdApi.File file=drive.file(selected);
        if(file!=null&&file.local.isDownloadingCompleted){playDownloaded(file.local.path);return;}
        String cached=cachedRom(selected);if(cached!=null){playDownloaded(cached);return;}
        if(!drive.owns(selected)){connectScreen();return;}
        pendingPlay=selected.id;downloadLabel.setText(T.t("Preparando tu juego…"));drive.download(selected);updateDownload();
    }
    private String cachedRom(Game game){
        String path=getSharedPreferences("downloaded_roms",MODE_PRIVATE).getString(game.id,null);
        if(path==null)return null;
        try{java.io.File f=new java.io.File(path).getCanonicalFile();return f.isFile()&&f.getPath().startsWith(getFilesDir().getCanonicalPath()+java.io.File.separator)?f.getPath():null;}
        catch(java.io.IOException e){return null;}
    }
    private void playDownloaded(String path){
        pendingPlay="";
        Intent intent=new Intent(this,GameActivity.class).putExtra("rom",path).putExtra("filename",selected.fileName).putExtra("title",selected.title).putExtra("description",selected.description).putExtra("console",selected.console.name()).putExtra("fileId",selected.fileId).putExtra("account",selected.telegramAccount);
        startActivity(intent);
    }
    private void search() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(query); input.setHint(T.t("Nombre del juego"));
        new AlertDialog.Builder(this).setTitle(T.t("Buscar juego")).setView(input)
                .setPositiveButton(T.t("Buscar"), (d,w) -> { query = input.getText().toString().trim();libraryScroll=0;lastFocus="search";render(); })
                .setNegativeButton(T.t("Cancelar"), null).show();
    }
    private void settings() {
        heading(T.t("Ajustes"), T.t("A tu manera")); content = scrollContent();content.setClipChildren(false);
        settingsSection(T.t("Apariencia"));LinearLayout appearance=horizontal();content.addView(appearance);
        settingTile(appearance,T.t("Color"),library.dark()?T.t("Oscuro"):T.t("Claro"),"color_mode",()->{library.dark(!library.dark());render();});
        settingTile(appearance,T.t("Diseño"),T.t(themeName()),"visual_theme",()->{
            String[] themes = {"Moderno", "GBA clásico", "Mosaico", "Consola completa"};
            String[] shown = new String[themes.length];for(int i=0;i<themes.length;i++)shown[i]=T.t(themes[i]);
            int current=java.util.Arrays.asList(themes).indexOf(themeName());
            new AlertDialog.Builder(this).setTitle(T.t("Tema visual")).setSingleChoiceItems(shown,current,(d,w)->{library.theme(themes[w]);d.dismiss();render();}).show();
        });
        settingTile(appearance,"Idioma / Language",T.english()?"English":"Español","language",()->{
            String[] codes={"es","en"};String[] names={"Español","English"};
            new AlertDialog.Builder(this).setTitle("Idioma / Language").setSingleChoiceItems(names,T.english()?1:0,(d,w)->{T.set(this,codes[w]);d.dismiss();recreate();}).show();
        });
        LinearLayout section=horizontal();TextView sectionTitle=label(T.t("Consolas visibles"),19,text,true);section.addView(sectionTitle,new LinearLayout.LayoutParams(0,-2,1));
        section.addView(button(T.t("Mostrar todas"),"show_all",()->{for(Console c:Console.values())library.visible(c,true);render();}));content.addView(section);
        LinearLayout row=null;int n=0;
        for(Console c:Console.values()){
            if(n++%3==0){row=horizontal();row.setClipChildren(false);content.addView(row);}
            LinearLayout card=vertical();card.setPadding(dp(15),dp(12),dp(15),dp(12));card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            LinearLayout top=horizontal();ImageView image=logo(c);image.setPadding(dp(6),dp(3),dp(6),dp(3));image.setBackground(panel(0xFFF0F3FA,false));top.addView(image,new LinearLayout.LayoutParams(dp(75),dp(34)));
            top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            android.widget.Switch toggle=new android.widget.Switch(this);toggle.setChecked(library.visible(c));toggle.setClickable(false);toggle.setFocusable(false);toggle.setShowText(false);
            toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));toggle.setTrackTintList(android.content.res.ColorStateList.valueOf(library.visible(c)?accent:0xFF4A5568));top.addView(toggle);
            card.addView(top);TextView name=label(c.title,14,text,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
            card.addView(label(gameCount(library.count(c)),11,muted,false));card.setContentDescription(c.title+", "+(library.visible(c)?"visible":"oculta")+", "+gameCount(library.count(c)));
            focusable(card,"visible_"+c.name(),surface,()->{library.visible(c,!library.visible(c));lastFocus="visible_"+c.name();render();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(112),1);p.setMargins(dp(5),dp(5),dp(5),dp(5));row.addView(card,p);
        }
        settingsSection(T.t("Tu biblioteca"));LinearLayout connection=horizontal();content.addView(connection);
        settingTile(connection,"TirsoDrive",drive.stage==TirsoDrive.Stage.READY?T.t("Conectado · gestionar grupos"):T.t("Vincular colección"),"telegram",this::connectScreen);
        settingTile(connection,T.t("Mandos"),T.t("Perfiles independientes"),"controllers",this::controllers);
        LinearLayout system=horizontal();content.addView(system);
        settingTile(system,T.t("Archivos del sistema"),T.t("BIOS y archivos compartidos"),"system_files",this::importSystemFile);
        settingTile(system,"RetroStream",BuildConfig.VERSION_NAME,"about",()->message("RetroStream "+BuildConfig.VERSION_NAME,T.t("Nueve consolas integradas. Las ROMs se preparan al pulsar Jugar y se eliminan al salir. Tus partidas y estados permanecen en la TV.\n\nMAME: ROMs compatibles con MAME 2003-Plus; ciertos juegos requieren BIOS o archivos parent. Puedes añadirlos en Archivos del sistema.\n\nCódigo y licencias de los núcleos incluidos en la release y en los avisos de esta aplicación.")));
        LinearLayout updates=horizontal();content.addView(updates);
        settingTile(updates,T.t("Actualizaciones"),T.t("Buscar e instalar la última versión"),"updates",this::checkUpdates);
        settingTile(updates,T.t("Informe de problemas"),T.t("Qué registró Android si un juego falla"),"crash_report",this::crashReport);
        content.addView(button(T.t("Licencias y créditos"),"licenses",this::licenses));
    }
    private void crashReport(){
        Toast.makeText(this,T.t("Preparando informe…"),Toast.LENGTH_SHORT).show();
        new Thread(()->{
            String report=CrashReport.build(this);
            runOnUiThread(()->{
                String preview=report.length()>1800?report.substring(0,1800)+"\n…":report;
                new AlertDialog.Builder(this).setTitle(T.t("Informe de problemas")).setMessage(preview)
                    .setPositiveButton(T.t("Guardar en Descargas"),(d,w)->{String where=CrashReport.save(this,report);Toast.makeText(this,where==null?T.t("No se pudo guardar el informe"):T.t("Guardado en ")+where,Toast.LENGTH_LONG).show();})
                    .setNegativeButton(T.t("Cerrar"),null).show();
            });
        },"crash-report").start();
    }
    private void checkUpdates(){
        Toast.makeText(this,T.t("Buscando actualizaciones…"),Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try{
                Updater.Info info=Updater.check();
                runOnUiThread(()->{
                    if(info.build<=BuildConfig.VERSION_CODE){message(T.t("Actualizaciones"),T.t("Ya tienes la última versión (build ")+BuildConfig.VERSION_CODE+").");return;}
                    new AlertDialog.Builder(this).setTitle(T.t("Nueva versión disponible"))
                        .setMessage("Build "+info.build+" · "+(info.size/(1024*1024))+" MB\n\n"+info.notes+T.t("\n\nTus partidas y la sesión de Telegram se conservan."))
                        .setPositiveButton(T.t("Actualizar ahora"),(d,w)->startUpdate(info)).setNegativeButton(T.t("Más tarde"),null).show();
                });
            }catch(Exception e){runOnUiThread(()->message(T.t("Actualizaciones"),T.t("No se pudo comprobar: ")+e.getMessage()));}
        },"update-check").start();
    }
    private void startUpdate(Updater.Info info){
        if(!getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(this).setTitle(T.t("Permiso necesario")).setMessage(T.t("Android pide permiso una sola vez para que RetroStream pueda instalar su propia actualización. Activa «Permitir de esta fuente» y vuelve a pulsar Actualizar."))
                .setPositiveButton(T.t("Abrir ajustes"),(d,w)->{try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){message(T.t("Permiso necesario"),T.t("Abre Ajustes de Android → Aplicaciones → RetroStream → Instalar apps desconocidas y permítelo."));}})
                .setNegativeButton(T.t("Cancelar"),null).show();
            return;
        }
        android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);
        LinearLayout box=vertical();box.setPadding(dp(24),dp(16),dp(24),dp(8));box.addView(bar);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(T.t("Descargando actualización…")).setView(box).setCancelable(false).show();
        new Thread(()->{
            try{
                java.io.File apk=Updater.download(this,info,(done,total)->{int percent=(int)(done*100/Math.max(1,total));runOnUiThread(()->bar.setProgress(percent));});
                runOnUiThread(()->{dialog.dismiss();Toast.makeText(this,T.t("Instalando…"),Toast.LENGTH_LONG).show();});
                Updater.install(this,apk);
            }catch(Exception e){runOnUiThread(()->{dialog.dismiss();message(T.t("Actualizaciones"),e.getMessage());});}
        },"update-download").start();
    }
    private void settingsSection(String title){TextView view=label(title,19,text,true);view.setPadding(dp(5),dp(18),0,dp(8));content.addView(view);}
    private void settingTile(LinearLayout row,String title,String value,String tag,Runnable action){
        LinearLayout card=vertical();card.setPadding(dp(20),dp(14),dp(20),dp(14));card.addView(label(title,16,text,true));TextView subtitle=label(value,12,muted,false);subtitle.setSingleLine(true);subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(subtitle);
        focusable(card,tag,surface,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(84),1);p.setMargins(dp(5),dp(5),dp(5),dp(5));row.addView(card,p);
    }
    private void licenses(){try{String[] files=getAssets().list("licenses");new AlertDialog.Builder(this).setTitle(T.t("Licencias y créditos")).setItems(files,(d,w)->{
        try(InputStream in=getAssets().open("licenses/"+files[w])){message(files[w],new String(GameStorage.read(in,1024*1024),StandardCharsets.UTF_8));}catch(Exception e){message(T.t("Licencias"),T.t("No se pudo abrir este aviso"));}
    }).show();}catch(Exception e){message(T.t("Licencias"),T.t("Los avisos están disponibles en el código fuente de esta release"));}}
    private void importSystemFile(){
        new AlertDialog.Builder(this).setTitle(T.t("Archivos del sistema")).setMessage(T.t("Añade disksys.rom para Famicom Disk System o archivos ZIP de BIOS/parent para MAME. Conserva sus nombres originales. Usa archivos de los que dispongas legítimamente."))
            .setPositiveButton(T.t("Elegir archivo"),(d,w)->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),31);}catch(android.content.ActivityNotFoundException e){message(T.t("Archivos del sistema"),T.t("Esta TV necesita un selector de documentos compatible para importar archivos."));}}).setNegativeButton(T.t("Cancelar"),null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=31||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();new Thread(()->{try{
            String filename=null;try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);}
            if(filename==null||(!filename.equalsIgnoreCase("disksys.rom")&&!filename.toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9_+.\\-]+\\.zip")))throw new java.io.IOException(T.t("Selecciona disksys.rom o un ZIP con su nombre original"));
            java.io.File folder=new java.io.File(getFilesDir(),"bios");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException(T.t("No se pudo crear la carpeta del sistema"));
            java.io.File temp=java.io.File.createTempFile("system-",".tmp",getCacheDir());try{
                try(InputStream in=getContentResolver().openInputStream(uri);java.io.OutputStream out=new java.io.FileOutputStream(temp)){if(in==null)throw new java.io.IOException(T.t("No se pudo abrir el archivo"));GameStorage.copy(in,out,64L*1024*1024);}
                GameStorage.copyAtomic(temp,new java.io.File(folder,filename.toLowerCase(java.util.Locale.ROOT)));
            }finally{temp.delete();}runOnUiThread(()->Toast.makeText(this,T.t("Archivo del sistema añadido"),Toast.LENGTH_LONG).show());
        }catch(Exception e){runOnUiThread(()->message(T.t("Archivos del sistema"),e.getMessage()));}},"system-import").start();
    }
    private void controllers() {
        java.util.ArrayList<InputDevice> devices=new java.util.ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) devices.add(device);
        }
        if(devices.isEmpty()){message(T.t("Perfiles de controles"),T.t("No se detectaron mandos. Vincula uno por Bluetooth o USB desde los ajustes de Android TV."));return;}
        String[] names=new String[devices.size()];
        for(int i=0;i<names.length;i++)names[i]=ControllerProfiles.model(devices.get(i))+" · "+devices.get(i).getName();
        new AlertDialog.Builder(this).setTitle(T.t("Perfil por control")).setItems(names,(d,w)->controllerProfile(devices.get(w))).show();
    }
    private void controllerProfile(InputDevice device) {
        float[] values={.20f,.40f,.60f};String[] choices={T.t("Zona muerta: baja (20%)"),T.t("Zona muerta: media (40%)"),T.t("Zona muerta: alta (60%)")};
        int choice=0;float current=controllers.deadzone(device);for(int i=0;i<values.length;i++)if(Math.abs(values[i]-current)<.05f)choice=i;
        new AlertDialog.Builder(this).setTitle(ControllerProfiles.model(device))
            .setSingleChoiceItems(choices,choice,(dialog,which)->{
                controllers.deadzone(device,values[which]);Toast.makeText(this,T.t("Perfil guardado para este dispositivo"),Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(T.t("Diagnóstico"),(d,w)->{
                ControllerProfiles.State s=controllers.state(device.getId());
                message(T.t("Entradas independientes"),String.format(java.util.Locale.ROOT,
                    T.t("ID: %04x:%04x\nD-pad teclas: %d, %d\nD-pad HAT: %d, %d\nStick izquierdo: %.2f, %.2f\nStick derecho: %.2f, %.2f\n\nCada origen mantiene su estado separado. Los núcleos usan perfiles independientes por control y por jugador, sin mezclar D-pad y joystick."),
                    device.getVendorId(),device.getProductId(),s.keyX,s.keyY,s.hatX,s.hatY,s.leftX,s.leftY,s.rightX,s.rightY));
            }).setNegativeButton(T.t("Jugador"),(d,w)->new AlertDialog.Builder(this).setTitle(T.t("Asignar mando"))
                .setSingleChoiceItems(new String[]{T.t("Automático"),T.t("Jugador 1"),T.t("Jugador 2"),T.t("Jugador 3"),T.t("Jugador 4")},controllers.player(device)+1,(x,which)->{controllers.player(device,which-1);x.dismiss();}).show()).setPositiveButton(T.t("Listo"),null).show();
    }
    private void message(String title, String body) { new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(T.t("Entendido"), null).show(); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(controllers.key(event,true))return true;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { onBackPressed(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            View focus = getCurrentFocus(); if (focus != null && event.getRepeatCount() == 0) focus.performClick(); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START) { route("settings"); return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && page.equals("library")) { search(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X && !page.equals("welcome")) { console = null; favorites = true; query = ""; libraryFocus = ""; libraryScroll = 0; route("library"); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent event) {
        controllers.key(event,false);
        if(keyCode==KeyEvent.KEYCODE_BUTTON_A||keyCode==KeyEvent.KEYCODE_BUTTON_B||keyCode==KeyEvent.KEYCODE_BUTTON_START||keyCode==KeyEvent.KEYCODE_BUTTON_X||(keyCode==KeyEvent.KEYCODE_BUTTON_Y&&page.equals("library")))return true;
        return super.onKeyUp(keyCode,event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if(event.getAction()==MotionEvent.ACTION_MOVE && (event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK) {
            int direction=controllers.motion(event);
            if(direction!=0) {
                View focused=getCurrentFocus();
                if(focused!=null&&"hero_console".equals(focused.getTag())&&(direction==View.FOCUS_LEFT||direction==View.FOCUS_RIGHT))moveHero(direction==View.FOCUS_RIGHT?1:-1);
                else if(focused!=null){View next=focused.focusSearch(direction);if(next!=null)next.requestFocus();}
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
    @Override public void onBackPressed() {
        if(page.equals("welcome")){drive.skip();route("home");return;}
        if (page.equals("detail")) backToLibrary();
        else if (!page.equals("home")) route("home");
        else new AlertDialog.Builder(this).setTitle(T.t("Salir de RetroStream"))
                    .setPositiveButton(T.t("Salir"), (d,w) -> finish()).setNegativeButton(T.t("Cancelar"), null).show();
    }
    @Override protected void onDestroy() {drive.detach(driveListener);inputManager.unregisterInputDeviceListener(inputListener);artwork.close(); super.onDestroy(); }
    @Override protected void onPause(){cancelPending();super.onPause();}
}
