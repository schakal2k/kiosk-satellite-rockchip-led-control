// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchip;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Owns all hardware access, effects and root helper lifetime for this plugin.
 *
 * Dual-bar variant for panels with two LED bars (Allnet 174716): each bar is an
 * independent "side" with its own power, colour, brightness, effect and Home
 * Assistant light. A bar can show one of seven colours at a time (see LedMath.channel).
 */
public final class RockchipLedPlugin implements KioskPlugin {
    // The original five, still driven by LedMath.frame (unchanged), plus the
    // 19 ported from davidcoulson/kiosk-satellite's led_effects.dart — see
    // LedEffectRegistry for the name -> LedRichEffect mapping. Includes
    // "None" for internal validation (onEvent below, and the plugin's own
    // Settings dropdown) — 25 entries.
    private static final String[] EFFECTS={
        "None","Pulse","Blink","Rainbow","Candle","Random",
        "Sunrise/Sunset","Moonlight Glow","Lightning Storm","Wake-Up Alarm","Candle Flicker",
        "Fairytwinkle","Fireworks Burst","Beacon Pulse","Heartbeat Pulse","Soft Glow","Rolling Fog (Pronounced)",
        "Pacifica (Calm Lagoon)","Pacifica (Storm)","Pacifica (Deep Current)",
        "Aurora (Solar Storm)","Aurora (Pastel Dream)","Aurora (Red Sky)",
        "Bubbles","Disco Sparkle",
    };
    // The list handed to host.publishLight, which caps a registered light at
    // 24 effects (PluginBridge.kt's publishLight: require(effects.size <= 24
    // ...), with no message — surfaces as the unhelpful "Failed requirement."
    // if exceeded). Drops "None": PluginLightState.validate on the host side
    // already treats "None" as valid even when absent from the declared
    // list, so sending it still works — it just won't appear as a pickable
    // option in Home Assistant's effect dropdown, only via this plugin's own
    // Settings screen (which validates against the full EFFECTS above).
    private static final String[] ENTITY_EFFECTS=Arrays.copyOfRange(EFFECTS,1,EFFECTS.length);
    // probe() returns -errno from open("/dev/ledjni"). These two mean the
    // node is not there at all, which is the one failure no amount of
    // retrying, permission fixing or root escalation can clear -- root
    // cannot conjure a device node the kernel never published. EACCES (-13)
    // is deliberately NOT here: that node exists, and the root fallback may
    // still reach it.
    private static final int ENOENT=-2;
    private static final int ENODEV=-19;
    private final AtomicBoolean alive=new AtomicBoolean();
    private PluginHost host;
    private ScheduledExecutorService worker;
    private volatile LedTransport transport;
    private Map<String,Object> settings=new HashMap<>();
    /** Highest level the hardware accepts per colour. */
    private static final int MAX_LEVEL=15;
    private static final Map<String,Object> SIDE_DEFAULTS=new HashMap<>();
    static {
        SIDE_DEFAULTS.put("powerLeft",false);SIDE_DEFAULTS.put("colorLeft","#00A8FF");
        SIDE_DEFAULTS.put("brightnessLeft",50);SIDE_DEFAULTS.put("effectLeft","None");
        SIDE_DEFAULTS.put("swapSides",false);
    }
    /** One LED bar. Settings keys carry the suffix ("" for right, "Left" for left). */
    private static final class Side {
        final String light,suffix;final int index;
        long effectStart;int[] last;boolean lastPower;
        // Non-null while the selected effect is one of the 19 ported ones --
        // LedMath.frame handles the original five. Rebuilt by restart().
        LedRichEffect rich;
        Side(String light,String suffix,int index){this.light=light;this.suffix=suffix;this.index=index;}
        String key(String base){return base+suffix;}
    }
    private final Side[] sides={new Side("panel","",0),new Side("panel_left","Left",1)};
    private long testStart=-1;
    private boolean loaded;

