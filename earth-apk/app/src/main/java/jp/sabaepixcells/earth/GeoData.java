package jp.sabaepixcells.earth;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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

    /*
     * Google Maps screenshot calibration used to create the authoritative
     * nishiyama_lawn_10000_gps_googlemaps_corrected.csv.
     *
     * Re-generating the 10,000 points here avoids bundling a large CSV in the APK,
     * while preserving the same Point IDs and positions (to ~1e-9 deg).
     */
    private static final double LAT_X = -1.3611247142353734e-09;
    private static final double LAT_Y = -1.219018419387763e-06;
    private static final double LAT_C = 35.951083841105415;
    private static final double LON_X =  1.5071272179468116e-06;
    private static final double LON_Y = -1.3044001434536767e-09;
    private static final double LON_C = 136.18085502157922;

    private static final int[][] PX_BOUNDARY = new int[][]{
            {855,158},{910,152},{965,153},{1025,160},{1080,175},{1130,198},
            {1170,230},{1198,270},{1218,320},{1227,375},{1226,430},{1216,485},
            {1198,530},{1172,568},{1138,598},{1098,621},{1052,638},{1000,649},
            {945,653},{890,650},{840,641},{795,625},{758,600},{728,570},
            {706,535},{691,495},{681,450},{676,405},{678,360},{686,315},
            {700,275},{720,238},{746,207},{776,184},{812,168}
    };

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

        generateAuthoritativePoints();
        if (points.size()!=10000) throw new IllegalStateException("GPS point count="+points.size());
    }

    private static double halton(int index, int base) {
        double f=1.0, r=0.0;
        int i=index;
        while (i>0) {
            f/=base;
            r += f*(i%base);
            i/=base;
        }
        return r;
    }

    private static boolean insidePixelPolygon(double x, double y) {
        boolean inside=false;
        int n=PX_BOUNDARY.length, j=n-1;
        for (int i=0;i<n;i++) {
            double xi=PX_BOUNDARY[i][0], yi=PX_BOUNDARY[i][1];
            double xj=PX_BOUNDARY[j][0], yj=PX_BOUNDARY[j][1];
            boolean cross=((yi>y)!=(yj>y)) &&
                    (x < (xj-xi)*(y-yi)/((yj-yi)==0 ? 1e-15 : (yj-yi))+xi);
            if (cross) inside=!inside;
            j=i;
        }
        return inside;
    }

    private void generateAuthoritativePoints() {
        points.clear();
        double minX=PX_BOUNDARY[0][0], maxX=minX, minY=PX_BOUNDARY[0][1], maxY=minY;
        for (int[] p: PX_BOUNDARY) {
            if (p[0]<minX) minX=p[0]; if (p[0]>maxX) maxX=p[0];
            if (p[1]<minY) minY=p[1]; if (p[1]>maxY) maxY=p[1];
        }
        int seq=1;
        while (points.size()<10000) {
            double x=minX + halton(seq,2)*(maxX-minX);
            double y=minY + halton(seq,3)*(maxY-minY);
            if (insidePixelPolygon(x,y)) {
                double lat=LAT_X*x + LAT_Y*y + LAT_C;
                double lon=LON_X*x + LON_Y*y + LON_C;
                points.add(new Point(points.size()+1,lat,lon));
            }
            seq++;
        }
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
