package jp.sabaepixcells.earth;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public final class SunDiscovery {
    public static final class Result { public final String host; public final int port; Result(String h,int p){host=h;port=p;} }
    private SunDiscovery(){}
    public static Result discover(int discoveryPort,int timeoutMs)throws Exception{
        DatagramSocket s=new DatagramSocket();
        try{
            s.setBroadcast(true);s.setSoTimeout(timeoutMs);
            byte[]data="SABAE_PIXCELLS_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
            DatagramPacket p=new DatagramPacket(data,data.length,InetAddress.getByName("255.255.255.255"),discoveryPort);s.send(p);
            byte[]buf=new byte[2048];DatagramPacket r=new DatagramPacket(buf,buf.length);s.receive(r);
            String txt=new String(r.getData(),0,r.getLength(),StandardCharsets.UTF_8);JSONObject j=new JSONObject(txt);
            if(!"SABAE_PIXCELLS_SUN_V1".equals(j.optString("magic")))return null;
            return new Result(r.getAddress().getHostAddress(),j.optInt("wsPort",8765));
        }finally{s.close();}
    }
}
