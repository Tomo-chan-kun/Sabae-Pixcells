package jp.sabaepixcells.earth;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity implements LocationListener {
    private static final String PROTOCOL="sabae-pixcells-gps/1";
    private static final String VERSION="v001";
    private static final int REQ_LOCATION=1001;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final GeoData geo=new GeoData();
    private volatile boolean geoReady=false;
    private LocationManager locationManager;
    private SharedPreferences prefs;

    private FrameLayout lightLayer;
    private TextView topStatus, bigMessage, bottomInfo;
    private Button startButton, gearButton, centerButton;
    private LinearLayout settingsPanel;
    private EditText hostEdit, portEdit;
    private CheckBox autoDiscoverCheck, devCheck;

    private volatile SimpleWebSocket ws;
    private volatile boolean connected=false, connecting=false;
    private boolean activeRequested=false;
    private boolean rangeKnown=false, insideRange=false, rangeDisconnected=false;
    private int outsideCount=0, insideCount=0;
    private boolean debugCenter=false;
    private Location lastLocation;
    private double effectiveLat, effectiveLon;
    private float effectiveAccuracy=9999f;
    private GeoData.Point effectivePoint;
    private long checkIntervalMs=1000;
    private long seq=0;
    private int lastRevision=0;
    private double serverOffsetMs=0.0;
    private boolean haveClockSync=false;
    private long lastConnectAttempt=0;
    private final String deviceId="EARTH-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);

    private Block currentBlock, nextBlock;
    private String sunEffectName="—";
    private String sunSegment="—";

    private static final class Block {
        String effectId; long startMs,endMs; double effectElapsedStartSec; int revision;
        Block(String e,long s,long end,double elapsed,int rev){effectId=e;startMs=s;endMs=end;effectElapsedStartSec=elapsed;revision=rev;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp=getWindow().getAttributes();
        lp.screenBrightness=1.0f;
        getWindow().setAttributes(lp);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        prefs=getSharedPreferences("earth",MODE_PRIVATE);
        locationManager=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
        buildUi();
        bigMessage.setText("地図データ読込中…");
        new Thread(()->{
            try{
                geo.load(this);
                geoReady=true;
                runOnUiThread(()->{
                    bigMessage.setText("演出開始を押してください");
                    bottomInfo.setText("西山公園 GPS 10,000ポイント読込済み");
                });
            } catch(Exception e){
                runOnUiThread(()->bigMessage.setText("地図データ読込エラー\n"+e.getMessage()));
            }
        },"GeoLoad").start();
        main.post(renderLoop);
        main.post(pollLoop);
    }

    private TextView text(String s,int sp){
        TextView v=new TextView(this);
        v.setText(s);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setPadding(12,8,12,8);
        return v;
    }

    private void buildUi(){
        FrameLayout root=new FrameLayout(this);
        setContentView(root);

        lightLayer=new FrameLayout(this);
        lightLayer.setBackgroundColor(Color.BLACK);
        root.addView(lightLayer,new FrameLayout.LayoutParams(-1,-1));

        topStatus=text("Sun: 未接続",13);
        topStatus.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
        tp.setMargins(12,12,70,0);
        root.addView(topStatus,tp);

        bigMessage=text("",27);
        bigMessage.setGravity(Gravity.CENTER);
        bigMessage.setBackgroundColor(0x33000000);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);
        bp.setMargins(24,0,24,0);
        root.addView(bigMessage,bp);

        bottomInfo=text(deviceId,11);
        bottomInfo.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        ip.setMargins(12,0,12,12);
        root.addView(bottomInfo,ip);

        gearButton=new Button(this);
        gearButton.setText("⚙");
        gearButton.setOnClickListener(v->showSettings(true));
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(58,58,Gravity.TOP|Gravity.RIGHT);
        gp.setMargins(0,8,8,0);
        root.addView(gearButton,gp);

        startButton=new Button(this);
        startButton.setText("演出開始");
        startButton.setTextSize(20);
        startButton.setOnClickListener(v->startEarth());
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        sp.setMargins(0,0,0,70);
        root.addView(startButton,sp);

        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(0xff0d1117);
        settingsPanel=new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(20,24,20,24);
        scroll.addView(settingsPanel,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        scroll.setVisibility(View.GONE);
        settingsPanel.setTag(scroll);

        settingsPanel.addView(text("Sabae Pixcells Earth "+VERSION+"\nGPS発光用",22));

        autoDiscoverCheck=new CheckBox(this);
        autoDiscoverCheck.setText("Sunを自動検出");
        autoDiscoverCheck.setTextColor(Color.WHITE);
        autoDiscoverCheck.setChecked(prefs.getBoolean("auto",true));
        settingsPanel.addView(autoDiscoverCheck);

        settingsPanel.addView(text("自動検出できない場合のSun IP",12));
        hostEdit=new EditText(this);
        hostEdit.setTextColor(Color.WHITE);
        hostEdit.setHintTextColor(0xff888888);
        hostEdit.setHint("192.168.1.100");
        hostEdit.setText(prefs.getString("host","192.168.1.100"));
        settingsPanel.addView(hostEdit,new LinearLayout.LayoutParams(-1,-2));

        settingsPanel.addView(text("WebSocket Port",12));
        portEdit=new EditText(this);
        portEdit.setTextColor(Color.WHITE);
        portEdit.setInputType(2);
        portEdit.setText(prefs.getString("port","8765"));
        settingsPanel.addView(portEdit,new LinearLayout.LayoutParams(-1,-2));

        devCheck=new CheckBox(this);
        devCheck.setText("開発用テスト機能（開発段階のみ）");
        devCheck.setTextColor(Color.WHITE);
        settingsPanel.addView(devCheck);

        centerButton=new Button(this);
        centerButton.setText("芝生公園の中心座標を使用");
        centerButton.setVisibility(View.GONE);
        settingsPanel.addView(centerButton);

        devCheck.setOnCheckedChangeListener((bttn,checked)->{
            centerButton.setVisibility(checked?View.VISIBLE:View.GONE);
            if(!checked&&debugCenter){
                debugCenter=false;
                centerButton.setText("芝生公園の中心座標を使用");
                reprocessLocation();
            }
        });

        centerButton.setOnClickListener(v->{
            debugCenter=!debugCenter;
            centerButton.setText(debugCenter?"中心座標を使用中（解除）":"芝生公園の中心座標を使用");
            reprocessLocation();
        });

        Button close=new Button(this);
        close.setText("設定を保存して閉じる");
        settingsPanel.addView(close);
        close.setOnClickListener(v->{
            prefs.edit()
                    .putBoolean("auto",autoDiscoverCheck.isChecked())
                    .putString("host",hostEdit.getText().toString().trim())
                    .putString("port",portEdit.getText().toString().trim())
                    .apply();
            ((ScrollView)settingsPanel.getTag()).setVisibility(View.GONE);
        });
    }

    private void showSettings(boolean show){
        ((ScrollView)settingsPanel.getTag()).setVisibility(show?View.VISIBLE:View.GONE);
    }

    private void startEarth(){
        if(!geoReady){
            bigMessage.setText("地図データ読込中…");
            return;
        }
        activeRequested=true;
        startButton.setVisibility(View.GONE);
        bigMessage.setText("GPS取得中…");
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION);
        } else {
            startLocationUpdates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_LOCATION){
            if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED){
                startLocationUpdates();
            } else {
                bigMessage.setText("GPS権限が必要です");
            }
        }
    }

    private void startLocationUpdates(){
        try{
            locationManager.removeUpdates(this);
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        Math.max(200,checkIntervalMs),
                        0f,
                        this,
                        Looper.getMainLooper());
                Location l=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if(l!=null) onLocationChanged(l);
            }
        } catch(Exception e){
            bigMessage.setText("GPS開始エラー\n"+e.getMessage());
        }
    }

    private void restartLocationUpdates(){
        if(activeRequested&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            startLocationUpdates();
        }
    }

    @Override public void onLocationChanged(Location location){
        lastLocation=location;
        reprocessLocation();
    }

    @Override public void onProviderEnabled(String provider){}

    @Override public void onProviderDisabled(String provider){
        if(LocationManager.GPS_PROVIDER.equals(provider)){
            bigMessage.setText("GPSが無効です");
        }
    }

    private void reprocessLocation(){
        if(!activeRequested||!geoReady) return;

        if(debugCenter){
            effectiveLat=geo.centerLat;
            effectiveLon=geo.centerLon;
            effectiveAccuracy=0.5f;
        } else if(lastLocation!=null){
            effectiveLat=lastLocation.getLatitude();
            effectiveLon=lastLocation.getLongitude();
            effectiveAccuracy=lastLocation.hasAccuracy()?lastLocation.getAccuracy():9999f;
        } else {
            bigMessage.setText("GPS取得中…");
            return;
        }

        effectivePoint=geo.nearest(effectiveLat,effectiveLon);
        boolean inside=geo.contains(effectiveLat,effectiveLon);
        rangeKnown=true;

        if(connected){
            if(inside){
                insideRange=true;
                outsideCount=0;
            } else {
                outsideCount++;
                if(outsideCount>=3){
                    insideRange=false;
                    rangeDisconnected=true;
                    disconnectSun();
                    bigMessage.setText("演出範囲外です");
                }
            }
        } else {
            insideRange=inside;
            if(inside){
                insideCount++;
                outsideCount=0;
                int need=rangeDisconnected?3:1;
                if(insideCount>=need){
                    rangeDisconnected=false;
                    connectSun();
                }
            } else {
                insideCount=0;
                disconnectSun();
                bigMessage.setText("演出範囲外です");
                topStatus.setText("Sun: 未接続（範囲外）");
                lightLayer.setBackgroundColor(Color.BLACK);
            }
        }
        updateBottom();
    }

    private void connectSun(){
        if(!activeRequested||!insideRange||connected||connecting) return;
        long n=System.currentTimeMillis();
        if(n-lastConnectAttempt<1200) return;
        lastConnectAttempt=n;
        connecting=true;
        topStatus.setText("Sun: 接続先検索中…");

        final boolean auto=autoDiscoverCheck.isChecked();
        final String manual=hostEdit.getText().toString().trim();
        final int manualPort=parsePort(portEdit.getText().toString());

        new Thread(()->{
            String host=manual;
            int port=manualPort;
            if(auto){
                try{
                    SunDiscovery.Result r=SunDiscovery.discover(8764,1200);
                    if(r!=null){
                        host=r.host;
                        port=r.port;
                    }
                } catch(Exception ignored){}
            }

            if(host==null||host.isEmpty()){
                connecting=false;
                runOnUiThread(()->topStatus.setText("Sun: 自動検出失敗 / IP未設定"));
                return;
            }

            final String h=host;
            final int p=port;
            final SimpleWebSocket[] holder=new SimpleWebSocket[1];

            holder[0]=new SimpleWebSocket(new SimpleWebSocket.Listener(){
                @Override public void onOpen(){
                    ws=holder[0];
                    connected=true;
                    connecting=false;
                    sendHello();
                    runOnUiThread(()->{
                        topStatus.setText("Sun: 接続済み "+h+":"+p);
                        if(nextBlock==null&&currentBlock==null) bigMessage.setText("Sun情報取得中…");
                    });
                }

                @Override public void onText(String text){
                    runOnUiThread(()->handleSunState(text));
                }

                @Override public void onClosed(String reason){
                    connected=false;
                    connecting=false;
                    runOnUiThread(()->{
                        if(activeRequested&&insideRange&&currentBlock==null) bigMessage.setText("Sun再接続中…");
                        topStatus.setText("Sun: 未接続");
                    });
                }

                @Override public void onError(Exception e){
                    connected=false;
                    connecting=false;
                    runOnUiThread(()->{
                        if(activeRequested&&insideRange&&currentBlock==null) bigMessage.setText("Sun再接続中…");
                        topStatus.setText("Sun: 接続エラー");
                    });
                }
            });

            ws=holder[0];
            holder[0].connect(h,p,"/device");
        },"SunConnect").start();
    }

    private int parsePort(String s){
        try{return Integer.parseInt(s.trim());}
        catch(Exception e){return 8765;}
    }

    private JSONObject basePacket(String type)throws Exception{
        JSONObject j=new JSONObject();
        j.put("type",type);
        j.put("protocol",PROTOCOL);
        j.put("source","earth");
        j.put("app","Earth");
        j.put("appVersion",VERSION);
        j.put("deviceId",deviceId);
        j.put("lat",effectiveLat);
        j.put("lon",effectiveLon);
        j.put("accuracyM",effectiveAccuracy);
        if(effectivePoint!=null) j.put("pointId",effectivePoint.id);
        j.put("debugCenter",debugCenter);
        return j;
    }

    private void sendHello(){
        try{
            JSONObject j=basePacket("hello");
            j.put("clientSendMs",System.currentTimeMillis());
            sendJson(j);
        } catch(Exception ignored){}
    }

    private void sendStatus(){
        if(!connected||ws==null||!insideRange) return;
        try{
            JSONObject j=basePacket("status");
            j.put("seq",++seq);
            j.put("clientSendMs",System.currentTimeMillis());
            j.put("lastRevision",lastRevision);
            sendJson(j);
        } catch(Exception ignored){}
    }

    private void sendJson(JSONObject j){
        SimpleWebSocket s=ws;
        if(s==null||!s.isOpen()) return;
        new Thread(()->{
            try{s.sendText(j.toString());}
            catch(Exception ignored){}
        },"EarthSend").start();
    }

    private void disconnectSun(){
        SimpleWebSocket s=ws;
        ws=null;
        connected=false;
        connecting=false;
        if(s!=null) s.close();
    }

    private void handleSunState(String raw){
        try{
            JSONObject m=new JSONObject(raw);
            if(!"sun-state".equals(m.optString("type"))||!PROTOCOL.equals(m.optString("protocol"))) return;

            long recv=System.currentTimeMillis();
            long server=m.optLong("serverTimeMs",recv);
            if(m.has("echoClientSendMs")){
                long sent=m.optLong("echoClientSendMs",recv);
                double candidate=server-((sent+recv)/2.0);
                serverOffsetMs=haveClockSync?(serverOffsetMs*0.8+candidate*0.2):candidate;
                haveClockSync=true;
            } else if(!haveClockSync){
                serverOffsetMs=server-recv;
            }

            long newInterval=Math.max(200,Math.min(10000,m.optLong("checkIntervalMs",1000)));
            if(Math.abs(newInterval-checkIntervalMs)>=50){
                checkIntervalMs=newInterval;
                restartLocationUpdates();
            }

            lastRevision=m.optInt("configRevision",lastRevision);
            sunEffectName=m.optString("effectName","—");

            if(m.has("currentSegmentStartSec")&&!m.isNull("currentSegmentStartSec")){
                sunSegment=String.format(
                        Locale.JAPAN,
                        "%.1f～%.1f秒",
                        m.optDouble("currentSegmentStartSec"),
                        m.optDouble("currentSegmentEndSec"));
            } else {
                sunSegment=m.optString("phase","—");
            }

            if(!activeRequested) return;

            if(!m.optBoolean("running",false)){
                currentBlock=null;
                nextBlock=null;
                lightLayer.setBackgroundColor(Color.BLACK);
                bigMessage.setText("Sun停止中");
                return;
            }

            long sn=serverNow();
            String phase=m.optString("phase");

            if("running".equals(phase)
                    &&currentBlock!=null
                    &&"immediate".equals(m.optString("lastApplyMode"))
                    &&m.optInt("activeRevision")!=currentBlock.revision){
                long s=m.optLong("currentSegmentStartMs",sn);
                long e=m.optLong("currentSegmentEndMs",sn);
                double es=m.optDouble("currentSegmentStartSec",0);
                currentBlock=new Block(
                        m.optString("effectId","wave"),
                        s,e,es,
                        m.optInt("activeRevision"));
                nextBlock=null;
            }

            long nb=m.optLong("nextBoundaryMs",0);
            if(nb>0){
                String ne=m.optString("nextEffectId",m.optString("effectId","wave"));
                double nel=m.optDouble("nextEffectElapsedStartSec",0);
                int nr=m.optInt("nextActiveRevision",m.optInt("activeRevision"));
                long nd=m.optLong("segmentDurationMs",5000);
                if(m.has("pendingApplyAtMs")
                        &&!m.isNull("pendingApplyAtMs")
                        &&m.optLong("pendingApplyAtMs")==nb
                        &&!m.isNull("pendingSegmentDurationMs")){
                    nd=m.optLong("pendingSegmentDurationMs",nd);
                }
                Block b=new Block(ne,nb,nb+Math.max(500,nd),nel,nr);
                if(currentBlock==null||b.startMs>=currentBlock.endMs-5) nextBlock=b;
            }

            topStatus.setText("Sun: 接続済み / "+sunEffectName+" "+sunSegment+" / rev "+lastRevision);

        } catch(Exception ignored){}
    }

    private long serverNow(){
        return System.currentTimeMillis()+Math.round(serverOffsetMs);
    }

    private String effectName(String id){
        if("wave".equals(id)) return "波（奥→手前）";
        if("ripple".equals(id)) return "波紋";
        if("fireworks".equals(id)) return "花火";
        return id;
    }

    private final Runnable renderLoop=new Runnable(){
        @Override public void run(){
            try{renderFrame();}
            finally{main.postDelayed(this,50);}
        }
    };

    private void renderFrame(){
        if(!activeRequested){
            lightLayer.setBackgroundColor(Color.BLACK);
            return;
        }

        if(!rangeKnown){
            lightLayer.setBackgroundColor(Color.BLACK);
            bigMessage.setText("GPS取得中…");
            return;
        }

        if(!insideRange){
            lightLayer.setBackgroundColor(Color.BLACK);
            bigMessage.setText("演出範囲外です");
            return;
        }

        long sn=serverNow();

        if(currentBlock!=null&&sn>=currentBlock.endMs) currentBlock=null;

        if(currentBlock==null&&nextBlock!=null&&sn>=nextBlock.startMs){
            currentBlock=nextBlock;
            nextBlock=null;
        }

        if(currentBlock!=null
                &&sn>=currentBlock.startMs
                &&sn<currentBlock.endMs
                &&effectivePoint!=null){
            double elapsed=currentBlock.effectElapsedStartSec+(sn-currentBlock.startMs)/1000.0;
            int c=Effects.color(
                    currentBlock.effectId,
                    geo,
                    effectiveLat,
                    effectiveLon,
                    elapsed,
                    effectivePoint.id);
            lightLayer.setBackgroundColor(c);
            bigMessage.setText("");
            topStatus.setText("Sun: 接続"+(connected?"済み":"断")+" / 表示: "+effectName(currentBlock.effectId));
            return;
        }

        lightLayer.setBackgroundColor(Color.BLACK);

        if(nextBlock!=null&&sn<nextBlock.startMs){
            double remain=(nextBlock.startMs-sn)/1000.0;
            bigMessage.setText(String.format(Locale.JAPAN,"演出開始まで %.1f 秒",Math.max(0,remain)));
        } else if(!connected){
            bigMessage.setText("Sun再接続中…");
        } else {
            bigMessage.setText("次区間待機");
        }
    }

    private final Runnable pollLoop=new Runnable(){
        @Override public void run(){
            if(activeRequested&&rangeKnown&&insideRange){
                if(connected) sendStatus();
                else connectSun();
            }
            main.postDelayed(this,Math.max(200,checkIntervalMs));
        }
    };

    private void updateBottom(){
        String gps=String.format(
                Locale.JAPAN,
                "GPS %.7f, %.7f / 精度 %.1fm / Point %s",
                effectiveLat,
                effectiveLon,
                effectiveAccuracy,
                effectivePoint==null?"—":Integer.toString(effectivePoint.id));
        if(debugCenter) gps+=" / 開発モード：中心座標を使用中";
        bottomInfo.setText(gps);
    }

    @Override protected void onDestroy(){
        main.removeCallbacksAndMessages(null);
        try{locationManager.removeUpdates(this);}catch(Exception ignored){}
        disconnectSun();
        super.onDestroy();
    }
}
