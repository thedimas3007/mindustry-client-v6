package mindustry.desktop;

import arc.*;
import arc.Files.FileType;
import arc.backend.sdl.*;
import arc.backend.sdl.jni.SDL;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.Rand;
import arc.struct.Seq;
import arc.util.*;
import arc.util.serialization.Base64Coder;
import club.minnced.discord.rpc.*;
import com.codedisaster.steamworks.SteamAPI;
import mindustry.*;
import mindustry.client.Main;
import mindustry.core.Version;
import mindustry.desktop.steam.*;
import mindustry.game.EventType.*;
import mindustry.gen.Groups;
import mindustry.net.*;
import mindustry.net.Net.NetProvider;
import mindustry.type.Publishable;

import java.io.*;

import static mindustry.Vars.*;

public class DesktopLauncher extends ClientLauncher{
    public final static String discordID = "514551367759822855";
    boolean useDiscord = OS.is64Bit && !OS.isARM && !OS.hasProp("nodiscord"), loadError = false;
    Throwable steamError;
    static SdlConfig config;

    public static void main(String[] arg){
        try{
            int aaSamples = 0;
            String env = System.getenv("aaSamples");
            int idx = Seq.with(arg).indexOf("aaSamples");
            if (env != null) {
                if (Strings.canParsePositiveInt(env)) {
                    aaSamples = Integer.parseInt(env);
                    aaSamples = Math.min(aaSamples, 32);
                }
            }
            if (idx >= 0) {
                if (arg.length > idx + 1 && Strings.canParsePositiveInt(arg[idx + 1])) {
                    aaSamples = Integer.parseInt(arg[idx + 1]);
                    aaSamples = Math.min(aaSamples, 16);
                }
            }
            int finalAaSamples = aaSamples;

            Version.init();
            config = new SdlConfig() {{
                title = Strings.format("Mindustry (v@) | Foo's Client (@)", Version.buildString(), Version.clientVersion.equals("v0.0.0") ? "Dev" : Version.clientVersion);
                maximized = true;
                width = 900;
                height = 700;
                samples = finalAaSamples;
                setWindowIcon(FileType.internal, "icons/foo_64.png");
            }};
            Vars.loadLogger();
            new SdlApplication(new DesktopLauncher(arg), config);
        }catch(Throwable e){
            handleCrash(e);
        }
    }

    @Override
    public void stopDiscord() {
        DiscordRPC.INSTANCE.Discord_Shutdown();
    }

    @Override
    public void startDiscord() {
        if(useDiscord){
            try{
                DiscordRPC.INSTANCE.Discord_Initialize(discordID, null, true, "1127400");
                Log.info("Initialized Discord rich presence.");
                Runtime.getRuntime().addShutdownHook(new Thread(DiscordRPC.INSTANCE::Discord_Shutdown));
            }catch(Throwable t){
                useDiscord = false;
                Log.err("Failed to initialize discord. Enable debug logging for details.");
                Log.debug("Discord init error: \n@\n", Strings.getStackTrace(t));
            }
        }
    }

    public DesktopLauncher(String[] args){
        boolean useSteam = Version.modifier.contains("steam");
        testMobile = Seq.with(args).contains("-testMobile");

        add(Main.INSTANCE);

        if(useSteam){
            //delete leftover dlls
            for(Fi other : new Fi(".").parent().list()){
                if(other.name().contains("steam") && (other.extension().equals("dll") || other.extension().equals("so") || other.extension().equals("dylib"))){
                    other.delete();
                }
            }

            Events.on(ClientLoadEvent.class, event -> {
                if(steamError != null){
                    Core.app.post(() -> Core.app.post(() -> Core.app.post(() -> {
                        ui.showErrorMessage(Core.bundle.format("steam.error", (steamError.getMessage() == null) ? steamError.getClass().getSimpleName() : steamError.getClass().getSimpleName() + ": " + steamError.getMessage()));
                    })));
                }
            });

            try{
                SteamAPI.loadLibraries();

                if(!SteamAPI.init()){
                    loadError = true;
                    Log.err("Steam client not running.");
                }else{
                    initSteam(args);
                    Vars.steam = true;
                }

                if(SteamAPI.restartAppIfNecessary(SVars.steamID)){
                    System.exit(0);
                }
            }catch(NullPointerException ignored){
                steam = false;
                Log.info("Running in offline mode.");
            }catch(Throwable e){
                steam = false;
                Log.err("Failed to load Steam native libraries.");
                logSteamError(e);
            }
        }
    }

