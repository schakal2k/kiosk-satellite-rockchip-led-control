// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchip;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.PluginHost;

public final class LedTest {
    private static final class Host implements PluginHost {
        volatile Map<String,Object> state;
        volatile Map<String,Object> leftState;
        volatile Map<String,Object> saved;
        volatile String status="";
        final AtomicInteger changes=new AtomicInteger();
        public void showWindow(String a,String b,String c){throw new AssertionError("No overlay required");}
        public void hideWindow(){}
        public void log(String value){}
        public void status(String value,boolean error){status=value;changes.incrementAndGet();}
        public void saveSettings(Map<String,Object> value){saved=new HashMap<>(value);}
        public void publishLight(String key,String name,String[] effects,Map<String,Object> value){
            assert key.equals("panel")||key.equals("panel_left");assert name.contains("simulation");
            assert name.contains(key.equals("panel")?"right":"left");
            // The real host (PluginBridge.kt) rejects more than 24 with an
            // unhelpful "Failed requirement." — this mock enforces the same
            // cap so a future effect addition fails here, locally, instead
            // of only on a real device. See ENTITY_EFFECTS in
            // RockchipLedPlugin for why this is 24, not 25.
            assert effects.length<=24:"publishLight effects exceeds the host's 24-effect cap: "+effects.length;
            assert effects.length==24;
            assert !Arrays.asList(effects).contains("None"):"\"None\" should be omitted from the published list, not counted against the cap";
            if(key.equals("panel"))state=new HashMap<>(value);else leftState=new HashMap<>(value);changes.incrementAndGet();
        }
        public void removeLight(String key){if(key.equals("panel"))state=null;else leftState=null;changes.incrementAndGet();}
    }
    private static Map<String,Object> defaults(){
        Map<String,Object> s=new HashMap<>();
        s.put("power",false);s.put("color","#00A8FF");s.put("brightness",50);s.put("effect","None");s.put("period",2000);
        s.put("powerLeft",false);s.put("colorLeft","#00A8FF");s.put("brightnessLeft",50);s.put("effectLeft","None");s.put("swapSides",false);
        s.put("exposeLight",true);s.put("maxDrive",15);s.put("allowRoot",false);s.put("simulation",true);return s;
    }
    private interface Condition{boolean done();}
    private static void waitFor(Condition condition)throws Exception{
        long end=System.nanoTime()+2_000_000_000L;
        while(!condition.done() && System.nanoTime()<end)Thread.sleep(10);
        if(!condition.done())throw new AssertionError("Timed out");
    }
    public static void main(String[] args)throws Exception{
        for(int channel=0;channel<=255;channel++){
            assert LedMath.scale(channel,100,15)>=0 && LedMath.scale(channel,100,15)<=15;
            assert LedMath.scale(channel,0,15)==0;
        }
        assert LedMath.scale(255,100,15)==15;
        assert LedMath.scale(255,100,255)==255;
        assert Arrays.equals(LedMath.color("#12aB00"),new int[]{18,171,0});
        for(String effect:new String[]{"None","Pulse","Blink","Rainbow","Candle","Random"}){
            for(long elapsed=0;elapsed<10000;elapsed+=61){
                for(int c:LedMath.frame(effect,elapsed,2000,new int[]{255,190,80}))assert c>=0&&c<=255;
            }
        }
        // The 19 ported rich effects: fresh instance per name (matching how
        // RockchipLedPlugin.configure() creates one), ticked across enough
        // elapsed time to cover every state-machine transition and a couple
        // of full periods for the slower ones (Sunrise/Sunset at 300s,
        // Wake-Up Alarm's one-shot 600s ramp), checking every channel stays
        // in range throughout.
        for(Map.Entry<String,java.util.function.Supplier<LedRichEffect>> entry:LedEffectRegistry.RICH_EFFECTS.entrySet()){
            LedRichEffect rich=entry.getValue().get();
            for(long elapsed=0;elapsed<650_000;elapsed+=61){
                for(int c:rich.tick(elapsed,new int[]{255,190,80})){
                    assert c>=0&&c<=255:entry.getKey()+" produced out-of-range channel "+c+" at "+elapsed+"ms";
                }
            }
        }
        // Palette: one of seven colours per bar.
        assert LedMath.channel(new int[]{255,0,0})==0 && LedMath.channel(new int[]{0,255,0})==1 && LedMath.channel(new int[]{0,0,255})==2;
        assert LedMath.channel(new int[]{0,255,255})==3 && LedMath.channel(new int[]{255,0,255})==4 && LedMath.channel(new int[]{255,255,0})==5;
        assert LedMath.channel(new int[]{255,255,255})==6 && LedMath.channel(new int[]{0,0,0})==-1;
        assert LedMath.channel(new int[]{255,100,0})==0 && LedMath.channel(new int[]{255,128,0})==5 && LedMath.channel(new int[]{10,5,0})==5;
        assert LedTransport.Root.quote("a'b").equals("'a'\\''b'");
        Host host=new Host();RockchipLedPlugin plugin=new RockchipLedPlugin();
        plugin.start(host,defaults());waitFor(() -> host.state!=null&&host.leftState!=null);
        assert host.status.startsWith("Simulation");assert host.state.get("on").equals(false);
        Map<String,Object> command=new HashMap<>();command.put("on",true);command.put("brightness",0.3);
        command.put("red",1.0);command.put("green",0.0);command.put("blue",0.0);command.put("effect","Pulse");
        plugin.onEvent("light.panel",command);waitFor(() -> host.saved!=null);
        assert host.saved.get("color").equals("#FF0000");assert ((Number)host.saved.get("brightness")).intValue()==30;
        assert host.saved.get("effect").equals("Pulse");assert host.state.get("on").equals(true);
        // The left bar is an independent light: commanding it leaves the right bar's settings alone.
        assert host.saved.get("colorLeft").equals("#00A8FF") && host.saved.get("effectLeft").equals("None");
        host.saved=null;
        Map<String,Object> leftCommand=new HashMap<>();leftCommand.put("on",true);leftCommand.put("brightness",0.8);
        leftCommand.put("red",0.0);leftCommand.put("green",1.0);leftCommand.put("blue",0.0);
        plugin.onEvent("light.panel_left",leftCommand);waitFor(() -> host.saved!=null);
        assert host.saved.get("colorLeft").equals("#00FF00");assert ((Number)host.saved.get("brightnessLeft")).intValue()==80;
        assert host.saved.get("powerLeft").equals(true);assert host.saved.get("color").equals("#FF0000");
        assert host.leftState.get("on").equals(true);
        host.saved=null;plugin.execute("off",Collections.emptyMap());waitFor(() -> host.saved!=null);
        assert host.saved.get("power").equals(false);assert host.saved.get("powerLeft").equals(false);
        Map<String,Object> settings=defaults();settings.put("exposeLight",false);plugin.configure(settings);waitFor(() -> host.state==null&&host.leftState==null);
        plugin.stop();int count=host.changes.get();Thread.sleep(150);assert host.changes.get()==count;
        // requireDevice's deferral exemption. A host that cannot hand over a
        // native library path (no SDK 1 support, wrong ABI, missing file)
        // leaves the plugin unable to probe, so "is /dev/ledjni there?" has
        // no answer and start() must NOT refuse -- it falls through to
        // detect() and reports the way it always did. Without the catch in
        // requireDevice this start throws UnsupportedOperationException out
        // of the SDK's default nativeLibraryPath and the plugin becomes
        // unenableable everywhere, including on panels whose LED works.
        Host bare=new Host();RockchipLedPlugin deferred=new RockchipLedPlugin();
        Map<String,Object> real=defaults();real.put("simulation",false);real.put("exposeLight",false);
        deferred.start(bare,real);
        waitFor(() -> !bare.status.isEmpty());
        deferred.stop();
        System.out.println("PASS: a host without a native library path defers to detect() instead of blocking enable ("+bare.status+")");
        // The root-fallback exemption. With allowRoot armed, requireDevice
        // must return before it touches the native library at all: the root
        // helper may reach a node this process cannot, and probing here
        // would block the host's enable call behind a root-manager prompt.
        // Asserted by the absence of a throw on a host that cannot supply a
        // library path -- reaching the load would be the bug.
        Host rooted=new Host();RockchipLedPlugin viaRoot=new RockchipLedPlugin();
        Map<String,Object> rootSettings=defaults();
        rootSettings.put("simulation",false);rootSettings.put("allowRoot",true);rootSettings.put("exposeLight",false);
        viaRoot.start(rooted,rootSettings);
        viaRoot.stop();
        System.out.println("PASS: root fallback defers the probe to detect() rather than refusing the enable");
        for(Thread thread:Thread.getAllStackTraces().keySet())assert !thread.isAlive()||!thread.getName().equals("rockchip-led");
        System.out.println("PASS: channel limits, color math, seven-colour palette, two independent bars, 19 rich effects, 24-effect entity cap, root argument quoting, simulation, HA commands, settings persistence, entity removal and clean worker shutdown.");
    }
}