    public void start(PluginHost host, Map<String,Object> settings) {
        this.host=host;
        requireDevice(settings);
        alive.set(true);
        worker=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"rockchip-led");t.setDaemon(true);return t;});
        configure(settings);
        worker.scheduleWithFixedDelay(() -> safe(this::frame),0,60,TimeUnit.MILLISECONDS);
    }
    public void configure(Map<String,Object> values) {
        Map<String,Object> copy=new HashMap<>(values);
        for(Map.Entry<String,Object> entry:SIDE_DEFAULTS.entrySet())copy.putIfAbsent(entry.getKey(),entry.getValue());
        submit(() -> {
            boolean recheck=transport==null || !Objects.equals(settings.get("simulation"),copy.get("simulation")) || !Objects.equals(settings.get("allowRoot"),copy.get("allowRoot"));
            settings=copy;testStart=-1;
            for(Side side:sides)restart(side);
            if (recheck) detect();
            frame();publish();
        });
    }
    /** Restarts a side's effect clock and rebuilds its stateful effect for the current settings. */
    private void restart(Side side) {
        side.effectStart=System.nanoTime();side.last=null;
        Supplier<LedRichEffect> supplier=LedEffectRegistry.RICH_EFFECTS.get(settings.get(side.key("effect")));
        side.rich=supplier!=null?supplier.get():null;
    }
    public void execute(String command,Map<String,Object> args) {
        submit(() -> {
            switch(command) {
                case "detect":detect();frame();publish();break;
                case "test":
                    if (transport==null) {status("No LED access. Check hardware access first.",true);return;}
                    testStart=System.nanoTime();for(Side side:sides)side.last=null;break;
                case "off":for(Side side:sides)settings.put(side.key("power"),false);testStart=-1;frame();publish();host.saveSettings(settings);break;
                default:throw new IllegalArgumentException("Unknown LED command");
            }
        });
    }
    public void onEvent(String event,Map<String,Object> payload) {
        Side found=null;
        for(Side candidate:sides)if(("light."+candidate.light).equals(event))found=candidate;
        if(found==null)return;
        final Side side=found;
        Map<String,Object> values=new HashMap<>(payload);
        submit(() -> {
            if (transport==null) return;
            if (values.get("on") instanceof Boolean) settings.put(side.key("power"),values.get("on"));
            if (values.containsKey("brightness")) settings.put(side.key("brightness"),Math.round(unit(values.get("brightness"))*100));
            int[] rgb=LedMath.color((String)settings.get(side.key("color")));
            String[] keys={"red","green","blue"};
            for(int i=0;i<3;i++) if(values.containsKey(keys[i])) rgb[i]=(int)Math.round(unit(values.get(keys[i]))*255);
            settings.put(side.key("color"),String.format(Locale.ROOT,"#%02X%02X%02X",rgb[0],rgb[1],rgb[2]));
            if(values.containsKey("effect")) {
                String effect=String.valueOf(values.get("effect"));
                if(!Arrays.asList(EFFECTS).contains(effect)) throw new IllegalArgumentException("Unknown LED effect");
                settings.put(side.key("effect"),effect);
            }
            testStart=-1;restart(side);
            frame();publish();host.saveSettings(settings);
        });
    }
    /**
     * Refuse to start at all on a panel this plugin cannot drive.
     *
     * Kiosk Satellite treats a plugin as enabled only when start() returns:
     * PluginBridge.enable writes enabled=true immediately after the call and
     * routes any throw through fail(), which writes enabled=false and keeps
     * the message as the plugin's error. detect() runs on the worker thread
     * instead, so a panel with no LED used to end up with this plugin
     * switched on, re-probing hardware that will never appear and offering
     * settings that can never do anything. Probing here, synchronously and
     * before any state is built, converts that into what it actually is:
     * this plugin cannot be enabled on this panel.
     *
     * Runs before alive/worker are set up, so a throw leaves nothing to
     * unwind.
     *
     * The condition is "cannot open the device", NOT "the node is missing".
     * A truly absent node does report ENOENT to an unprivileged app -- an
     * earlier revision of this comment claimed SELinux masks that as EACCES,
     * which is wrong, and the errno 13 that seemed to prove it came from a
     * stray file a diagnostic session had left at /dev/ledjni.
     *
     * The wider condition earns its place on its own: a node that exists but
     * will not open, on a panel with no root fallback, cannot drive an LED
     * either, and keying on ENOENT alone would enable there and merely
     * complain. So any probe failure refuses, unless something might still
     * rescue it.
     *
     * Three deliberate exemptions.
     *
     * Simulation mode never opens the device, so it stays enableable
     * anywhere -- that is the whole point of it.
     *
     * Root fallback, when armed, defers to detect(): the root helper may
     * well reach a node this process cannot, and asking it here would mean
     * blocking the host's enable call behind a root-manager prompt that can
     * sit unanswered for minutes. detect() already does that wait properly,
     * with a status to explain itself.
     *
     * A native library that will not load leaves us unable to probe at all,
     * which makes the question unanswerable rather than answered no; that
     * also falls through to detect() and reports as it always did.
     *
     * Both escape hatches named in the message are reachable while the
     * plugin is off -- its settings page stays open, saying "Enable this
     * plugin from its entry row to run it" -- so refusing here cannot strand
     * anyone outside the settings that would fix it. Verified on a panel.
     */
    private void requireDevice(Map<String,Object> settings) {
        if(Boolean.TRUE.equals(settings.get("simulation")))return;
        if(Boolean.TRUE.equals(settings.get("allowRoot")))return;
        try {
            if(!loaded){NativeLed.load(host.nativeLibraryPath("rockchip_led"));loaded=true;}
        } catch(Throwable unavailable){return;}
        int result=NativeLed.probe();
        if(result==0)return;
        throw new IllegalStateException(
            (result==ENOENT||result==ENODEV
                ?"This panel has no /dev/ledjni device, so there is no LED for this plugin to control. "
                :error(result)+" ")+
            "A Rockchip chipset alone does not imply LED support. Turn on Root fallback below if this "+
            "panel is rooted, or Simulation mode to enable the plugin without hardware -- both stay "+
            "reachable while the plugin is off.");
    }
    private static double unit(Object value) {
        if(!(value instanceof Number)) throw new IllegalArgumentException("Expected a light number");
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number<0||number>1)throw new IllegalArgumentException("Light value is outside 0 to 1");
        return number;
    }
    private void detect() throws Exception {
        closeTransport();removeLights();
        if(Boolean.TRUE.equals(settings.get("simulation"))) transport=new LedTransport.Simulation();
        else {
            if(!loaded){NativeLed.load(host.nativeLibraryPath("rockchip_led"));loaded=true;}
            LedTransport direct=new LedTransport.Direct();
            int result=direct.probe();
            if(result==0)transport=direct;
            // Reachable when the node disappears under a running plugin (a
            // driver unload), or when start() could not probe because the
            // native library would not load. A fresh enable is refused by
            // requireDevice long before this.
            else if(result==ENOENT || result==ENODEV) {status("This panel has no accessible /dev/ledjni device. Rockchip chipset alone does not imply LED support.",true);return;}
            else if(Boolean.TRUE.equals(settings.get("allowRoot"))) {
                status("Waiting for root access. Approve Kiosk Satellite in your root manager if prompted.",false);
                LedTransport.Root root=new LedTransport.Root(host.packagePath(),host.nativeLibraryPath("rockchip_led"));
                try {result=root.probe();if(result!=0)throw new IllegalStateException(error(result));transport=root;}
                catch(Exception e){root.close();throw e;}
            } else {status(error(result)+" Enable root fallback only if this panel is rooted.",true);return;}
        }
        for(Side side:sides){side.last=null;side.lastPower=true;}status(transport.label(),false);
    }
    private void frame() throws Exception {
        LedTransport device=transport;
        if(device==null || !alive.get() || settings.isEmpty())return;
        int testStep=-1;
        if(testStart>=0) {
            long test=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-testStart);
            if(test<3000)testStep=(int)(test/1000);
            else{testStart=-1;for(Side side:sides)side.last=null;}
        }
        for(Side side:sides)drive(device,side,testStep);
    }
    /** The hardware bar this side talks to; "Swap left and right" exchanges them. */
    private int bar(Side side){return Boolean.TRUE.equals(settings.get("swapSides"))?1-side.index:side.index;}
    private void drive(LedTransport device,Side side,int testStep) throws Exception {
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-side.effectStart);
        boolean power=Boolean.TRUE.equals(settings.get(side.key("power")));
        int[] baseColor=LedMath.color((String)settings.get(side.key("color")));
        int[] rgb=side.rich!=null
            ?side.rich.tick(elapsed,baseColor)
            :LedMath.frame((String)settings.get(side.key("effect")),elapsed,((Number)settings.get("period")).doubleValue(),baseColor);
        if(testStep>=0){power=true;rgb=new int[3];rgb[testStep]=255;}
        if(!power){if(side.lastPower){check(device.set(bar(side),-1,0));side.lastPower=false;side.last=null;}return;}
        int limit=Math.min(MAX_LEVEL,((Number)settings.get("maxDrive")).intValue());
        double brightness=((Number)settings.get(side.key("brightness"))).doubleValue();
        int channel=LedMath.channel(rgb);
        int level=channel<0?0:LedMath.scale(Math.max(rgb[0],Math.max(rgb[1],rgb[2])),brightness,limit);
        if(level==0)channel=-1;
        int[] drive={channel,level};
        if(!side.lastPower||!Arrays.equals(drive,side.last)){check(device.set(bar(side),channel,level));side.last=drive;side.lastPower=true;}
    }
    private void publish(){for(Side side:sides)publish(side);}
    private void publish(Side side){
        if(transport==null||!Boolean.TRUE.equals(settings.get("exposeLight"))){host.removeLight(side.light);return;}
        int[] rgb=LedMath.color((String)settings.get(side.key("color")));Map<String,Object> state=new HashMap<>();
        state.put("on",settings.get(side.key("power")));state.put("brightness",((Number)settings.get(side.key("brightness"))).doubleValue()/100);
        state.put("red",rgb[0]/255.0);state.put("green",rgb[1]/255.0);state.put("blue",rgb[2]/255.0);state.put("effect",settings.get(side.key("effect")));
        String name="Rockchip LED "+(side.index==0?"right":"left");
        host.publishLight(side.light,Boolean.TRUE.equals(settings.get("simulation"))?name+" (simulation)":name,ENTITY_EFFECTS,state);
    }
    private void removeLights(){for(Side side:sides)host.removeLight(side.light);}
    private void status(String text,boolean error){if(alive.get())host.status(text,error);}
    private static String error(int code){return "LED access failed (errno "+(-code)+"). "+(code==-13||code==-1?"The device permissions or SELinux policy denied access.":"Check the panel driver and its /dev/ledjni node.");}
    private static void check(int code){if(code!=0)throw new IllegalStateException(error(code));}
    private interface Task{void run() throws Exception;}
    private void safe(Task task){
        if(!alive.get())return;
        try{task.run();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Throwable e){closeTransport();if(alive.get()){removeLights();status(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(),true);}}
    }
    private void submit(Task task){if(alive.get())worker.execute(() -> safe(task));}
    private void closeTransport(){LedTransport old=transport;transport=null;if(old!=null){try{old.off();}catch(Exception ignored){}old.close();}}
    public void stop() throws Exception {
        alive.set(false);worker.shutdownNow();
        if(!worker.awaitTermination(1000,TimeUnit.MILLISECONDS)) {
            LedTransport old=transport;if(old instanceof LedTransport.Root)old.close();
            throw new IllegalStateException("LED worker did not stop");
        }
        closeTransport();
    }
}
