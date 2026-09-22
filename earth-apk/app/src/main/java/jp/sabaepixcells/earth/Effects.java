package jp.sabaepixcells.earth;

import android.graphics.Color;

public final class Effects {
    private Effects() {}
    private static double clamp(double x,double a,double b){return x<a?a:(x>b?b:x);}
    private static int mix(int a,int b,double t){ t=clamp(t,0,1); return (int)Math.round(a+(b-a)*t); }
    private static int colorMix(int r0,int g0,int b0,int r1,int g1,int b1,double t){
        return Color.rgb(mix(r0,r1,t),mix(g0,g1,t),mix(b0,b1,t));
    }

    public static int color(String effectId, GeoData geo, double lat, double lon, double elapsedSec, int pointId) {
        if ("wave".equals(effectId)) {
            double u=geo.normEast(lon);
            double phase=(elapsedSec%5.0)/5.0;
            double crest=1.0-phase;
            double d=Math.abs(u-crest);
            d=Math.min(d,Math.min(Math.abs((u+1.0)-crest),Math.abs(u-(crest+1.0))));
            double intensity=Math.exp(-Math.pow(d/0.095,2));
            return colorMix(0,12,42,190,235,255,intensity);
        }
        if ("ripple".equals(effectId)) {
            double[] xy=geo.localMeters(lat,lon);
            double dist=Math.hypot(xy[0],xy[1]);
            double radius=(elapsedSec%4.0)/4.0*34.0;
            double ring=Math.exp(-Math.pow((dist-radius)/2.2,2));
            double radius2=Math.max(0,radius-8.0);
            ring=Math.max(ring,0.35*Math.exp(-Math.pow((dist-radius2)/2.5,2)));
            return colorMix(0,4,18,65,220,255,ring);
        }
        if ("fireworks".equals(effectId)) {
            double[][] centers={{-11,7},{8,10},{2,-5},{-6,-10},{13,-1},{-15,1},{5,4},{0,13}};
            int[][] colors={{255,80,55},{80,165,255},{255,205,55},{220,90,255},{80,255,175},{255,125,45},{245,245,255},{255,65,145}};
            double[] xy=geo.localMeters(lat,lon); double spacing=1.35;
            long kNow=(long)Math.floor(elapsedSec/spacing);
            double rr=0,gg=0,bb=0;
            for(int dk=0;dk<4;dk++){
                long k=kNow-dk; if(k<0)continue;
                double age=elapsedSec-k*spacing; if(age<0||age>1.9)continue;
                double[] c=centers[(int)(k%centers.length)]; int[] col=colors[(int)(k%colors.length)];
                double dist=Math.hypot(xy[0]-c[0],xy[1]-c[1]);
                double radius=4.0+age*17.5, width=1.6+age*0.9;
                double ring=Math.exp(-Math.pow((dist-radius)/width,2))*Math.max(0,1.0-age/2.0);
                long seed=(pointId*1103515245L+k*12345L)&0x7fffffffL;
                double s=seed/(double)0x7fffffffL;
                double sparkle=(s>0.91&&ring>0.18)?1.0:0.0;
                double amp=clamp(ring*1.15+sparkle*0.35,0,1);
                rr+=col[0]*amp; gg+=col[1]*amp; bb+=col[2]*amp;
            }
            return Color.rgb((int)clamp(rr,0,255),(int)clamp(gg,0,255),(int)clamp(bb,0,255));
        }
        return Color.BLACK;
    }
}
