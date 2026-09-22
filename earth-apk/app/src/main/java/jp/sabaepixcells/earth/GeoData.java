package jp.sabaepixcells.earth;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class GeoData {
    public static final class Point {
        public final int id;
        public final double lat;
        public final double lon;
        Point(int id, double lat, double lon) { this.id=id; this.lat=lat; this.lon=lon; }
    }

    private final List<double[]> boundary = new ArrayList<>();
    private final List<Point> points = new ArrayList<>();
    public double centerLat, centerLon, minLat, maxLat, minLon, maxLon;

    public void load(Context context) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open("map_boundary.json"), StandardCharsets.UTF_8))) {
            String line; while ((line=br.readLine())!=null) sb.append(line);
        }
        JSONObject root = new JSONObject(sb.toString());
        JSONObject c = root.getJSONObject("center");
        centerLat = c.getDouble("latitude"); centerLon = c.getDouble("longitude");
        JSONObject b = root.getJSONObject("bounds");
        minLat=b.getDouble("min_lat"); maxLat=b.getDouble("max_lat");
        minLon=b.getDouble("min_lon"); maxLon=b.getDouble("max_lon");
        JSONArray ring = root.getJSONArray("boundary_lonlat");
        for (int i=0;i<ring.length();i++) {
            JSONArray p=ring.getJSONArray(i);
            boundary.add(new double[]{p.getDouble(1),p.getDouble(0)});
        }

        double cosLat=Math.cos(Math.toRadians(centerLat));
        try (DataInputStream in = new DataInputStream(context.getAssets().open("points_xy_cm.bin"))) {
            for (int i=0;i<10000;i++) {
                int xLo=in.readUnsignedByte(), xHi=in.readUnsignedByte();
                int yLo=in.readUnsignedByte(), yHi=in.readUnsignedByte();
                short xCm=(short)(xLo | (xHi<<8));
                short yCm=(short)(yLo | (yHi<<8));
                double x=xCm/100.0, y=yCm/100.0;
                double lat=centerLat + y/111320.0;
                double lon=centerLon + x/(111320.0*cosLat);
                points.add(new Point(i+1,lat,lon));
            }
            if (in.read()!=-1) throw new IllegalStateException("point binary has trailing bytes");
        }
        if (points.size()!=10000) throw new IllegalStateException("GPS point count="+points.size());
    }

    public boolean contains(double lat, double lon) {
        boolean inside=false;
        int n=boundary.size(), j=n-1;
        for (int i=0;i<n;i++) {
            double yi=boundary.get(i)[0], xi=boundary.get(i)[1];
            double yj=boundary.get(j)[0], xj=boundary.get(j)[1];
            boolean cross=((yi>lat)!=(yj>lat)) &&
                    (lon < (xj-xi)*(lat-yi)/((yj-yi)==0 ? 1e-15 : (yj-yi))+xi);
            if (cross) inside=!inside;
            j=i;
        }
        return inside;
    }

    public Point nearest(double lat, double lon) {
        double clat=Math.cos(Math.toRadians(centerLat));
        Point best=points.get(0); double bestD=Double.POSITIVE_INFINITY;
        for (Point p: points) {
            double dx=(p.lon-lon)*clat, dy=p.lat-lat, d=dx*dx+dy*dy;
            if (d<bestD) { bestD=d; best=p; }
        }
        return best;
    }

    public double normEast(double lon) { return (lon-minLon)/Math.max(1e-15,maxLon-minLon); }
    public double[] localMeters(double lat, double lon) {
        double y=(lat-centerLat)*111320.0;
        double x=(lon-centerLon)*111320.0*Math.cos(Math.toRadians(centerLat));
        return new double[]{x,y};
    }
}