    void logSteamError(Throwable e){
        steamError = e;
        loadError = true;
        Log.err(e);
        try(OutputStream s = new FileOutputStream("steam-error-log-" + System.nanoTime() + ".txt")){
            String log = Strings.neatError(e);
            s.write(log.getBytes());
        }catch(Exception e2){
            Log.err(e2);
        }
    }

    void initSteam(String[] args){
        SVars.net = new SNet(new ArcNetProvider());
        SVars.stats = new SStats();
        SVars.workshop = new SWorkshop();
        SVars.user = new SUser();
        boolean[] isShutdown = {false};

        Events.on(ClientLoadEvent.class, event -> {
            Core.settings.defaults("name", SVars.net.friends.getPersonaName());
            if(player.name.isEmpty()){
                player.name = SVars.net.friends.getPersonaName();
                Core.settings.put("name", player.name);
            }
            steamPlayerName = SVars.net.friends.getPersonaName();
            //update callbacks
            Core.app.addListener(new ApplicationListener(){
                @Override
                public void update(){
                    if(SteamAPI.isSteamRunning()){
                        SteamAPI.runCallbacks();
                    }
                }
            });

            Core.app.post(() -> {
                if(args.length >= 2 && args[0].equals("+connect_lobby")){
                    try{
                        long id = Long.parseLong(args[1]);
                        ui.join.connect("steam:" + id, port);
                    }catch(Exception e){
                        Log.err("Failed to parse steam lobby ID: @", e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        });

        Events.on(DisposeEvent.class, event -> {
            SteamAPI.shutdown();
            isShutdown[0] = true;
        });

        //steam shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if(!isShutdown[0]){
                SteamAPI.shutdown();
            }
        }));
    }

    static void handleCrash(Throwable e){
        Cons<Runnable> dialog = Runnable::run;
        boolean badGPU = false;
        String finalMessage = Strings.getFinalMessage(e);
        String total = Strings.getCauses(e).toString();

        if(total.contains("Couldn't create window") || total.contains("OpenGL 2.0 or higher") || total.toLowerCase().contains("pixel format") || total.contains("GLEW")|| total.contains("unsupported combination of formats")){

            dialog.get(() -> message(
                total.contains("Couldn't create window") ? "A graphics initialization error has occured! Try to update your graphics drivers:\n" + finalMessage :
                            "Your graphics card does not support the right OpenGL features.\n" +
                                    "Try to update your graphics drivers. If this doesn't work, your computer may not support Mindustry.\n\n" +
                                    "Full message: " + finalMessage));
            badGPU = true;
        }

        boolean fbgp = badGPU;

        CrashSender.send(e, file -> {
            Throwable fc = Strings.getFinalCause(e);
            if(!fbgp){
                dialog.get(() -> message("A crash has occurred. It has been saved in:\n" + file.getAbsolutePath() + "\n" + fc.getClass().getSimpleName().replace("Exception", "") + (fc.getMessage() == null ? "" : ":\n" + fc.getMessage())));
            }
        });
    }

    @Override
    public Seq<Fi> getWorkshopContent(Class<? extends Publishable> type){
        return !steam ? super.getWorkshopContent(type) : SVars.workshop.getWorkshopFiles(type);
    }

    @Override
    public void viewListing(Publishable pub){
        SVars.workshop.viewListing(pub);
    }

    @Override
    public void viewListingID(String id){
        SVars.net.friends.activateGameOverlayToWebPage("steam://url/CommunityFilePage/" + id);
    }

    @Override
    public NetProvider getNet(){
        return steam ? SVars.net : new ArcNetProvider();
    }

    @Override
    public void openWorkshop(){
        SVars.net.friends.activateGameOverlayToWebPage("https://steamcommunity.com/app/1127400/workshop/");
    }

    @Override
    public void publish(Publishable pub){
        SVars.workshop.publish(pub);
    }

    @Override
    public void inviteFriends(){
        SVars.net.showFriendInvites();
    }

    @Override
    public void updateLobby(){
        if(SVars.net != null){
            SVars.net.updateLobby();
        }
    }

    @Override
    public void updateRPC(){
        //if we're using neither discord nor steam, do no work
        if((!useDiscord || !Core.settings.getBool("discordrpc")) && !steam) return;

        //common elements they each share
        boolean inGame = state.isGame();
        String gameMapWithWave = "Unknown Map";
        String gameMode = "";
        String gamePlayersSuffix = "";
        String uiState = "";

        if(inGame){
            //TODO implement nice name for sector
            gameMapWithWave = Strings.capitalize(Strings.stripColors(state.map.name()));

            if(state.rules.waves){
                gameMapWithWave += " | Wave " + state.wave;
            }
            gameMode = state.rules.pvp ? "PvP" : state.rules.attackMode ? "Attack" : "Survival";
            if(net.active() && Groups.player.size() > 1){
                gamePlayersSuffix = " | " + Groups.player.size() + " Players";
            }
        }else{
            if(ui.editor != null && ui.editor.isShown()){
                uiState = "In Editor";
            }else if(ui.planet != null && ui.planet.isShown()){
                uiState = "In Launch Selection";
            }else{
                uiState = "In Menu";
            }
        }

        if(useDiscord && Core.settings.getBool("discordrpc")){
            DiscordRichPresence presence = new DiscordRichPresence();

            if(inGame){
                presence.state = gameMode + gamePlayersSuffix;
                presence.details = gameMapWithWave;
                if(state.rules.waves){
                    presence.largeImageText = "Wave " + state.wave;
                }
            }else{
                presence.state = uiState;
            }

            presence.largeImageKey = "logo";
            presence.smallImageKey = "foo";
            presence.smallImageText = Strings.format("Foo's Client (@)", Version.clientVersion.equals("v0.0.0") ? "Dev" : Version.clientVersion);
            presence.startTimestamp = beginTime/1000;

            DiscordRPC.INSTANCE.Discord_UpdatePresence(presence);
        }

        if(steam){
            //Steam mostly just expects us to give it a nice string, but it apparently expects "steam_display" to always be a loc token, so I've uploaded this one which just passes through 'steam_status' raw.
            SVars.net.friends.setRichPresence("steam_display", "#steam_status_raw");

            SVars.net.friends.setRichPresence("steam_status", Strings.format("Foo's Client (@) | @", Version.clientVersion.equals("v0.0.0") ? "Dev" : Version.clientVersion, inGame ? gameMapWithWave : uiState));
        }
    }

    @Override
    public String getUUID(){
        if(steam){
            try{
                byte[] result = new byte[8];
                new Rand(SVars.user.user.getSteamID().getAccountID()).nextBytes(result);
                return new String(Base64Coder.encode(result));
            }catch(Exception e){
                e.printStackTrace();
            }
        }

        return super.getUUID();
    }

    private static void message(String message){
        SDL.SDL_ShowSimpleMessageBox(SDL.SDL_MESSAGEBOX_ERROR, "oh no", message);
    }

    private boolean validAddress(byte[] bytes){
        if(bytes == null) return false;
        byte[] result = new byte[8];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        return !new String(Base64Coder.encode(result)).equals("AAAAAAAAAOA=") && !new String(Base64Coder.encode(result)).equals("AAAAAAAAAAA=");
    }
}
