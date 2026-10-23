package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Original ⇄ Free My TV. "free": our home screen and screensaver, and the apps in the lists below turned off
 * (pm disable-user: still installed, back with one button). "original": everything as it came from the factory.
 *
 * It all goes through the TV's own ADB (Adb, 127.0.0.1:5555), which you allow once on the TV. The switches run as
 * background jobs (ModeJob) in the main process; the settings screen (the :home process) asks for them and follows the
 * state file. Only the TV itself asks for switches: nothing here listens to the network.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "io.freemytv";
    static final String HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity";
    static final String SETTINGS_ACTIVITY = PKG + "/" + PKG + ".SettingsActivity";
    /** Our screensaver: on Android 11 the copy that runs in the main process (ScreensaverAlone, #10). */
    static final String DREAM = PKG + "/" + PKG + (Build.VERSION.SDK_INT == 30 ? ".ScreensaverAlone" : ".Screensaver");
    static final String FREE = "free", ORIGINAL = "original";

    /** Classic Android TV's home screen; its recommendations service crashes on the screensaver while it's off (#11). */
    static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    static final String TV_RECOMMENDATIONS = "com.google.android.tvrecommendations";
    /** Google TV's home screen: the fallback when the TV's own one wasn't recorded. */
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
    /**
     * Google TV has a RecoveryActivity that would take over the home screen without its launcher. Older Android TV
     * (Android 9) has the same setup app without a home screen: there it is left alone.
     */
    static final String GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";

    /**
     * Turned off in Free My TV mode, when the TV has them. Google extras first, then TV-maker extras (TCL here; add
     * your brand's, see the README). The TV's own home screen and screensaver are added at switch time.
     */
    static final String[] DEBLOAT = {
        "com.google.android.play.games",        // Play Games, YouTube Music
        // TCL
        "com.tcl.magiconnectfree",                                // screen mirroring apps
        "com.tcl.waterfall.overseas", "com.tcl.channelplus",                          // TCL Channel and its Live TV
        "com.tcl.messagebox", "com.tcl.usercenter",                                   // TCL messages and account
        "com.tcl.exhibit", "com.tcl.esticker", "com.tcl.hotelmenu",                   // store demo, hotel menu
        "com.tcl.repairguide", "com.tcl.ocean.instructions", "com.tcl.logkit",        // repair guide, manual, logs
        // Multilaser (Expressluck / CVTE board)
        "tv.anoki.acr.anokiacroptin",                                                 // Anoki ACR: watches what's on screen
        "fusion.android.tv.demo", "com.expressluck.tvhelpmanual",                     // store demo (also Xiaomi projectors), manual
        // Philco (Homwee/Changhong)
        "com.homwee.aipont",                                                          // AI PONT: promo channels, app downloads
        "com.homwee.help",                                                            // user manual
        // Xiaomi
        "com.miui.tv.analytics", "com.xiaomi.statistic",                              // telemetry
        "com.mitv.tvhome.mitvplus", "com.xm.webcontent",                              // Mi TV+ promo channels, promo web content
        // Skyworth (VEON and other badges; MediaTek)
        "com.smartdevice.epopplayer", "com.coocaa.demonow.lightining",               // store demos (start on screen on)
        "com.smartdevice.recommendation",                                             // Coolita promo channels
        // KTC (NextStar; MediaTek)
        "tv.anoki.acr.controller",                                                    // Anoki ACR: watches what's on screen
        "com.mediatek.tv.retaildemo", "com.electronic.manual",                        // store demo, manual
    };

    /** The voice assistant, only with disable_assistant=true in config.properties (the mic button stops working). */
    static final String[] ASSISTANT = {"com.google.android.katniss", "com.android.hotwordenrollment.okgoogle"};

    /** Never turned off, not even by mistake (picture, sound, inputs, remote, store). Checked before every disable. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
        "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv", "com.tcl.tvinput",
        "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay", "com.tcl.autopair",
        "com.tcl.UpdatePeripheral", "com.tcl.dashboard", "com.tcl.suspension", "com.tcl.providers.config",
        "com.tcl.gamebar", "com.tcl.airplay2", "com.mirakulo.astrotv.service", "com.tcl.initsetup",
        "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
        // TCL on Google TV (C825, Android 11): system server, Bluetooth remote and mic, keys, settings and quick menu,
        // keyboard, webview, Wi-Fi overlay, partner setup
        "com.tcl.system.server", "com.tcl.tcl_bt_rcu_service", "com.tcl.micmanager", "com.tcl.keyhelp",
        "com.tcl.settings", "com.tcl.common.shortcutmenu", "com.tcl.inputmethod.international", "com.tcl.android.webview",
        "com.tcl.wifi.resources.overlay", "com.tcl.partnercustomizer", "com.tcl.tv.tclhome_passive",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.inputmethod.latin", "com.google.android.webview",
        "com.android.tv.settings", "com.android.systemui", "android", PKG,
        // Philips (TP Vision) and MediaTek: system, Ambilight, live TV and inputs, setup, factory menu (#11)
        "org.droidtv", "org.droidtv.ambisvc", "org.droidtv.ui.strings", "org.droidtv.tv.packageinfo", "org.droidtv.icp",
        "org.droidtv.gel", "org.droidtv.eum", "com.mediatek.tv.service", "com.mediatek.tvinput", "com.mediatek.network",
        "com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.setupwizard", "com.mediatek.tv.factory",
        "mediatek.factorymenu.ui", "com.tpv.xmic.ots.cms.client",
        // Multilaser (Expressluck / CVTE): remote pairing and keys, live TV, media player, factory menus
        "com.express.bt.autopair", "com.mediatek.autopair", "com.mediatek.hotkey.dispatcher",
        "com.mediatek.android.tv.globalkeyoverlay", "com.cltv.fast", "com.mediatek.wwtv.mediaplayer",
        "com.mediatek.androidbox", "com.expressluck.facmenu", "com.express.at.factory.factorymanager",
        "com.at.factory.androidtvaidlservice", "com.cvte.tv.at", "com.cvte.tv.fac.aging", "com.express.fac.test",
        // Philco (Homwee/Changhong, MediaTek): picture tests, overlays, updates, factory menu (pairing is above)
        "com.mediatek.tv.factorykey", "com.mediatek.testpq", "com.mediatek.overlay.tvcenter",
        "com.mediatek.overlay.tvsettings", "com.homwee.overlay.tvservice", "com.homwee.synckey", "com.homwee.upgrade",
        "com.homwee.dmt.upgrade", "com.homwee.factory", "com.homwee.chinstallapk", "com.homwee.chtvmanager",
        "com.mstar.android.tv.disclaimercustomization",
        // Xiaomi (MediaTek): system service, remote pairing, live TV, quick settings, child lock, updates, casting
        "mitv.service", "com.mi.bluetooth", "com.mitv.livetv.ci_mediatek", "com.xiaomi.tvqs.overseas",
        "com.mitv.tvlock", "com.xiaomi.mitv.updateservice", "com.xiaomi.android.tvsetup.partnercustomizer",
        "com.mitv.overseaservice", "com.xiaomi.mitv.tvmanager", "com.xiaomi.wfdsinkhelperservice",
        "com.mitv.milinkservice", "com.xiaomi.mitv.smartshare", "android.autoinstalls.config.xiaomi.amelie",
        // Skyworth (MediaTek): live TV, remote pairing and keys, setup, service menu, casting, OLED care, boot
        "com.smartdevice.livetv", "com.smartdevice.livetv.overlay_SANLUX", "com.smartdevice.bleconnectservice",
        "com.smartdevice.duplebluetooth", "com.smartdevice.myapp", "com.smartdevice.setupwraith",
        "com.smartdevice.aftersale", "com.smartdevice.factory", "com.smartdevice.dailymanage",
        "com.smartdevice.dtv.icast", "com.smartdevice.leanbacklauncher.customizer", "com.mediatek.OledFuctionService",
        "com.mediatek.android.boot.appsplashscreen", "com.mediatek.android.leanbacklauncher.partnercustomizer",
        // KTC (NextStar; MediaTek): live TV and tuners, setup, settings, updates, casting, remote pairing, factory menus
        "com.mediatek.tv.oneworld.tvcenter", "com.mediatek.tis", "com.mediatek.tv.agent",
        "com.mediatek.dtv.tvinput.dvbtuner", "com.mediatek.tv.oneworld.wizardkt", "com.mediatek.tv.settings",
        "com.mediatek.tv.systemupdater", "com.mediatek.extservice", "com.mediatek.dialservice", "com.mediatek.miracast",
        "com.mediatek.tv.launcher.partner", "com.google.android.tv.googletvremotepairer", "com.ktc.serialport",
        "com.ktc.tv.factory", "ktc.factorymenu.ui",
        // Xiaomi projectors (Fengmi/Formovie, MediaTek): keystone, focus, picture and sound, system service, remote
        // pairing, settings provider, factory tests, launcher config, Freeview live TV
        "com.fengmi.projector.imaging", "com.fengmi.platform", "com.fengmi.Autopair", "com.fengmi.fmsettings",
        "com.fengmi.testsuite", "android.autoinstalls.config.fomovie",
        "uk.co.freeview.tifbridge",
        "uk.co.freeview.mdsclient", "uk.co.freeview.fvpconfigauth"));

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this). */
    static void request(Context c, String mode) {
        ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to the TV's own (asked by :home). */
    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    /** After the TV boots (and after a TV update): wait for the system to settle and check Free My TV mode. */
    static void bootCheck(Context c) {
        ensureRemoteService(c, 30_000);
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45_000);
    }

    /** After an update of this app: complete Free My TV mode if anything is missing. */
    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    /**
     * The remote's app buttons (RemoteKeys) need an accessibility service that only filters those keys. It is on only
     * on TCL, Vestel, KTC and Sony TVs (and Formovie projectors) in Free My TV mode, and goes off with Original. Android turns it off when this app is updated, so the
     * boot check and an update turn it back on.
     */
    static void ensureRemoteService(Context c, long delayMs) {
        if (RemoteKeys.supported()) ModeJob.schedule(c, ModeJob.REMOTE, null, delayMs);
    }

    static void ensureRemoteServiceNow(Context app) {
        if (!RemoteKeys.supported() || !FREE.equals(state(app).optString("mode"))) return;
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            remoteService(app, adb, true);
        } catch (IOException e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    private static final String REMOTE_SERVICE = PKG + "/" + PKG + ".RemoteKeyService";
    private static final Pattern SERVICE = Pattern.compile("[\\w.]+/[\\w.$]+");

    /** Adds ours to the enabled accessibility services (keeping the others, like TalkBack), or takes it out. */
    private static void remoteService(Context c, Adb adb, boolean on) throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (current.equals("null")) current = "";
        List<String> others = new ArrayList<>();
        for (String one : current.split(":")) {
            if (one.isEmpty() || one.equals(REMOTE_SERVICE)) continue;
            // Only plain component names go back into the shell command; anything else is left alone.
            if (!SERVICE.matcher(one).matches()) {
                AppLog.w("accessibility services left as they are: unexpected entry");
                return;
            }
            others.add(one);
        }
        if (on == current.contains(REMOTE_SERVICE)) return;
        List<String> list = new ArrayList<>(others);
        if (on) {
            JSONObject s = state(c);
            try { s.put("accessibilityWasOff", !"1".equals(adb.shell("settings get secure accessibility_enabled").trim())); }
            catch (JSONException ignored) {}
            save(c, s);
            list.add(REMOTE_SERVICE);
        }
        adb.shell(list.isEmpty() ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services '" + String.join(":", list) + "'");
        if (on) adb.shell("settings put secure accessibility_enabled 1");
        else if (list.isEmpty() && state(c).optBoolean("accessibilityWasOff")) adb.shell("settings put secure accessibility_enabled 0");
        AppLog.w("remote buttons service " + (on ? "on" : "off"));
    }

    // MARK: - Switches (main process, from ModeJob)

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            if (FREE.equals(mode)) applyFree(c); else applyOriginal(c);
        }
    }

    private static void applyFree(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original" restores
            // exactly what was there, on any brand.
            String home = originalHome(s);
            if (home.isEmpty() || home.startsWith(PKG + "/")) {
                String found = currentHome(adb);
                home = found != null ? realHome(found) : "";
            }
            String dream = originalDream(s);
            if (dream.isEmpty()) dream = defaultDream(adb);
            JSONObject latest = state(c);
            latest.put("originalHome", home).put("originalDream", dream);
            String saver = adb.shell("settings get secure screensaver_components").trim();
            if (!saver.contains(PKG + "/")) latest.put("originalScreensaver", safeComponents(saver));
            save(c, latest);
            // Turning off an app makes Android delete the Wi-Fi networks it saved (#14), and Original can't bring them
            // back. The TV's first setup usually saved the network: then stop here, before anything changes.
            Set<String> owners = wifiOwners(adb);
            boolean setupHome = setupIsHome(adb);
            if ((setupHome && owners.contains(GOOGLE_SETUP)) || owners.contains(packageOf(home))) {
                throw new IOException(Ui.tr("a configuração inicial da TV salvou o Wi-Fi, e desligá-la apagaria essas redes. "
                        + "Em Configurações → Rede e Internet, esqueça a rede e conecte de novo (as fora de alcance não aparecem "
                        + "lá: o Claude Code apaga pelo computador) e tente outra vez.",
                        "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                        + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                        + "show there: Claude Code can remove them from your computer), then try once more.",
                        "la configuración inicial del TV guardó el Wi-Fi, y desactivarla borraría esas redes. "
                        + "En Ajustes → Red e Internet, olvida la red y vuelve a conectarte (las fuera de alcance no aparecen "
                        + "ahí: Claude Code las borra desde la computadora) y vuelve a intentarlo."));
            }
            if (metrics(latest, ORIGINAL) == null && !home.isEmpty()) {
                busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
                measureHomeTime(c, adb, ORIGINAL);
                measureMemory(c, adb, ORIGINAL);
            }
            busy(c, Ui.tr("Trocando o Início e a tela de descanso…", "Switching the home screen and screensaver…"));
            Watchdog.clear(c);
            check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                    Ui.tr("o Android não aceitou o novo Início", "Android didn't accept the new home screen"));
            adb.shell("settings put secure screensaver_components " + DREAM);
            JSONObject started = state(c);
            started.put("mode", FREE).put("homeReverted", false).put("setupHome", setupHome);
            save(c, started);
            List<String> targets = targets(home, dream, setupHome);
            Set<String> disabled = new HashSet<>(stringList(state(c), "disabled"));
            Set<String> present = packages(adb, "");
            Set<String> keptOff = new HashSet<>(stringList(state(c), "keptOff"));
            // Off now and not on our list: the person turned it off, unless an earlier install of Free My TV did (its
            // list lives in the TV's settings too, since an uninstall erases our state file).
            Set<String> ours = recorded(adb);
            for (String pkg : packages(adb, "-d")) {
                if (targets(home, dream, true).contains(pkg) && !disabled.contains(pkg) && !ours.contains(pkg)) keptOff.add(pkg);
            }
            keptOff.removeAll(ours);
            JSONObject before = state(c);
            before.put("keptOff", new JSONArray(sorted(keptOff)));
            save(c, before);
            int done = 0;
            for (String pkg : targets) {
                done++;
                busy(c, Ui.tr("Desligando apps (", "Turning off apps (") + done + "/" + targets.size() + ")…");
                if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg) || keptOff.contains(pkg)) continue;
                String out = adb.shell("pm disable-user --user 0 " + pkg);
                if (out.contains("disabled-user")) {
                    disabled.add(pkg);
                    JSONObject now = state(c);
                    now.put("disabled", new JSONArray(sorted(disabled)));
                    save(c, now);
                } else {
                    AppLog.w("could not turn off " + pkg + ": " + out.trim());
                }
            }
            record(adb, disabled);
            s = state(c);
            s.put("mode", FREE).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(sorted(disabled))).put("setupHome", setupHome);
            save(c, s);
            if (RemoteKeys.supported()) {
                try {
                    remoteService(c, adb, true);
                } catch (IOException e) {
                    AppLog.w("remote buttons service: " + e.getMessage());
                }
            }
            notice(c, Ui.tr("Weatherglass TV ligado: Início e tela de descanso novos, " + disabled.size() + " apps desligados.",
                    "Weatherglass TV is on: new home screen and screensaver, " + disabled.size() + " apps turned off."));
            busy(c, Ui.tr("Medindo o Weatherglass TV…", "Measuring Weatherglass TV…"));
            measureHomeTime(c, adb, FREE);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, FREE, 120_000);
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para ligar o Weatherglass TV: ", "Couldn't turn on Weatherglass TV: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
        }
    }

    private static void applyOriginal(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            busy(c, Ui.tr("Religando os apps…", "Turning the apps back on…"));
            String home = originalHome(s);
            Set<String> toEnable = new HashSet<>(stringList(s, "disabled"));
            for (String pkg : targets(home, originalDream(s), true)) {
                if (!Arrays.asList(DEBLOAT).contains(pkg) && !Arrays.asList(ASSISTANT).contains(pkg)) toEnable.add(pkg);
            }
            toEnable.removeAll(stringList(s, "keptOff"));
            Set<String> present = packages(adb, "");
            int done = 0;
            List<String> failed = new ArrayList<>();
            for (String pkg : sorted(toEnable)) {
                done++;
                busy(c, Ui.tr("Religando apps (", "Turning apps back on (") + done + "/" + toEnable.size() + ")…");
                if (!present.contains(pkg)) continue;
                // Checked: whatever didn't come back stays on the list (and in the notice); nothing disappears silently.
                String out = adb.shell("pm enable " + pkg);
                if (!out.contains("new state: enabled")) failed.add(pkg);
            }
            record(adb, new HashSet<>(failed));
            busy(c, Ui.tr("Devolvendo o Início original…", "Restoring the original home screen…"));
            boolean homeOk = restoreHome(adb, home);
            restoreScreensaver(adb, s);
            try {
                remoteService(c, adb, false);
            } catch (IOException e) {
                AppLog.w("remote buttons service: " + e.getMessage());
            }
            s = state(c);
            s.put("mode", ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(failed)).remove("keptOff");
            save(c, s);
            if (failed.isEmpty() && homeOk) {
                notice(c, Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta.",
                        "Original mode: the factory home screen, screensaver and apps are back."));
            } else {
                List<String> problems = new ArrayList<>(failed);
                if (!homeOk) problems.add(Ui.tr("o Início original", "the original home screen"));
                notice(c, Ui.tr("Modo original, mas não consegui religar: ", "Original mode, but I couldn't turn back on: ")
                        + String.join(", ", problems) + ".");
            }
            busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
            measureHomeTime(c, adb, ORIGINAL);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, 120_000);
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para voltar ao original: ", "Couldn't go back to the original: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
        }
    }

    /** The recorded home screen; without one, Google TV's; without that, the system picks (we leave the role). */
    private static boolean restoreHome(Adb adb, String home) throws IOException {
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME;
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home));
            if (adb.shell("cmd package set-home-activity '" + home + "'").contains("Success")) return true;
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG);
        return !homeHolder(adb).contains(PKG);
    }

    /** Safety net: our home screen crashed 3 times in a row. The home screen and the screensaver go back; the rest stays. */
    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                boolean ours = homeHolder(adb).contains(PKG);
                if (!ours && (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted"))) return;
                String home = originalHome(s);
                List<String> keptOff = stringList(s, "keptOff");
                if (!keptOff.contains(GOOGLE_SETUP)) adb.shell("pm enable " + GOOGLE_SETUP);
                if (!restoreHome(adb, home)) {
                    AppLog.w("could not give the home screen back");
                    return;
                }
                String homePkg = packageOf(home), dreamPkg = packageOf(originalDream(s));
                if (dreamPkg != null && !keptOff.contains(dreamPkg)) adb.shell("pm enable " + dreamPkg);
                JSONArray kept = new JSONArray();
                Set<String> stillOff = new HashSet<>();
                for (String pkg : stringList(s, "disabled")) {
                    if (!pkg.equals(GOOGLE_SETUP) && !pkg.equals(homePkg) && !pkg.equals(dreamPkg)) {
                        kept.put(pkg);
                        stillOff.add(pkg);
                    }
                }
                record(adb, stillOff);
                // The screensaver first: if that fails, the revert isn't marked done and the next try does it again.
                restoreScreensaver(adb, s);
                s.put("homeReverted", true).put("disabled", kept);
                save(app, s);
                notice(app, Ui.tr("O Início do Weatherglass TV caiu 3 vezes seguidas; voltei para o Início original.",
                        "Weatherglass TV's home screen crashed 3 times in a row; I switched back to the original one."));
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            } catch (Exception e) {
                AppLog.w("could not give the home screen back: " + e.getMessage());
            }
        }
    }

    /**
     * In Free My TV mode, checks that everything is still as we left it (the listed apps off, our home screen and
     * screensaver) and completes whatever is missing, with a notice. Runs after the TV boots (TV updates can turn
     * things back on) and after an update of this app.
     */
    static void verifyNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            String fingerprint = Build.FINGERPRINT;
            boolean updated = !fingerprint.equals(s.optString("fingerprint", fingerprint));
            try {
                s.put("fingerprint", fingerprint);
                s.remove("busy");
                save(app, s);
            } catch (JSONException ignored) {}
            if (!FREE.equals(s.optString("mode"))) return;
            List<String> fixed = new ArrayList<>();
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                Set<String> present = packages(adb, "");
                Set<String> disabledNow = packages(adb, "-d");
                Set<String> owners = wifiOwners(adb);
                boolean reverted = s.optBoolean("homeReverted");
                String home = originalHome(s);
                Set<String> wanted = new HashSet<>(stringList(s, "disabled"));
                String dream = originalDream(s);
                for (String pkg : targets(home, dream, s.optBoolean("setupHome", true))) {
                    if (!reverted || !(pkg.equals(packageOf(home)) || pkg.equals(GOOGLE_SETUP) || pkg.equals(packageOf(dream)))) {
                        wanted.add(pkg);
                    }
                }
                Set<String> disabled = new HashSet<>(stringList(s, "disabled"));
                // Off before Free My TV: turned off again if a TV update brings one back, but never handed to
                // Original to turn on.
                List<String> keptOff = stringList(s, "keptOff");
                for (String pkg : sorted(wanted)) {
                    if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue;
                    if (disabledNow.contains(pkg)) {
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        continue;
                    }
                    if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        fixed.add(pkg);
                    }
                }
                if (!reverted && !homeHolder(adb).contains(PKG)) {
                    adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
                    fixed.add(Ui.tr("o Início", "the home screen"));
                }
                // Whole component names: ".ScreensaverAlone" (Android 11) contains ".Screensaver" (12 and newer).
                if (!reverted && !Arrays.asList(adb.shell("settings get secure screensaver_components").trim().split(",")).contains(DREAM)) {
                    adb.shell("settings put secure screensaver_components " + DREAM);
                    fixed.add(Ui.tr("a tela de descanso", "the screensaver"));
                }
                record(adb, disabled);
                JSONObject latest = state(app);
                latest.put("disabled", new JSONArray(sorted(disabled)));
                save(app, latest);
            } catch (Exception e) {
                notice(app, Ui.tr("Não consegui conferir o Weatherglass TV: ", "Couldn't check Weatherglass TV: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
                return;
            }
            if (!fixed.isEmpty()) {
                notice(app, (updated ? Ui.tr("A atualização da TV religou ", "The TV update turned back on ")
                        : Ui.tr("Desliguei o que faltava: ", "Turned off what was missing: ")) + String.join(", ", fixed) + ".");
            } else if (updated) {
                notice(app, Ui.tr("A TV foi atualizada; o Weatherglass TV continua igual.", "The TV was updated; Weatherglass TV is still on."));
            }
        }
    }

    /**
     * What to turn off: the TV's own home screen and screensaver, Google TV's recovery home (when setupHome: the setup
     * app is one of the TV's home screens), and the lists above.
     */
    private static List<String> targets(String home, String dream, boolean setupHome) {
        List<String> out = new ArrayList<>();
        String homePkg = packageOf(home);
        if (homePkg != null && !homePkg.equals(PKG)) out.add(homePkg);
        if (TV_LAUNCHER.equals(homePkg)) out.add(TV_RECOMMENDATIONS);
        if (setupHome) out.add(GOOGLE_SETUP);
        String dreamPkg = packageOf(dream);
        if (dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) out.add(dreamPkg);
        out.addAll(Arrays.asList(DEBLOAT));
        if (Config.DISABLE_ASSISTANT) out.addAll(Arrays.asList(ASSISTANT));
        return out;
    }

    /** The home screen in use now ("package/activity"): the HOME role holder, among the TV's home screens. */
    private static String currentHome(Adb adb) throws IOException {
        String holder = homeHolder(adb);
        if (holder.isEmpty() || holder.contains(PKG)) return null;
        String list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : list.split("\n")) {
            line = line.trim();
            if (line.startsWith(holder + "/")) return line;
        }
        return null;
    }

    /**
     * The package that is the home screen now, or "". Some Android 12 TVs have no `cmd role get-role-holders` (it
     * answers "Unknown command"), so then we ask which activity opens HOME.
     */
    private static String homeHolder(Adb adb) throws IOException {
        String holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim();
        if (holder.matches("[\\w.]+(;[\\w.]+)*")) return holder;
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").trim().split("\n");
        String component = lines[lines.length - 1].trim();
        // With no default home yet, the system's chooser answers ("android/...ResolverActivity"): unknown.
        return component.contains("/") && !component.startsWith("android/") ? packageOf(component) : "";
    }

    /** Whether Google TV's setup app is one of the home screens (its RecoveryActivity), so it must go off too. */
    private static boolean setupIsHome(Adb adb) throws IOException {
        return adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").contains(GOOGLE_SETUP + "/");
    }

    /** Apps that saved a Wi-Fi network ("cname" in the Wi-Fi service's list of saved networks). */
    private static Set<String> wifiOwners(Adb adb) throws IOException {
        Set<String> owners = new HashSet<>();
        Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"));
        while (m.find()) owners.add(m.group(1));
        return owners;
    }

    /** The recorded home screen of the TV, as {@link #realHome}. */
    private static String originalHome(JSONObject s) {
        return realHome(s.optString("originalHome", ""));
    }

    /**
     * Some TVs (TCL, Android 14) leave the HOME role with Google TV's recovery home, while launcherx is the one that
     * answers the Home button. Treat that as launcherx, or it stays on and "original" restores the recovery screen.
     */
    private static String realHome(String home) {
        return home.startsWith(GOOGLE_SETUP + "/") ? GOOGLE_HOME : safeComponent(home);
    }

    private static String originalDream(JSONObject s) {
        return safeComponent(s.optString("originalDream", ""));
    }

    // Nested classes ("Outer$Inner") are valid; every shell command that takes one quotes it, so $ stays literal.
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z][\\w.]*/[\\w.$]+");

    private static String safeComponent(String component) {
        return COMPONENT.matcher(component).matches() ? component : "";
    }

    private static String safeComponents(String components) {
        for (String one : components.split(",")) {
            if (safeComponent(one).isEmpty()) return "";
        }
        return components;
    }

    private static void restoreScreensaver(Adb adb, JSONObject s) throws IOException {
        String saver = safeComponents(s.optString("originalScreensaver", ""));
        if (saver.isEmpty()) adb.shell("settings delete secure screensaver_components");
        else adb.shell("settings put secure screensaver_components '" + saver + "'");
    }

    /** The system's default screensaver ("package/service"), or "". */
    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? safeComponent(d) : "";
    }

    private static String packageOf(String component) {
        if (component == null || component.isEmpty()) return null;
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }

    /** Packages the shell sees (all; "-d" = only the disabled ones). The app alone can't see the ones it doesn't declare. */
    private static Set<String> packages(Adb adb, String flags) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell("pm list packages " + flags).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    private static Adb connect(Context c, JSONObject s) throws IOException {
        busy(c, Ui.tr("Conectando…", "Connecting…"));
        Adb adb = Adb.connect(c, 120_000, () -> busy(c, Ui.tr("Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                "Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
        if (!s.optBoolean("adbAuthorized")) {
            JSONObject latest = state(c);
            try { latest.put("adbAuthorized", true); } catch (JSONException ignored) {}
            save(c, latest);
        }
        return adb;
    }

    private static void check(String output, String expected, String error) throws IOException {
        if (!output.contains(expected)) throw new IOException(error + " (" + output.trim() + ")");
    }

    // MARK: - Measurements (without opening apps: memory and the home button)

    private static void measureHomeTime(Context c, Adb adb, String mode) throws IOException, JSONException {
        List<Integer> samples = new ArrayList<>();
        Pattern total = Pattern.compile("TotalTime: (\\d+)");
        for (int i = 0; i < 3; i++) {
            adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
            SystemClock.sleep(500);
            Matcher m = total.matcher(adb.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"));
            if (m.find()) samples.add(Integer.parseInt(m.group(1)));
            SystemClock.sleep(600);
        }
        adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
        if (samples.isEmpty()) return;
        Collections.sort(samples);
        putMetric(c, mode, "homeMs", samples.get(samples.size() / 2));
    }

    /** Two minutes after a switch (the home screen settled, caches trimmed). */
    static void measureMemoryNow(Context c, String mode) {
        synchronized (RUN) {
            if (!mode.equals(state(c).optString("mode"))) return;
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                measureMemory(c, adb, mode);
            } catch (Exception e) {
                AppLog.w("memory measurement failed: " + e.getMessage());
            }
        }
    }

    private static void measureMemory(Context c, Adb adb, String mode) throws IOException, JSONException {
        Matcher avail = Pattern.compile("MemAvailable:\\s+(\\d+) kB").matcher(adb.shell("cat /proc/meminfo"));
        if (avail.find()) putMetric(c, mode, "memAvailableMB", Long.parseLong(avail.group(1)) / 1024);
        long pss = 0;
        String home = packageOf(originalHome(state(c)));
        String[] processes = FREE.equals(mode) ? new String[] {PKG + ":home"}
                : home == null ? new String[0] : new String[] {home, home + ":coreservices"};
        Pattern total = Pattern.compile("TOTAL PSS:\\s+(\\d+)");
        for (String p : processes) {
            Matcher m = total.matcher(adb.shell("dumpsys meminfo " + p));
            if (m.find()) pss += Long.parseLong(m.group(1));
        }
        if (pss > 0) putMetric(c, mode, "homePssMB", pss / 1024);
    }

    private static void putMetric(Context c, String mode, String key, long value) throws JSONException {
        JSONObject s = state(c);
        JSONObject metrics = s.optJSONObject("metrics");
        if (metrics == null) metrics = new JSONObject();
        JSONObject m = metrics.optJSONObject(mode);
        if (m == null) m = new JSONObject();
        m.put(key, value).put("at", System.currentTimeMillis());
        metrics.put(mode, m);
        s.put("metrics", metrics);
        save(c, s);
    }

    private static JSONObject metrics(JSONObject s, String mode) {
        JSONObject metrics = s.optJSONObject("metrics");
        return metrics == null ? null : metrics.optJSONObject(mode);
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "home-state.json");
            if (!f.exists()) return defaults();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaults();
            }
        }
    }

    private static JSONObject defaults() {
        try {
            return new JSONObject().put("mode", ORIGINAL);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir();
            File tmp = new File(dir, "home-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "home-state.json"))) AppLog.w("could not replace the state file");
        }
    }

    private static void busy(Context c, String text) {
        JSONObject s = state(c);
        try { s.put("busy", text).put("busyAt", SystemClock.elapsedRealtime()).put("error", ""); } catch (JSONException ignored) {}
        save(c, s);
    }

    private static void done(Context c, String error) {
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) {}
        save(c, s);
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        JSONObject s = state(c);
        JSONArray old = s.optJSONArray("notices");
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
            if (old != null) for (int i = 0; i < old.length() && list.length() < 20; i++) list.put(old.get(i));
            s.put("notices", list);
        } catch (JSONException ignored) {}
        save(c, s);
    }

    // MARK: - Helpers

    static List<String> stringList(JSONObject s, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = s.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /**
     * What Free My TV turned off, kept in the TV's own settings too: an uninstall or Clear data erases our state file,
     * and a new install must still tell its own work from what the person had turned off (#142).
     */
    private static final String RECORD = "free_my_tv_disabled";

    private static Set<String> recorded(Adb adb) {
        Set<String> out = new HashSet<>();
        try {
            String v = adb.shell("settings get global " + RECORD).trim();
            if (!v.isEmpty() && !v.equals("null")) for (String pkg : v.split(",")) if (pkg.matches("[\\w.]+")) out.add(pkg);
        } catch (IOException e) {
            AppLog.w("could not read the record of what we turned off: " + e.getMessage());
        }
        return out;
    }

    private static void record(Adb adb, Set<String> disabled) {
        List<String> list = new ArrayList<>();
        for (String pkg : sorted(disabled)) if (pkg.matches("[\\w.]+")) list.add(pkg);
        try {
            adb.shell(list.isEmpty() ? "settings delete global " + RECORD
                    : "settings put global " + RECORD + " " + String.join(",", list));
        } catch (IOException e) {
            AppLog.w("could not save the record of what we turned off: " + e.getMessage());
        }
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }
}
